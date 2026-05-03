# 07 · Hooks 自动化系统

> Hooks 是 Jimi 的"**事件驱动钩子机制**"——在工具调用、用户输入、会话生命周期等关键节点触发自定义脚本或 Agent 任务。设计上**全面对齐 Claude Code 的 Hooks 规范**（stdin JSON / exit code 决策 / matcher 正则），并在此之上扩展了 Agent 切换、错误捕获、Agent Teams 事件等 Jimi 独有触发点。

---

## 1. 为什么需要 Hooks？

LLM 驱动的 Agent 经常需要与外部系统打通：

| 场景 | 对应 Hook |
|------|-----------|
| 写完 Java 文件自动 `google-java-format -i` | `POST_TOOL_USE` + `tools: [WriteFile]` + `file_patterns: [*.java]` |
| 禁止 LLM 跑 `rm -rf /` | `PRE_TOOL_USE` + `matcher: ".*rm -rf.*"` → exit 2 阻塞 |
| 用户提交敏感词时终止 | `USER_PROMPT_SUBMIT` + `matcher: ".*(密码\|password).*"` |
| 会话启动时加载项目环境变量 | `SESSION_START` |
| 子 Agent 完成后自动汇总 | `SUBAGENT_STOP` / `TEAMMATE_TASK_COMPLETE` |
| 编译报错自动调 Agent 修复 | `ON_ERROR` + execution.type=agent |

Hook 的本质是**把确定性工作从 LLM 手里拿走**——LLM 只负责创造性推理，格式化、审计、备份、通知交给 Hook 脚本。

---

## 2. 源码目录结构

```
io.leavesfly.jimi.core.hook/
├── HookType.java          // 15 个触发类型枚举 + fromString 命名兼容
├── HookSpec.java          // 配置模型：name/description/enabled/trigger/execution/conditions/priority
├── HookTrigger.java       // 触发条件：type/matcher/tools/filePatterns/agentName/errorPattern
├── ExecutionSpec.java     // 执行配置：type(script/agent/composite) + script/scriptFile/agent/task/steps
├── HookCondition.java     // 条件：env_var / file_exists / script / tool_result_contains
├── HookContext.java       // 运行时上下文 + toStdinJson()
├── HookLoader.java        // @Service：委托 YamlConfigLoader 三层加载
├── HookRegistry.java      // @Service：@PostConstruct 自动加载，提供 trigger(type, context)
└── HookExecutor.java      // @Service：执行脚本/Agent/Composite，返回 HookResult（含 blocked 语义）

集成点：
├── core/engine/toolcall/ToolDispatcher.java    // PRE_TOOL_USE / POST_TOOL_USE / POST_TOOL_USE_FAILURE
├── core/engine/AgentExecutor.java              // USER_PROMPT_SUBMIT / STOP / ON_ERROR
└── core/JimiFactory.java                       // SESSION_START

用户配置位置：
~/.jimi/hooks/*.yaml         (GLOBAL，对所有项目生效)
<project>/.jimi/hooks/*.yaml (PROJECT，仅当前项目生效)
resources/hooks/*.yaml       (classpath，JAR 内置示例)
```

---

## 3. 15 种 HookType 全景图

`io.leavesfly.jimi.core.hook.HookType` 共 **15 个枚举值**，按语义分为 4 组。下表里的"代码埋点"一栏基于**实际 `file_grep HookType.XXX`** 的全仓库扫描结果——只有出现在主流程代码里（非 `HookType.java`/`HookTrigger.java`/`HookRegistry.java` 等定义与分派文件）才算真正有埋点。

### 3.1 对齐 Claude Code 标准（9 个）

| HookType | Claude Code 对应 | 代码埋点 | 源码位置 | 阻塞能力 |
|----------|-----------------|:--------:|----------|:--------:|
| `USER_PROMPT_SUBMIT` | UserPromptSubmit | ✅ 已接入 | `AgentExecutor.execute(List<ContentPart>, boolean)` 首段 | ⚠️ 定义阻塞但未消费结果（见 §8） |
| `PRE_TOOL_USE` | PreToolUse | ✅ 已接入 | `ToolDispatcher.executeValidToolCall` 第 1 步 | ⚠️ 定义阻塞但实际未阻塞（见 §8） |
| `POST_TOOL_USE` | PostToolUse | ✅ 已接入 | `ToolDispatcher` flatMap 内 `.subscribe()` | 异步，不阻塞 |
| `POST_TOOL_USE_FAILURE` | PostToolUseFailure | ✅ 已接入 | `ToolDispatcher` onErrorResume 内 `.subscribe()` | 异步 |
| `STOP` | Stop | ✅ 已接入 | `AgentExecutor.onExecutionSuccess` | 异步 `.subscribe()` |
| `SESSION_START` | SessionStart | ✅ 已接入 | `JimiFactory.create()`（`context.restore().then(...)` 链条末尾） | `.then` 串联但 `onErrorResume` 吞异常 |
| `NOTIFICATION` | Notification | ❌ **仅有枚举定义，主流程无埋点** | — | — |
| `SUBAGENT_STOP` | SubagentStop | ❌ **仅有枚举定义，主流程无埋点** | — | — |
| `SESSION_END` | SessionEnd | ❌ **仅有枚举定义，主流程无埋点** | — | — |

### 3.2 Jimi 扩展（3 个）

| HookType | 代码埋点 | 源码位置 |
|----------|:--------:|----------|
| `ON_ERROR` | ✅ 已接入 | `AgentExecutor.onExecutionError`（`doOnError` 回调） |
| `PRE_AGENT_SWITCH` | ❌ **仅有枚举定义，主流程无埋点** | — |
| `POST_AGENT_SWITCH` | ❌ **仅有枚举定义，主流程无埋点** | — |

### 3.3 Agent Teams 扩展（3 个）

| HookType | 代码埋点 |
|----------|:--------:|
| `TEAM_START` | ❌ **仅有枚举定义，主流程无埋点** |
| `TEAM_COMPLETE` | ❌ **仅有枚举定义，主流程无埋点** |
| `TEAMMATE_TASK_COMPLETE` | ❌ **仅有枚举定义，主流程无埋点** |

> **重要事实**：上表里所有标 ❌ 的类型，在当前代码库里通过 `file_grep "HookType.XXX"` 搜索**不存在任何真实触发调用**——它们只是预留的枚举值，配了对应 type 的 Hook **永远不会被触发**。真正可用的只有 7 个：`USER_PROMPT_SUBMIT` / `PRE_TOOL_USE` / `POST_TOOL_USE` / `POST_TOOL_USE_FAILURE` / `STOP` / `ON_ERROR` / `SESSION_START`。详细的 Agent Team 模型见 **[03 · Agent 多智能体系统](03-Agent多智能体系统.md)**，其埋点完善工作可作为 Hook 系统后续的扩展方向。

### 3.4 `HookType.fromString` 的命名兼容

配置文件里写 type 时，以下写法**等价**（源码 `HookType.fromString`）：

```
"PreToolUse"    ↔ "PRE_TOOL_USE"  （CamelCase 自动转 SNAKE_CASE）
"pre-tool-use"  ↔ "PRE_TOOL_USE"  （连字符转下划线）
"PRE_TOOL_CALL" → "PRE_TOOL_USE"  （旧版命名兼容）
"POST_TOOL_CALL"→ "POST_TOOL_USE"
"PRE_USER_INPUT"→ "USER_PROMPT_SUBMIT"
"ON_SESSION_START"→ "SESSION_START"
```

---

## 4. Hook 配置文件结构

### 4.1 完整示例

```yaml
name: "auto-format"                  # 必需，全局唯一
description: "Java 文件写入后自动格式化"  # 必需
enabled: true                        # 可选，默认 true
priority: 10                         # 可选，默认 0；数值越大越先执行

trigger:
  type: "POST_TOOL_USE"              # 必需，见 §3
  matcher: "WriteFile|StrReplaceFile"  # 可选，正则
  tools:                             # 可选，列表精确匹配（与 matcher AND 关系）
    - "WriteFile"
  file_patterns:                     # 可选，简化 glob
    - "*.java"
  agentName: "Code-Agent"            # 可选，Agent 切换/子 Agent 事件专用
  errorPattern: ".*OOM.*"            # 可选，ON_ERROR 事件专用

execution:
  type: "script"                     # 必需：script / agent / composite
  script: |                          # type=script 时必需（或 scriptFile 二选一）
    #!/bin/bash
    google-java-format -i "${MODIFIED_FILE}"
  scriptFile: "/path/to/format.sh"   # 可选，与 script 二选一
  workingDir: "${JIMI_WORK_DIR}"
  timeout: 30                        # 可选，默认 60 秒
  async: true                        # 可选，默认 false
  environment:
    MY_ENV: "value"
  agent: "Code-Agent"                # type=agent 时必需
  task: "修复编译错误"                # type=agent 时必需
  steps:                             # type=composite 时必需
    - type: "script"
      script: "mvn clean"
      description: "清理"
      continueOnFailure: false
      timeout: 60

conditions:                          # 可选，AND 关系；任一不满足则跳过
  - type: "env_var"
    var: "AUTO_FORMAT"
    value: "true"                    # 可省；省则仅检查存在
```

### 4.2 字段必需性速查

| 字段 | 必需 | 源码验证位置 |
|------|:----:|----------------|
| `HookSpec.name` | ✓ | `HookSpec.validate()` 抛 `IllegalArgumentException` |
| `HookSpec.description` | ✓ | 同上 |
| `HookSpec.trigger` | ✓ | 同上 |
| `HookSpec.execution` | ✓ | 同上 |
| `HookTrigger.type` | ✓ | `HookTrigger.validate()` |
| `ExecutionSpec.type` | ✓ | `ExecutionSpec.validate()` |
| `ExecutionSpec.script` / `scriptFile`（二选一） | ✓ when `type=script` | 同上 |
| `ExecutionSpec.agent` + `task` | ✓ when `type=agent` | 同上 |
| `ExecutionSpec.steps` | ✓ when `type=composite` | 同上 |

> 启动时加载：每个文件被 `HookLoader.loadAllHooks` 解析成 `HookSpec`，调 `validate()` 抛异常则**打 error 日志但不中断**，继续加载其他文件。

---

## 5. `HookTrigger` 的匹配逻辑

`HookRegistry.matches(hook, context)` 实际逻辑（全部为 **AND**，任一不满足则跳过）：

```
matcher  (正则) → 匹配 resolveMatchTarget(type, context) 的目标
  + tools  (列表) → 匹配 context.toolName（仅 *_TOOL_USE 事件）
  + filePatterns (glob) → 匹配 context.affectedFiles（任一文件命中即可）
  + agentName (精确) → 匹配 context.agentName
  + errorPattern (正则) → 匹配 context.errorMessage
```

### 5.1 `resolveMatchTarget` 的分派

`matcher` 匹配对象随 HookType 变化：

| HookType | `matcher` 目标 |
|----------|---------------|
| `PRE_TOOL_USE` / `POST_TOOL_USE` / `POST_TOOL_USE_FAILURE` | `context.toolName` |
| `PRE_AGENT_SWITCH` / `POST_AGENT_SWITCH` / `SUBAGENT_STOP` | `context.agentName` |
| `NOTIFICATION` | `context.notificationType` |
| `ON_ERROR` | `context.errorMessage` |
| 其他类型 | `null`（matcher 不生效） |

所以 `USER_PROMPT_SUBMIT` 上**配 `matcher` 是无效的**——若需根据用户输入过滤，请在 script 里读 `$USER_INPUT` 自行判断（见 §7.2 变量表）。

### 5.2 filePatterns 的简化 glob

`HookRegistry.matchesPattern`：

```text
String regex = pattern.replace(".", "\\.")
                      .replace("*", ".*")
                      .replace("?", ".");
return fileName.matches(regex);
```

- **只支持 `*` 和 `?`**，**不支持 `**`（递归目录）**
- 只对 `file.getFileName().toString()` 匹配——所以 `src/**/*.java` 这样的路径会失效，要改写成 `*.java`

### 5.3 matcher 正则失败的降级

`HookTrigger.matchesValue`：

```text
try { return Pattern.matches(matcher, value); }
catch (PatternSyntaxException e) { return matcher.equals(value); }
```

当 `matcher` 不是合法正则时（例如忘记转义），**降级为字符串全等**，不会直接报错。`validate()` 阶段就会在预编译时拒绝非法正则，所以运行时很少走到降级分支。

---

## 6. `ExecutionSpec` 三种执行类型

### 6.1 `script`——Shell 脚本

```
HookExecutor.executeScriptWithResult:
  1. getScriptContent = script ?? read(scriptFile)
  2. scriptRunner.replaceVariables(script, buildVariableMap(context))  ← 变量替换
  3. ProcessBuilder("/bin/bash", "-c", script)
  4. processBuilder.environment().putAll(env)   // 追加 environment + HOOK_TOOL_NAME / HOOK_AGENT_NAME
  5. process.start()
  6. writeStdinJson(process, context)   // 对齐 Claude Code：stdin 传 JSON
  7. 读 stdout/stderr, process.waitFor(timeout)
  8. parseJsonOutput(stdout)   // 仅在 stdout 以 "{" 开头时尝试解析
  9. 根据 exit code 返回 HookResult：
       0 → success
       2 → blocked(reason=stderr, stderr=stderr)
       其他 → success=false, exitCode=X, 非阻塞错误
```

**默认超时 60 秒**（`ExecutionSpec.timeout` 默认值）；超时 `destroyForcibly()` 并返回 `HookResult.error("Script timed out after Ns")`。

### 6.2 `agent`——委派给 Agent

⚠️ **重要现状**：源码 `HookExecutor.executeAgentWithResult` 当前仅**记录日志**：

```text
log.warn("Agent execution delegates to agent '{}' with task: {}",
    execution.getAgent(), resolvedTask);
return HookResult.success();
```

并未真正调用 `AgentExecutor` 执行——注释写明"**后续可通过 AgentExecutor 接入**"。换言之，目前配 `type: agent` 的 Hook **只会写一行日志，不会真的跑 Agent 任务**。生产使用请选 `type: script` 或 `composite`。

### 6.3 `composite`——按顺序执行多步

```text
return Flux.fromIterable(steps)
    .concatMap(step -> executeStep(...))
    .takeUntil(result -> !result.isSuccess() && !isStepContinueOnFailure(steps, result))
    .last()
    .defaultIfEmpty(HookResult.success());
```

每个 `ExecutionStep` 启一个 `/bin/bash -c script`，`redirectErrorStream(true)` 合并 stderr 到 stdout；默认步骤超时 60 秒。

⚠️ **`isStepContinueOnFailure` 的真实行为**：

```text
private boolean isStepContinueOnFailure(List<ExecutionStep> steps, HookResult result) {
    return steps.stream().anyMatch(ExecutionStep::isContinueOnFailure);
}
```

**任何一个步骤** 配了 `continueOnFailure: true`，**整个 composite 在任意步骤失败都会继续**——这与"每步独立判断自己的 continueOnFailure"的朴素预期**不一致**。如果你只想让最后一步失败时跳过，请为**所有步骤**显式声明 `continueOnFailure: false` 以外的行为，或者拆成多个 Hook。

### 6.4 `async`——异步模式

```text
if (execution.isAsync()) {
    executionMono.subscribeOn(Schedulers.boundedElastic()).subscribe(...);
    return Mono.just(HookResult.success());   // 立刻返回 success
}
```

`async: true` 时**同步返回假成功**，真实执行结果只会出现在日志里——对 PRE_TOOL_USE 阻塞场景**必须关闭 async**，否则 block 永远不会生效。

---

## 7. `HookContext` 与 stdin JSON

### 7.1 `toStdinJson` 的字段映射（对齐 Claude Code）

| HookContext 字段 | stdin JSON key |
|------------------|---------------|
| `hookType` | `hook_event_name` |
| `workDir` | `cwd` |
| `sessionId` | `session_id` |
| `toolName` | `tool_name` |
| `toolCallId` | `tool_use_id` |
| `toolInput` (Map) | `tool_input` |
| `toolResult` | `tool_response` |
| `agentName` | `agent_name` |
| `previousAgentName` | `previous_agent` |
| `errorMessage` | `error` |
| `userInput` | `prompt` |
| `notificationMessage` | `message` |
| `notificationType` | `notification_type` |
| `lastAssistantMessage` | `last_assistant_message` |
| `stopHookActive` | `stop_hook_active`（布尔，始终输出） |

脚本里典型读法：

```bash
#!/bin/bash
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name')
TOOL_INPUT=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
PROMPT=$(echo "$INPUT" | jq -r '.prompt // empty')
```

### 7.2 可用的 `${VAR}` 变量（`HookExecutor.buildVariableMap`）

脚本内可直接用 `${VAR}` 占位符，执行前被 `ScriptRunner.replaceVariables` 替换：

| 变量 | 含义 |
|------|------|
| `${JIMI_WORK_DIR}` | 工作目录 |
| `${HOME}` | `System.getProperty("user.home")` |
| `${TOOL_NAME}` | 触发的工具名 |
| `${TOOL_RESULT}` | 工具执行结果（POST_TOOL_USE） |
| `${MODIFIED_FILES}` | 空格分隔的所有受影响文件路径 |
| `${MODIFIED_FILE}` | 第一个受影响文件路径 |
| `${AGENT_NAME}` / `${CURRENT_AGENT}` | Agent 名（同义） |
| `${PREVIOUS_AGENT}` | 前一个 Agent |
| `${USER_INPUT}` | 用户输入（USER_PROMPT_SUBMIT 事件） |
| `${LAST_ASSISTANT_MESSAGE}` | 最后一条 assistant 消息 |
| `${NOTIFICATION_MESSAGE}` | 通知内容 |
| `${ERROR_MESSAGE}` | 错误信息 |

此外会额外注入 **process 环境变量**（非替换，脚本内用 `$HOOK_TOOL_NAME` 读取）：

- `HOOK_TOOL_NAME`
- `HOOK_AGENT_NAME`

---

## 8. 关键落差：PRE_TOOL_USE 的阻塞语义

这是**理解 Jimi Hooks 当前能力最重要的一点**，也是对齐源码必须写清的事实。

### 8.1 `HookExecutor` 层的阻塞定义

`HookExecutor.HookResult` 有 `boolean blocked` 字段；当脚本 **exit code = 2** 时返回 `HookResult.blocked(stderr, stderr)`——这部分**完全对齐 Claude Code**。

### 8.2 `ToolDispatcher` 层的集成

```text
// ToolDispatcher.executeValidToolCall
return triggerHookSafely(HookType.PRE_TOOL_USE, preHookContext)
        .then(toolRegistry.execute(toolName, arguments))   // ← 无条件继续
        .flatMap(result -> ...);

private Mono<Void> triggerHookSafely(HookType type, HookContext context) {
    if (hookRegistry == null) return Mono.empty();
    return hookRegistry.trigger(type, context)      // ← Mono<Void>，丢失了 HookResult.blocked
            .onErrorResume(e -> Mono.empty());
}
```

`hookRegistry.trigger()` 返回 `Mono<Void>`，**调用链在这里把 `HookResult.blocked` 字段完全丢弃**。因此：

- ✅ PRE_TOOL_USE Hook 脚本**会被执行**
- ✅ 脚本可以 `exit 2` 打出 block 日志（`log.warn("Hook blocked action: ...")`）
- ❌ **但后续的 `toolRegistry.execute()` 仍然会正常运行**，工具不会被真正阻止

如果你要让 Hook 真正阻塞工具执行，当前有两种可行方式：

1. **改走 Approval 审批**：通过 `ApprovalManager` 拦截危险工具，而非靠 PRE_TOOL_USE（详见 [04 · 工具系统](04-工具系统与ToolRegistry.md) 的 Approval 章节）。
2. **在 PRE_TOOL_USE 脚本里直接让工具"失效"**：例如脚本临时删除目标文件、抢占文件锁，让后续工具自然报错——由 `POST_TOOL_USE_FAILURE` 捕获并告警。

> 这是 Jimi 当前版本设计层面的**已知落差**——`HookResult.blocked` 已经建模好，但未在工具调用链路里实际消费。未来若要真正支持阻塞语义，只需把 `triggerHookSafely` 改返 `Mono<HookResult>` 并在阻塞时 `Mono.error()`/`Mono.empty()` 中断即可。

### 8.3 其他事件的阻塞情况

| 事件 | 是否真阻塞 | 原因 |
|------|:----------:|------|
| `USER_PROMPT_SUBMIT` | ❌ 不阻塞 | `AgentExecutor` 同样 `.then(appendMessage)` 忽略 HookResult |
| `SESSION_START` | ❌ 不阻塞 | `JimiFactory` `.onErrorResume` 吞异常，加载失败也继续 |
| `POST_TOOL_USE` / `POST_TOOL_USE_FAILURE` / `STOP` / `ON_ERROR` | — | 全部 `.subscribe()` 异步，设计上就不阻塞 |

---

## 9. `HookRegistry.trigger` 的执行流

```
trigger(type, context)
  ├── hooks = hooksByType.get(type) ?? []
  ├── filter(h -> h.enabled && matches(h, context))   // §5 的匹配逻辑
  ├── 若匹配为空 → Mono.empty()
  └── Flux.fromIterable(matched)
        .concatMap(hook -> executor.execute(hook, context)
                                   .onErrorResume(e -> Mono.empty()))
        .then()
```

- **排序**：注册时已按 `priority` 降序排序（`Comparator.comparingInt(HookSpec::getPriority).reversed()`）
- **串行**：`concatMap` 保证同类型的 Hook **依次执行**（上一个完成才跑下一个）；**不同类型的 Hook 之间互不影响**
- **错误隔离**：单个 Hook 抛异常只会被 `log.error` 记录，不影响同批其他 Hook，也不影响主流程

---

## 10. `HookCondition` 的 4 种条件

`conditions` 数组是 **AND 语义**——所有条件满足才会执行 Hook。`HookExecutor.checkCondition` 分派：

### 10.1 `env_var`

```yaml
conditions:
  - type: env_var
    var: AUTO_FORMAT
    value: "true"        # 可省；省则仅检查存在（非 null）
```

### 10.2 `file_exists`

```yaml
conditions:
  - type: file_exists
    path: "${JIMI_WORK_DIR}/pom.xml"
```

- 路径支持 `${VAR}` 替换
- 相对路径会 `context.workDir.resolve(path)`
- 内部用 `Files.exists(path)`

### 10.3 `script`

```yaml
conditions:
  - type: script
    script: |
      #!/bin/bash
      [ $(date +%u) -lt 6 ]    # 仅工作日
```

- 脚本 exit 0 → 满足
- **硬编码 5 秒超时**，超时 `destroyForcibly()` 返回 false

### 10.4 `tool_result_contains`

```yaml
conditions:
  - type: tool_result_contains
    pattern: ".*git commit.*"   # 正则
```

- 用 `context.toolResult.matches(pattern)`——**`String.matches()` 要求整串完全匹配**，若想子串匹配必须自己写 `.*` 包裹

---

## 11. 三层加载与 `YamlConfigLoader`

`HookLoader.loadAllHooks(projectDir)` 委托 `YamlConfigLoader.loadAll("hooks", HookSpec.class, projectDir)` 实现三层加载——**加载顺序决定了优先级**（后加载的同名 Hook 会覆盖先加载的）：

```text
1. classpath:/hooks/*.yaml              （JAR 内置示例）
       ↓
2. ~/.jimi/hooks/*.yaml                 （GLOBAL，所有项目生效）
       ↓
3. <projectDir>/.jimi/hooks/*.yaml      （PROJECT，仅该项目生效，仅当 projectDir != null 时加载）
```

同名 Hook **后加载的覆盖先加载的**（`HookRegistry.registerHook` 检测到重名会先 `unregisterHook(name)` 再注册）——所以当 PROJECT 层加载生效时，**项目级 Hook 可以覆盖全局 Hook**。

### 11.1 JAR 模式下 classpath 层被主动跳过

`YamlConfigLoader.loadFromClasspath` 源码：

```text
if (resource.getProtocol().equals("jar")) {
    log.debug("Running from JAR, skipping classpath {}", subDir);
    return Collections.emptyList();
}
```

也就是说：**当 Jimi 以打包后的 JAR 方式运行时，classpath 层的 `hooks/` 目录会被整体跳过**（与 Skills 的 JAR 白名单机制不同）。所以 Hook 的 JAR 内置示例**只在开发模式（直接跑源码）下生效**，生产环境里用户的 Hook **只能放在 `~/.jimi/hooks/` 或项目 `.jimi/hooks/`**。

### 11.2 ⚠️ 项目级 `.jimi/hooks/` 当前不会被自动加载

这是**当前版本最重要的一个实际落差**，必须如实写明：

`HookRegistry` 的 `@PostConstruct init()` 在 Spring 启动时就运行一次 `loadAndRegisterHooks()`，此时它读的是 `this.projectDirectory`——**初始值为 `null`**。要让 PROJECT 层也被扫描，需要在得到工作目录后调 `HookRegistry.setProjectDirectory(projectDir)` 并 **手动**再调一次 `reloadHooks()`。

但源码全仓库 `file_grep "hookRegistry.setProjectDirectory"` 的结果为 **0 条**——即：

- 对比之下，`JimiFactory.create()` **会调用** `customCommandRegistry.setProjectDirectory(workDir)`（见 `JimiFactory.java` 第 255 行附近）自动加载项目级自定义命令
- 但**从未对 `hookRegistry` 做同样的调用**

因此在当前代码状态下：

- ✅ `classpath:/hooks/*.yaml`：开发模式下生效，JAR 模式下跳过（§11.1）
- ✅ `~/.jimi/hooks/*.yaml`：全局 Hook，始终生效
- ❌ `<projectDir>/.jimi/hooks/*.yaml`：**实际上永远不会被加载**

要让项目级 Hook 生效，当前有两种变通方式：

1. **把 Hook 放到 `~/.jimi/hooks/`**，用 `trigger.matcher` 或 `conditions.file_exists` 把逻辑限制在该项目。
2. **扩展源码**：在 `JimiFactory.create()` 里 `customCommandRegistry.setProjectDirectory` 旁加一行 `hookRegistry.setProjectDirectory(workDir)` 并调 `reloadHooks()`，并让 `SESSION_START` 埋点在重载之后再触发。

### 11.3 热加载

`HookRegistry.reloadHooks()` 会清空索引后重新执行 `loadAndRegisterHooks()`。源码层面 `file_grep "WatchService"` / `file_grep "fileWatch"` 在 `hook` 包下**均为 0 条结果**——没有监控文件变更的 `WatchService`，想热加载只能**外部手动调用** `reloadHooks()`（例如通过自定义命令触发，详见 [09 · 自定义命令与 CLI 交互](09-自定义命令与CLI交互.md)）。

### 11.4 `HookRegistry` 对外 API

| 方法 | 用途 |
|------|------|
| `registerHook(HookSpec)` | 动态注册（验证失败抛 `RuntimeException`） |
| `unregisterHook(name)` | 注销 |
| `getHook(name)` / `hasHook(name)` / `getHookCount()` | 查询 |
| `getHooks(HookType)` / `getAllHooks()` | 列举 |
| `enableHook(name)` / `disableHook(name)` | 切换启用状态 |
| `reloadHooks()` | 重新扫描所有目录 |
| `getHookStatistics()` | 按类型统计 |
| `setProjectDirectory(Path)` | 设置项目目录。**当前代码库内无任何调用方**（见 §11.2），生效需外部显式调用并配合 `reloadHooks()` |

---

## 12. 实战示例

### 12.1 示例 A：Java 格式化

`~/.jimi/hooks/auto-format-java.yaml`：

```yaml
name: auto-format-java
description: 写 Java 文件后自动 google-java-format
enabled: true
priority: 10
trigger:
  type: POST_TOOL_USE
  tools: [WriteFile, StrReplaceFile]
  file_patterns: ["*.java"]
execution:
  type: script
  async: true
  timeout: 30
  script: |
    #!/bin/bash
    for f in ${MODIFIED_FILES}; do
      if [[ "$f" == *.java ]]; then
        google-java-format -i "$f" 2>&1 || true
      fi
    done
conditions:
  - type: env_var
    var: JIMI_AUTO_FORMAT
    value: "true"
```

### 12.2 示例 B：危险命令记录（注意：仅记录不阻塞——见 §8）

```yaml
name: audit-dangerous-commands
description: 记录所有 rm / mv 等危险命令
trigger:
  type: PRE_TOOL_USE
  tools: [BashTool]              # BashTool 的 getName() 返回 "BashTool"（见 BashTool.java 第 68 行）
  # matcher 可省：tools 列表已精确匹配；若要用正则则须整串匹配（Pattern.matches），
  # 例如 matcher: "BashTool" 或 matcher: ".*Bash.*" 均可命中 "BashTool"
execution:
  type: script
  script: |
    #!/bin/bash
    INPUT=$(cat)
    CMD=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
    if [[ "$CMD" =~ (rm -rf|mv .*|dd if) ]]; then
      echo "[AUDIT] $(date): $CMD" >> ~/.jimi/audit.log
      # exit 2 虽然会被 HookExecutor 识别为 blocked，
      # 但当前版本 ToolDispatcher 不真正阻塞——详见 07 篇 §8
    fi
    exit 0
```

> ⚠️ 注意 `HookContext.toolInput` 当前的 `ToolDispatcher` 埋点代码里**并未填充**（只塞了 `toolName/toolCallId/workDir`，见 §3.1 与 `ToolDispatcher.executeValidToolCall` 源码），所以上面的 `.tool_input.command` 在 stdin JSON 里当前会是 `null`——这是对 Claude Code 标准的**另一个现存落差**。若要在 PRE_TOOL_USE 脚本里拿到命令参数，目前要么改走自定义变量、要么扩展 `ToolDispatcher` 把 `arguments` 解析成 `Map` 后 `.toolInput(map)`。

### 12.3 示例 C：会话启动加载本地配置

```yaml
name: load-project-env
description: 会话启动时加载 .env 到日志
trigger:
  type: SESSION_START
execution:
  type: script
  script: |
    if [ -f "${JIMI_WORK_DIR}/.env" ]; then
      echo "[Hook] Loaded .env from ${JIMI_WORK_DIR}" >> ~/.jimi/session.log
    fi
```

### 12.4 示例 D：Composite 流水线

```yaml
name: pre-commit-pipeline
description: 写完 java 文件后跑 clean + test + package
trigger:
  type: POST_TOOL_USE
  tools: [WriteFile]
  file_patterns: ["*.java"]
execution:
  type: composite
  steps:
    - type: script
      script: "mvn -q clean"
      description: "清理"
    - type: script
      script: "mvn -q test"
      description: "跑测试"
      continueOnFailure: false
    - type: script
      script: "mvn -q package -DskipTests"
      description: "打包"
```

⚠️ 提醒：由于 `isStepContinueOnFailure` 是 `anyMatch`（§6.3），若任何一步配了 `continueOnFailure: true`，**整个 composite 在所有失败时都继续**——当前如需严格按步骤独立控制，建议要么全部不配 `continueOnFailure`，要么拆成多个 Hook。

### 12.5 示例 E：ON_ERROR 告警

```yaml
name: error-slack-notify
description: Agent 执行出错时发 Slack
trigger:
  type: ON_ERROR
  errorPattern: ".*(OOM|OutOfMemory).*"
execution:
  type: script
  async: true
  script: |
    curl -X POST "$SLACK_WEBHOOK" -d "{\"text\":\"Jimi error: ${ERROR_MESSAGE}\"}"
```

---

## 13. 故障排查

| 现象 | 排查路径 |
|------|----------|
| Hook 未触发 | 日志搜 `"Registered hook: <name>"`；若缺失 → 文件路径或 `validate()` 失败（搜 `"Invalid hook config"`） |
| Hook 已注册但未执行 | 日志搜 `"No matching hooks for type"` → matcher/tools/filePatterns/agentName/errorPattern 有一项不匹配 |
| `PRE_TOOL_USE` exit 2 但工具仍然执行 | **这是预期行为**（§8）——改走 Approval 或在脚本里让工具自然失败 |
| 脚本超时被杀 | 查 `ExecutionSpec.timeout`（默认 60s），改大或 `async: true` |
| 变量 `${XXX}` 未替换 | 确认变量名在 §7.2 表内；ScriptRunner 对未知变量原样保留 |
| stdin JSON 读不到 | 脚本起始加 `INPUT=$(cat)`，不要用 `$STDIN` 这类不存在的变量 |
| `filePatterns: ["src/**/*.java"]` 不生效 | Jimi 的 glob 简化版不支持 `**`——改为 `*.java` 只按文件名匹配 |
| `type: agent` 只打日志不跑 Agent | **已知限制**（§6.2）——换 script 或 composite |
| `composite` 步骤失败仍继续 | 检查 `continueOnFailure` 的 `anyMatch` 现象（§6.3） |
| 项目级 `.jimi/hooks/` 放了 YAML 但没生效 | **这是预期行为**（§11.2）——`JimiFactory` 当前不会调 `hookRegistry.setProjectDirectory()`，所以 `projectDirectory = null`、PROJECT 层被跳过。临时方案：把 Hook 搬到 `~/.jimi/hooks/` |
| 配了 `SUBAGENT_STOP` / `SESSION_END` / `NOTIFICATION` / `PRE_AGENT_SWITCH` / `POST_AGENT_SWITCH` / `TEAM_*` 的 Hook 从不触发 | **这是预期行为**（§3）——这些 HookType 仅有枚举定义，代码库里无任何埋点 |
| `PRE_TOOL_USE` 脚本里 stdin JSON 的 `tool_input` 是 `null` | **这是预期行为**（§12.2 提示）——`ToolDispatcher` 埋 PRE_TOOL_USE 时未填充 `HookContext.toolInput` |

---

## 14. 与其他子系统的关系

- **工具链**：`ToolDispatcher.executeValidToolCall` 埋 `PRE_TOOL_USE` / `POST_TOOL_USE` / `POST_TOOL_USE_FAILURE` 三个埋点，见 [04 · 工具系统](04-工具系统与ToolRegistry.md)
- **Agent 执行器**：`AgentExecutor.execute(userInput, skipKnowledge)` 埋 `USER_PROMPT_SUBMIT`（首段）；`onExecutionSuccess` 埋 `STOP`；`onExecutionError` 埋 `ON_ERROR`，见 [03 · Agent 多智能体系统](03-Agent多智能体系统.md)
- **会话启动**：`JimiFactory.create()` 的 `context.restore().then(...)` 链条末尾埋 `SESSION_START`，见 [02 · 系统架构与核心引擎](02-系统架构与核心引擎.md)
- **ExecutionSpec 复用**：`command/custom/CustomCommandSpec` 直接 `import io.leavesfly.jimi.core.hook.ExecutionSpec`，所以自定义命令的 `script/agent/composite` 三种执行语义**与 Hook 完全一致**（`ConfigurableCommandHandler` 里的 switch 也是 `"script" / "agent" / "composite"` 三分支），见 [09 · 自定义命令与 CLI 交互](09-自定义命令与CLI交互.md)
- **YAML 加载**：三层加载底层统一为 `common/YamlConfigLoader.loadAll(subDir, type, projectDir)`，同样被 `CustomCommandLoader` 复用。Skills 走的是自己的 `SkillLoader`（因为 Skill 是目录 + SKILL.md 结构而非单 YAML 文件）

---

## 15. 关键文件速查

| 文件 | 作用 |
|------|------|
| `core/hook/HookType.java` | 15 个触发类型枚举 + `fromString` 命名兼容 |
| `core/hook/HookSpec.java` | 配置数据模型，`validate()` 做启动校验 |
| `core/hook/HookTrigger.java` | 匹配条件（type/matcher/tools/filePatterns/agentName/errorPattern）|
| `core/hook/ExecutionSpec.java` | script/agent/composite + timeout/async/environment |
| `core/hook/HookCondition.java` | 4 种条件类型 |
| `core/hook/HookContext.java` | 运行时上下文 + `toStdinJson()` Claude Code 兼容 |
| `core/hook/HookLoader.java` | 委托 `YamlConfigLoader` 三层加载 |
| `core/hook/HookRegistry.java` | `@PostConstruct init()` + `trigger(type, ctx)` |
| `core/hook/HookExecutor.java` | 执行脚本/Agent/Composite + `HookResult` + exit code 决策 |
| `core/engine/toolcall/ToolDispatcher.java` | PRE/POST/POST_FAILURE 三埋点 |
| `core/engine/AgentExecutor.java` | USER_PROMPT_SUBMIT / STOP / ON_ERROR 三埋点 |
| `core/JimiFactory.java` | SESSION_START 埋点 |
| `common/YamlConfigLoader.java` | 三层 YAML 加载器 |
| `common/ScriptRunner.java` | `replaceVariables(script, map)` 变量替换 |
| `docs/HOOKS.md` | 用户向导型文档（字段含义、场景范例） |

---

**[⬅ 上一篇：06 · Skills 技能包系统](06-Skills技能包系统.md)** | **[回到首页](Home.md)** | **[下一篇：08 · 代码图谱与 RAG 检索 ➡](08-代码图谱与RAG检索.md)**
