# 09 · 自定义命令与 CLI 交互

> 本篇目标：**从 JLine 按下回车**到**元命令被执行**，把 Jimi 的 CLI 交互层与命令系统的全部关节点逐一拆开。全文每一个类名、方法名、字段名、日志字符串、默认值、配置键，均来自源码亲读，**未亲读的字段绝不书写**；但凡设计与实现存在落差，会在相应章节明示。

**范围声明**：本篇不复述 Agent/LLM/Tool/Skill/Hook/Graph 的内部逻辑（已分别见 03/04/05/06/07/08 篇），仅聚焦 `ui/shell/*` 与 `command/*` 两个包的协作。

---

## 1 · 一张图看懂从按键到执行

```
┌─────────────────────────────── JLine 终端层 ────────────────────────────────┐
│  Terminal (system=true, UTF-8)                                               │
│    └─ LineReader (JimiParser + JimiCompleter + JimiHighlighter)              │
│         └─ lineReader.readLine(prompt)  ← 阻塞等待用户按回车                 │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                       │ String input
                   ┌───────────────────▼────────────────────┐
                   │  ShellUI#processInput(String)           │
                   │  - "" 空行 → return true                │
                   │  - "exit"/"quit" → return false (退出)  │
                   │  - 构建 ShellContext                    │
                   │  - 按优先级遍历 InputProcessor 列表     │
                   └───────────────────┬────────────────────┘
                                       │ 首个 canProcess()=true 的处理器独占
       ┌───────────────────────────────┼────────────────────────────────────┐
       │ priority=10                   │ priority=20                        │ priority=100
       │ MetaCommandProcessor          │ ShellShortcutProcessor             │ AgentCommandProcessor
       │ canProcess: input.startsWith("/") │ canProcess: input.startsWith("!") │ canProcess: 恒 true
       │                               │                                    │
       │ 1) 去 "/" → "cmd arg1 arg2"   │ 1) 去 "!" → "ls -la"               │ engineClient.runCommand
       │ 2) split("\\s+", 2) 取 cmd    │ 2) hasTool("BashTool") 校验        │   (input).block()
       │ 3) registry.hasCommand(cmd)   │ 3) 构 {"command","timeout":60}     │
       │ 4) 构 CommandContext          │ 4) executeTool("BashTool", args)   │
       │ 5) registry.execute(cmd, ctx) │                                    │
       └───────────────┬───────────────┴────────────────────────────────────┘
                       │
                       ▼
    ┌─────────────────────────────────────────────────────────────┐
    │ CommandRegistry (Spring @Component)                           │
    │   - Map<String, CommandHandler>        handlers               │
    │   - Map<String, String>                aliases (alias → name) │
    │   - Map<String, List<CommandHandler>>  categorizedHandlers    │
    │                                                               │
    │   execute(name, ctx):                                         │
    │     h = getHandler(name)  ← 先查 aliases 再查 handlers        │
    │     if h == null → throw IllegalArgumentException             │
    │     if !h.isAvailable(ctx) → throw IllegalStateException      │
    │     h.execute(ctx)                                            │
    └─────────────────────────────────┬───────────────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    │                                   │
          ┌─────────▼──────────┐           ┌────────────▼─────────────┐
          │ 18 个内建          │           │ ConfigurableCommandHandler │
          │ @Component         │           │  (适配 CustomCommandSpec)  │
          │ CommandHandler     │           │                            │
          │ (handlers/*)       │           │  由 CustomCommandRegistry  │
          │                    │           │  通过 registry.register()  │
          │ Spring 构造时一次  │           │  动态追加到同一 handlers Map│
          │ 性注入并 register  │           │                            │
          └────────────────────┘           └────────────────────────────┘
```

**关键事实**（每条都有源码兜底，后文会逐一展开）：

1. `CommandRegistry` 本身既是注册表**又**是分派器，源码里**没有**叫 `CommandDispatcher` 的类（`MetaCommandProcessor.process()` 直接调用 `commandRegistry.execute(name, ctx)`）。
2. 输入的"分派"发生在 `ShellUI#processInput`，通过 **3 个 `InputProcessor`** 的优先级排序实现；命令系统的"分派"则发生在 `CommandRegistry.execute`。两级分派职责分明。
3. 内建 18 个 handler 与自定义命令 handler **共存于同一个 `handlers` Map**。自定义命令不仅能新增，**还能覆盖同名内建命令**（`register()` 发现重名时仅打印 `log.warn("Command handler already registered: {}, overwriting", name)` 后直接覆盖）。

---

## 2 · 交互入口 `ShellUI` 的全部依赖

### 2.1 构造时组装的组件清单

源码：`io.leavesfly.jimi.ui.shell.ShellUI`，构造函数签名 `ShellUI(EngineClient engineClient, ApplicationContext applicationContext)`。

| 组件 | 类型 | 来源 | 作用 |
|---|---|---|---|
| `terminal` | `Terminal` | `TerminalBuilder.builder().system(true).encoding("UTF-8").build()` | JLine 终端句柄 |
| `lineReader` | `LineReader` | `LineReaderBuilder` 手工装配（见 2.2） | 读取用户一行输入 |
| `uiConfig` | `ShellUIConfig` | `engineClient.getShellUIConfig()` | 主题名、是否显示提示等 UI 配置 |
| `theme` | `ThemeConfig` | `engineClient.getThemeConfig()` | 前景/背景色映射 |
| `commandRegistry` | `CommandRegistry` | `applicationContext.getBean(CommandRegistry.class)` | 元命令注册表 |
| `notificationService` | `NotificationService` | `applicationContext.getBean(NotificationService.class)` | 通知服务（构造注入但 ShellUI 自身未显式使用） |
| `outputFormatter` | `OutputFormatter` | `new OutputFormatter(terminal, theme)` | 所有彩色打印的唯一出口 |
| `renderer` | `AssistantTextRenderer` | `new ...(terminal, theme)` | 流式助手文本渲染 |
| `spinnerManager` | `SpinnerManager` | `new ...(terminal, uiConfig)` | 等待时的旋转指示 |
| `promptBuilder` | `PromptBuilder` | `new ...(currentStatus, uiConfig, theme, engineClient)` | 构造每轮的提示符字符串 |
| `toolVisualization` | `ToolVisualization` | `new ToolVisualization()` | 工具调用的可视化 |
| `welcomeRenderer` | `WelcomeRenderer` | `new ...(terminal, outputFormatter, uiConfig, theme, engineClient)` | 欢迎页、token 统计、快捷键提示 |
| `wireMessageHandler` | `WireMessageHandler` | `new ...(outputFormatter, spinnerManager, renderer, interactionHandler, toolVisualization, uiConfig, currentStatus)` | 处理 Engine 侧 Wire 消息总线 |
| `inputProcessors` | `List<InputProcessor>` | 手工 new 3 个处理器后 `sort(comparingInt(getPriority))` | 输入分派链 |

**`notificationService` 的消费情况**：构造器内仅赋值给字段，`ShellUI` 其余方法未引用它；它应当由其他组件（例如 Hook 的 notification 动作）通过 Spring 注入使用——此处**属于注入接口但入口类未直接消费**的设计。

### 2.2 `LineReader` 的 JLine 选项集

源码 `ShellUI` 构造器中 `LineReaderBuilder` 的完整装配（逐条核对）：

| 装配项 | 值 |
|---|---|
| `terminal` | 上面创建的 `Terminal` |
| `appName` | `"Jimi"` |
| `completer` | `new JimiCompleter(commandRegistry, workingDir)` |
| `highlighter` | `new JimiHighlighter()` |
| `parser` | `new JimiParser()` |
| `option(DISABLE_EVENT_EXPANSION, true)` | 禁用 `!` 的历史事件扩展（否则与 `!<cmd>` shell 快捷冲突） |
| `option(AUTO_LIST, true)` | Tab 键自动列出候选 |
| `option(AUTO_MENU, true)` | Tab 键自动进入菜单模式 |
| `option(AUTO_MENU_LIST, true)` | 菜单与列表共存 |
| `option(INSERT_TAB, false)` | 行首 Tab 不插入字符 |
| `option(COMPLETE_IN_WORD, true)` | 在词中间触发补全 |
| `option(CASE_INSENSITIVE, true)` | 补全大小写不敏感 |

### 2.3 主循环

```java
public Mono<Boolean> run() {
    return Mono.defer(() -> {
        running.set(true);
        welcomeRenderer.printWelcome();
        while (running.get()) {
            try {
                String input = readLine();                // 阻塞
                if (input == null) { ...; break; }         // Ctrl-D (EOF)
                if (!processInput(input.trim())) break;    // processInput 返 false → 退出
            } catch (UserInterruptException e) {           // Ctrl-C
                outputFormatter.printInfo("Tip: press Ctrl-D or type 'exit' to quit");
            } catch (EndOfFileException e) { ...; break; }
            catch (Exception e) {
                log.error("Error in shell UI", e);
                outputFormatter.printError("Error: " + e.getMessage());
            }
        }
        return Mono.just(true);
    });
}
```

**退出路径**（实际生效的 3 条）：
1. `readLine()` 返回 `null` → break 循环。`ShellUI#readLine` 私有方法内部 `catch (EndOfFileException)` 后返回 `null`，**Ctrl-D 走这条**。
2. `processInput` 返回 `false` → break 循环。**只有两种输入会返回 false**：用户输入纯 `exit`/`quit`（在 `ShellUI#processInput` 里 `input.equals("exit") || input.equals("quit")` 识别），或元命令 `/exit`/`/quit`（在 `MetaCommandProcessor#process` 里 `commandName.equals("quit") || commandName.equals("exit")` 识别）。
3. `Mono<Boolean>` 订阅者调用 `stop()` → `running.set(false)` → 下轮循环退出。

**`run()` 循环体内还有一条 `catch (EndOfFileException)` 分支**，但由于 `readLine()` 私有方法已把 `EndOfFileException` 吞成 `null`，该 catch 在正常路径下**不可达**——它是防御性代码。

**Ctrl-C 不退出**，仅打印一次提示语 `"Tip: press Ctrl-D or type 'exit' to quit"`。这是因为 `ShellUI#readLine` 的 `catch (UserInterruptException e) { throw e; }` 原样上抛，由 `run()` 外层捕获后仅打印提示、不 break。

### 2.4 `subscribeWire()` 与 Wire 消息

`ShellUI` 构造末尾：

```java
wireSubscription = engineClient.subscribe()
        .publishOn(Schedulers.boundedElastic())
        .subscribe(wireMessageHandler::handle);
```

用 `boundedElastic` 是因为 `WireMessageHandler` 内部的审批流、确认对话、spinner 切换等**可能阻塞**。`close()` 里会 `wireSubscription.dispose()`。

---

## 3 · `InputProcessor` 三兄弟

接口定义（`ui/shell/input/InputProcessor.java`，共 4 个方法、1 个默认优先级 100）：

```java
public interface InputProcessor {
    boolean canProcess(String input);
    boolean process(String input, ShellContext context) throws Exception;  // 返 false → 退出 shell
    default int getPriority() { return 100; }
}
```

`ShellUI#registerInputProcessors` **硬编码**添加了 3 个：

```java
inputProcessors.add(new MetaCommandProcessor(commandRegistry));   // priority=10
inputProcessors.add(new ShellShortcutProcessor());                // priority=20
inputProcessors.add(new AgentCommandProcessor());                 // priority=100
inputProcessors.sort(Comparator.comparingInt(InputProcessor::getPriority));
```

排序规则：**数值越小越先匹配**。遍历时第一个 `canProcess()` 返回 true 的处理器**独占**该输入——这是排序的实际意义。

> **设计观察**：该列表是 `ShellUI` 内的 `new ArrayList<>()`，并非 Spring 注入。也就是说**扩展新的 `InputProcessor` 必须改 `ShellUI` 源码**——与 `CommandRegistry` 通过 `List<CommandHandler>` 自动注入的机制不一致。

### 3.1 `MetaCommandProcessor`（优先级 10）

```java
@Override public boolean canProcess(String input) { return input.startsWith("/"); }
```

`process()` 的完整流程：

1. `String commandLine = input.substring(1).trim()` — 剥离 `/` 前缀。
2. 若 `commandLine.isEmpty()` → `printError("空命令")` 返回 `true`。
3. `String[] parts = commandLine.split("\\s+", 2)` — **限制 2 份**，也就是 `cmd` 与剩余整段。
4. `commandName = parts[0]`；
   `args = parts.length > 1 ? parts[1].split("\\s+") : new String[0]` — **第二次 `split` 不限制**，空格分隔所有参数。
5. **`quit`/`exit` 特判**：若 `commandName.equals("quit") || equals("exit")`，打印 `"Bye!"` 返回 `false`（退出 shell）。
6. `commandRegistry.hasCommand(commandName)`：否 → `printError("未知命令: /" + name)` + `printInfo("输入 /help 查看可用命令")`，返回 `true`。
7. 构造 `CommandContext`（通过 Lombok `@Builder`）：

   | 字段 | 值 |
   |---|---|
   | `engineClient` | 透传 |
   | `terminal` | 透传 |
   | `lineReader` | 透传 |
   | `rawInput` | **完整原始 input**（带 `/`，保留所有空格） |
   | `commandName` | 第 4 步解析的 cmd |
   | `args` | 第 4 步解析的参数数组 |
   | `outputFormatter` | 透传 |

8. `commandRegistry.execute(commandName, cmdContext)`；任何异常都被捕获并 `printError("执行命令失败: " + e.getMessage())`，**不会让 shell 崩溃**。

**参数解析的两处典型坑**：

- `/memory write "User Preferences" 用户偏好` — 由于第 4 步是纯 `split("\\s+")`，**引号不会保留**，会被拆成 4 个 arg：`write`、`"User`、`Preferences"`、`用户偏好`。`MemoryCommandHandler#writeMemory` 实际行为是把 arg[1] 当 section（值为 `"User`），arg[2..] 用空格拼接作 content——与帮助文案 `write "User Preferences" 用户偏好使用中文回复` 存在偏差。**这是实现简化，帮助文案与真实行为不完全一致**。
- 参数里有连续多空格时，会出现空串 arg。下游 handler 需自行处理。

### 3.2 `ShellShortcutProcessor`（优先级 20）

```java
@Override public boolean canProcess(String input) { return input.startsWith("!"); }
```

`process()` 的完整流程：

1. `shellCommand = input.substring(1).trim()`；空则报错返 `true`。
2. 打印 `printInfo("执行 Shell 命令: " + shellCommand)`。
3. `engineClient.hasTool("BashTool")` 判断：否 → `printError("BashTool 工具不可用")` 返 `true`。
4. 手工拼 JSON 作为工具参数：
   ```java
   String arguments = String.format("{\"command\":\"%s\",\"timeout\":60}", jsonEscape(shellCommand));
   ```
   其中 `jsonEscape` 替换 `\`、`"`、`\n`、`\r`、`\t` 五种字符。
5. `engineClient.executeTool("BashTool", arguments).block()` —**同步阻塞**。
6. 根据 `ToolResult` 状态分三支：
   - `isOk()` → `printSuccess("命令执行成功")` + 输出内容
   - `isError()` → `printError("命令执行失败: " + message)` + 输出内容
   - 其他（REJECTED）→ `printError("命令被用户拒绝")`

**超时硬编码 60 秒**，用户无法调整。此处简化严重：任何要跑超过 60s 的 shell 命令都会被 BashTool 超时杀掉。

### 3.3 `AgentCommandProcessor`（优先级 100，兜底）

```java
@Override public boolean canProcess(String input) { return true; }  // 最低优先级 + 恒真
```

它的 `canProcess` 恒真只是形式——由于 MetaCommandProcessor（优先级 10）和 ShellShortcutProcessor（优先级 20）已先行吃掉以 `/` 和 `!` 开头的输入，轮到 AgentCommandProcessor（优先级 100）时实际看到的**只可能**是"既不以 `/` 开头也不以 `!` 开头"的输入。源码注释"默认处理器，处理所有其他输入"准确描述了这个效果。

对于未被前两个 processor 吃掉的**一切输入**（包括中文/英文自然语言、普通命令串），都走这条分支：

```java
out.printInfo("执行: " + input);
context.getEngineClient().runCommand(input).block();
out.printSuccess("✓ 完成");
```

`handleExecutionError` 对 `e.getMessage()` 做字符串 contains 判断，映射到 4 条友好提示：

| 匹配子串 | 提示 |
|---|---|
| `"LLMNotSet"` | "LLM 未配置。请设置 KIMI_API_KEY 环境变量。" |
| `"MaxStepsReached"` | "达到最大步骤数。任务可能过于复杂。" |
| `"401"` | "认证失败。请检查您的 API 密钥。" |
| `"403"` | "配额已用尽。请升级您的套餐或稍后重试。" |
| 其他 | `"错误: " + errorMsg` |

**设计观察**：通过 exception message 子串匹配来识别错误类型是**字符串耦合**——一旦上游异常文案变更，提示语就会失配。这是当前实现的一处简化。

---

## 4 · `CommandRegistry` 与 `CommandHandler` 接口

### 4.1 接口契约（`command/CommandHandler.java`）

10 个方法，其中 7 个是 `default`：

| 方法 | default 值 | 用途 |
|---|---|---|
| `String getName()` | — (抽象) | 命令名，不含 `/` |
| `String getDescription()` | — (抽象) | 一行简述 |
| `List<String> getAliases()` | `List.of()` | 别名 |
| `String getUsage()` | `"/" + getName()` | 用法 |
| `int getPriority()` | `0` | 分类内排序（大在前） |
| `String getCategory()` | `"general"` | 分类键 |
| `void execute(CommandContext)` throws Exception | — (抽象) | 执行体 |
| `boolean isAvailable(CommandContext)` | `true` | 动态启用/禁用 |

### 4.2 `CommandRegistry` 的三张表

```java
private final Map<String, CommandHandler> handlers = new ConcurrentHashMap<>();
private final Map<String, String>          aliases = new ConcurrentHashMap<>();
private final Map<String, List<CommandHandler>> categorizedHandlers = new ConcurrentHashMap<>();
```

三张表的语义：

- `handlers`：**唯一权威**的 name → handler 映射。key 全部是 `toLowerCase()`。
- `aliases`：alias(小写) → name(小写)。`getHandler(x)` 先查 aliases 再查 handlers。
- `categorizedHandlers`：category → List，每次 `register` 后按 `priority` 降序排序（`Comparator.comparingInt(CommandHandler::getPriority).reversed()`）。

### 4.3 构造与自动注册

```java
@Autowired
public CommandRegistry(List<CommandHandler> commandHandlers) {
    if (commandHandlers != null) {
        commandHandlers.forEach(this::register);
        log.info("Auto-registered {} command handlers via Spring dependency injection", handlers.size());
    }
}
```

**Spring 自动把所有 `@Component CommandHandler` 实现类**作为 List 构造参数注入。这个机制意味着：
- 新增内建命令 = 新建一个 `@Component implements CommandHandler` 的类，**零配置**即生效。
- `CustomCommandRegistry#registerCommand` 是在 Spring 启动完成后（`@PostConstruct`）调用 `commandRegistry.register(handler)`，动态补充到同一 `handlers` Map。

### 4.4 `register()` 的冲突策略

```java
public void register(CommandHandler handler) {
    String name = handler.getName().toLowerCase();
    if (handlers.containsKey(name)) {
        log.warn("Command handler already registered: {}, overwriting", name);   // ← 仅 warn，不抛异常
    }
    handlers.put(name, handler);
    for (String alias : handler.getAliases()) {
        aliases.put(alias.toLowerCase(), name);   // ← 同样覆盖，无去重检查
    }
    categorizedHandlers.computeIfAbsent(category, k -> new ArrayList<>()).add(handler);
    categorizedHandlers.get(category).sort(Comparator.comparingInt(CommandHandler::getPriority).reversed());
}
```

**关键行为**：
- 同名覆盖**只 warn 不阻止**。这意味着用户写一个名为 `help` 的自定义命令能直接覆盖内建 `HelpCommandHandler`。
- 别名冲突**连 warn 都没有**——后注册者静默覆盖。
- `categorizedHandlers` 采用 `add()` 不查重，如果 `register` 被重复调用，同一 handler 会重复进 list。（正常路径下不会触发，因为 Spring 只注入一次。）

### 4.5 `execute()` 的异常契约

```java
public void execute(String commandName, CommandContext context) throws Exception {
    CommandHandler handler = getHandler(commandName);
    if (handler == null) throw new IllegalArgumentException("Unknown command: " + commandName);
    if (!handler.isAvailable(context)) throw new IllegalStateException("Command not available: " + commandName);
    handler.execute(context);
}
```

**`IllegalArgumentException`** 仅就 `MetaCommandProcessor` 入口而言不会抛出，因为它在 `execute` 前已经 `hasCommand()` 过一次——对这条入口而言是**防御性代码**。但其他路径（如 composite 命令里的子步骤若直接 `registry.execute(...)`）则可能裸抛。

**`isAvailable`** 检查：当前所有内建 handler 都使用默认实现（恒 `true`），即**尚未启用该能力**；但接口预留了，比如依赖 Graph 的命令可以在 Graph 未初始化时返回 `false`。

---

## 5 · 18 个内建命令详表

以下表格按**源码实际声明的 `getName`/`getAliases`/`getCategory`/`getUsage`/`getDescription`** 整理，每一条都来自 `command/handlers/*.java` 的亲读。

| # | name | aliases | category | usage | description | handler 类 |
|---|---|---|---|---|---|---|
| 1 | `help` | `h`, `?` | general (默认) | `/help` | 显示帮助信息 | `HelpCommandHandler` |
| 2 | `version` | `v` | general | `/version` | 显示版本信息 | `VersionCommandHandler` |
| 3 | `status` | — | general | `/status` | 显示当前状态 | `StatusCommandHandler` |
| 4 | `config` | — | general | `/config` | 显示配置信息 | `ConfigCommandHandler` |
| 5 | `tools` | — | general | `/tools` | 显示可用工具列表 | `ToolsCommandHandler` |
| 6 | `init` | — | general | `/init` | 分析代码库并生成 AGENTS.md | `InitCommandHandler` |
| 7 | `clear` | `cls` | general | `/clear` | 清屏 | `ClearCommandHandler` |
| 8 | `reset` | — | general | `/reset` | 清除上下文历史 | `ResetCommandHandler` |
| 9 | `compact` | — | general | `/compact` | 压缩上下文 | `CompactCommandHandler` |
| 10 | `history` | — | general | `/history` | 显示命令历史 | `HistoryCommandHandler` |
| 11 | `new` | — | general | `/new` | 开启新会话 | `NewCommandHandler` |
| 12 | `theme` | — | general | `/theme [name]  - 切换主题 (default, dark, light, minimal, matrix)` | 切换UI主题 | `ThemeCommandHandler` |
| 13 | `agents` | — | general | `/agents [agent-name \| run <agent-name>]` | 管理和查看系统中的代理 | `AgentsCommandHandler` |
| 14 | `memory` | `mem` | **knowledge** | `/memory [read\|topics\|search <query>\|write <section> <content>\|clear]` | 管理项目长期记忆 | `MemoryCommandHandler` |
| 15 | `commands` | `cmds` | **system** | `/commands [list\|<name>\|reload\|enable <name>\|disable <name>]` | 管理自定义命令 | `CommandsCommandHandler` |
| 16 | `graph` | `g` | general（默认，未 override） | 见下方多行文本 | 代码图管理 | `GraphCommandHandler`（详见 08 篇） |
| 17 | `index` | — | **`上下文管理`**（中文 category key！） | `/index`（**未 override，沿用默认**） | 向量索引管理 - 支持: build/query/stats/clear | `IndexCommandHandler`（详见 08 篇） |
| 18 | `wiki` | — | **documentation** | `/wiki [init\|validate\|delete]` | 管理项目Wiki文档系统 | `WikiCommandHandler` |

**`/graph` 的 `getUsage()` 返回完整多行文本**（源码逐字抄录）：

```
/graph <subcommand> [args]
  build [path]  - 构建代码图 (默认当前目录)
  rebuild       - 重新构建代码图
  stats         - 显示图统计信息
  clear         - 清空代码图
  status        - 显示图状态
  save          - 保存代码图到磁盘
  load          - 从磁盘加载代码图
  query <type> <query> - 查询代码图
    query symbol <name> [-t type]  - 按符号名称查询
    query file <path>              - 按文件路径查询
    query callers <methodId>       - 查找方法调用者
    query callees <methodId>       - 查找方法被调用者
```

**`/index` 的 `getUsage()`** 在源码中**没有重写**，沿用 `CommandHandler` 接口默认实现 `"/" + getName() = "/index"`——这意味着 `/help` 命令如果依赖 `getUsage()` 打印帮助，`/index` 的用法说明**完全缺失**，用户只能通过阅读 `IndexCommandHandler#execute` 的分支代码或 08 篇文档才能知道可用子命令。

**未声明 `getCategory`** 的条目（1–13 以及 #16 `graph`）全部落在默认 `"general"` 分类。特别地：
- 条目 #14 `/memory` 使用 `"knowledge"`（英文）
- 条目 #15 `/commands` 使用 `"system"`（英文）
- 条目 #17 `/index` 使用 `"上下文管理"`（**中文字符串**，与其他 category key 命名风格不一致）
- 条目 #18 `/wiki` 使用 `"documentation"`（英文）

**观察**：`CommandRegistry.categorizedHandlers` 是以 category 字符串为 key 的 Map，它会原样保留中英混杂的分类名——目前没有任何消费方对 category 做聚合展示（`/commands list` 读的是自定义命令侧，不是 `CommandRegistry.categorizedHandlers`），所以这种不一致暂时没有可观察影响，但属于**代码级的命名风格分歧**。

**`/help` 的帮助文案 vs 真实实现**：`HelpCommandHandler#execute` 硬编码的帮助文本提到了 `/quit`、`/exit`、`/v` 等别名，但源码里：
- `quit`/`exit` 被 `MetaCommandProcessor` 特殊处理（直接返 false 退出），**并无对应的 `QuitCommandHandler`**。
- `/v` 是 `VersionCommandHandler.getAliases()` 声明的别名。

所以 `/quit`、`/exit` 能工作，但**不是走 handler 路径**，而是 processor 层短路。

### 5.1 `/help` 帮助文案中有、但实际不存在的命令

逐字对比 `HelpCommandHandler` 硬编码文本 vs 实际 `handlers/` 目录：

| 文案中提及 | 实际存在？ | 备注 |
|---|---|---|
| `/quit`、`/exit` | ✅（processor 层短路，非 handler） | 见上 |
| `/help, /h, /?` | ✅ | `HelpCommandHandler` |
| `/version, /v` | ✅ | `VersionCommandHandler` |
| `/status` | ✅ | — |
| `/config` | ✅ | — |
| `/tools` | ✅ | — |
| `/init` | ✅ | — |
| `/graph, /g` | ✅ | — |
| `/memory, /mem` | ✅ | — |
| `/clear, /cls` | ✅ | — |
| `/history` | ✅ | — |
| `/reset` | ✅ | — |
| `/compact` | ✅ | — |
| `/agents` | ✅ | — |
| `/commands` | ✅ | — |
| `/theme` | ✅ | — |

**文案中未提及、但实际存在的命令**：`/new`、`/wiki`、`/index`——这是**帮助文案漏收**的典型简化。用户只能通过 Tab 补全或 `/commands list` 以外的途径发现它们。

### 5.2 各内建命令 `execute` 的骨架摘要

以下仅记录**能反映外部可观察行为**的要点，具体内部对 `EngineClient` 的调用详见各自源码。

- **`/help`**：纯打印硬编码字符串表，不访问 EngineClient。
- **`/version`**：打印 `"Version: 0.1.0"` + `java.version` + `java.runtime.name`。版本号**硬编码在源码里**，未从 Maven 注入。
- **`/status`**：读 `engineClient.getAgentName()`、`getToolNames().size()`、`getContextInfo().getHistorySize()` / `getTokenCount()`。
- **`/config`**：读 `engineClient.getRuntimeInfo()`，打印 `isLlmConfigured`、`workDir`、`sessionId`、`historyFile`、`isYoloMode`。
- **`/tools`**：取 `getToolNames()`，按名字 `contains("file"|"read"|"write"|"grep"|"glob")`、`contains("bash"|"shell")`、`contains("web"|"fetch"|"search")` 归入 4 类（其他）。**完全基于工具名字符串匹配分类**，与工具本身的 category 字段无关。
- **`/init`**：`buildInitPrompt(workDir)` 通过字符串 `+` 拼接返回一段中文 prompt（含首尾 `return "..."` 的源码跨度约 4441 个字符，正文覆盖 "项目概述 / 技术架构 / 项目结构 / 技术栈 / 构建与运行 / 开发规范 / 测试策略 / 部署运维 / 关键流程 / 注意事项" 10 个维度，并在正文里**两处**动态插值 `workDir` 要求 `WriteFile` 工具写入 `<workDir>/AGENTS.md`），然后通过 `engineClient.runCommand(initPrompt).block()` 交给 Agent 跑。整个 prompt 以硬编码字符串形式存在 Java 源码中，不外化成资源文件。
- **`/clear`**：`outputFormatter.clearScreen()` 清屏。
- **`/reset`**：先 `getContextInfo().getCheckpointCount()`，为 0 则提示；否则 `engineClient.resetContext().block()`，打印 `"已回退到初始状态"`。
- **`/compact`**：**行为与文案不一致**。源码只做两件事：检查 `checkpointCount` 是否为 0；打印 `"🗃️ 正在压缩上下文..."` 和 `"✅ 上下文已压缩"`，紧跟一句 `printInfo("注意：上下文压缩将在下次 Agent 运行时自动触发")`。**没有任何实际压缩动作**——压缩是 Agent 主循环内部自动触发的，本命令只是**告诉用户**将在下次触发。这是一处**典型的"命令语义误导"**。
- **`/history`**：遍历 `lineReader.getHistory()`，对每条 `Entry#line()` 编号打印。
- **`/new`**：`engineClient.newSession().block()`，打印旧/新 session id。
- **`/theme`**：无参显示当前主题 + 5 个预设（`default` / `dark` / `light` / `minimal` / `matrix`）；有参则 `ThemeConfig.getPresetTheme(name)` 后调用 `engineClient.updateTheme(themeName, newTheme)` 与 `context.getOutputFormatter().setTheme(newTheme)`。**字符串白名单校验**（`isValidTheme` 仅返回这 5 个值），不在白名单之内直接 `printError`。提示尾部**明确写着 "部分样式将在下次输入时生效"**——真实原因：`ThemeCommandHandler#execute` **没有**调用 `ShellUI#updateTheme()`，所以 `AssistantTextRenderer` / `SpinnerManager` / `WelcomeRenderer` 仍持有旧 `ThemeConfig` 引用。`PromptBuilder` 相对特殊：它持有 `EngineClient` 与 `ThemeConfig theme` 字段，**`theme` 字段同样未被刷新**（因为 `ShellUI#updateTheme` 才会调 `promptBuilder.setTheme`），所以下次提示符构建时仍使用旧主题颜色——即文案说的"下次输入时生效"**并非自然生效**，而是**目前尚未完全生效**（仅 `outputFormatter` 用新主题打印后续消息）。
- **`/agents`**：见 5.3。
- **`/memory`**：见 10 篇（记忆管理）。
- **`/commands`**：见 6.3。
- **`/graph` / `/index`**：见 08 篇。
- **`/wiki`**：见 5.4。

### 5.3 `/agents` 三种调用形式

```java
if (argCount == 0)                   → listAllAgents()
else if (argCount == 1)              → showAgentDetails(normalize(arg0))
else if (argCount == 2 && "run".equals(arg0)) → runAgent(normalize(arg1))
else                                  → showUsageHelp()
```

`normalizeAgentName`：去空白 + 去首尾 `<>`（方便从 Wire 消息里粘来的 `<AgentName>` 直接用）。

**`runAgent` 的实现非常反直觉**：

```java
out.printWarning("⚠️  当前版本暂不支持运行时切换代理");
out.printInfo("请使用以下方式切换代理:");
out.println("  1. 退出当前会话");
out.println("  2. 使用 --agent 参数重新启动:");
out.println("     jimi --agent " + agentName);
```

也就是说 `/agents run <name>` **只校验 agent 是否存在**，然后打印一段硬编码的"请你手动重启"指引。**本命令实际不切换 agent**，这是又一处命令语义与用户期待的落差。

**`listAllAgents` 的"通用/专业"分类**完全通过一行代码判断：

```java
if ("Default Agent".equals(spec.getName())) { generalAgents.add(spec); }
else { specializedAgents.add(spec); }
```

即名字不等于 `Default Agent` 的都被算作"专业代理"——**字符串白名单而非分类字段**。

### 5.4 `/wiki` 三种子命令

`WikiCommandHandler`（`@Component`，`@Autowired(required = false) WikiValidator/WikiGenerator`）：

| 子命令 | 入口方法 | 核心动作 |
|---|---|---|
| 默认 / `init` | `executeInit` | 检测 `<workDir>/.jimi/wiki/README.md` 是否存在；不存在则 `Files.createDirectories` 后调用 `wikiGenerator.generateWiki(...).join()` |
| `validate` | `executeValidate` | 调用 `wikiValidator.validate(wikiPath)` 返回 `ValidationReport` |
| `delete` | `executeDelete` | YOLO 模式直删；否则 `lineReader.readLine("确认删除? (y/n): ")` 二次确认 |

**硬编码路径**：
- 常量 `WIKI_DIR_NAME = ".jimi/wiki"`
- 常量 `TIMESTAMP_FILE = ".wiki-timestamp"`（`init` 成功后 `Files.writeString` 当前毫秒）

**`checkWikiExists`** 的判断标准：**必须存在 `README.md`** 才算作"已初始化"（仅靠目录存在不够）。

**`delete` 确认逻辑**：`context.getEngineClient().isYoloMode()` 为 true 时跳过确认。用户需输入 `y`/`Y` 才删（`"y".equalsIgnoreCase(confirmation.trim())`），其它任意字符均取消。

---

## 6 · 自定义命令系统

### 6.1 三层加载、两级组合

Jimi 的自定义命令完全复用 07 篇介绍过的 `YamlConfigLoader` 三层加载：

```
classpath:commands/*.yaml   →  ~/.jimi/commands/*.yaml  →  <workDir>/.jimi/commands/*.yaml
      （内置示例，JAR 环境跳过）   （用户级）              （项目级，可选）
```

源码 `CustomCommandLoader#loadAllCommands`：

```java
List<CustomCommandSpec> loaded = yamlConfigLoader.loadAll(
        "commands", CustomCommandSpec.class, projectDir);
```

`YamlConfigLoader.loadAll` 的三层合并顺序固定为 **classpath → user → project**，`loadAll` 返回的 List 就是 append 顺序：先 classpath，再 user，最后 project。`CustomCommandRegistry#loadAndRegisterCommands` 对 List 逐个 `registerCommand(spec)`，而 `registerCommand` 在发现 `commandSpecs.containsKey(name)` 时会先 `unregisterCommand(name)` 再 `put`——**后注册者覆盖先注册者**。最终效果：**项目级命令覆盖用户级，用户级覆盖内置示例**，与 07 篇 Hook 加载一致。

**启动流程中的两阶段加载**：
1. Spring 初始化阶段（`@PostConstruct` 的 `CustomCommandRegistry#initialize`）：**只加载 classpath + user 两层**，因为此时 `projectDirectory == null`。
2. `JimiFactory.createEngine(...)` 组装 JimiEngine 之后：显式调用 `customCommandRegistry.setProjectDirectory(jimiRuntime.getWorkDir())`，`setProjectDirectory` 内部 `reloadCommands` 会**先 `unregisterCommand` 清空所有旧 spec**（包括 classpath 和 user 那两层）再重新三层加载——因此项目级命令此时才真正生效。

即用户在**启动前期**和**启动完成之后**`/commands list` 可能看到**不同数量**的命令——虽然启动脚本是同步流程，用户几乎感知不到差异，但在 JimiFactory 因异常提前返回的情况下，项目级命令可能不生效。`JimiFactory` 对该调用用 `try { ... } catch (Exception e) { log.warn("Failed to load project custom commands: {}", e.getMessage()); }` 包住，**即使项目级加载失败也不会阻断启动**。

**classpath 加载的 JAR 规避**（`YamlConfigLoader.loadFromClasspath`）：

```java
if (resource.getProtocol().equals("jar")) {
    log.debug("Running from JAR, skipping classpath {}", subDir);
    return Collections.emptyList();
}
```

这意味着 **`jimi.jar` 发布后**运行时，内置的 `commands/git-commit.yaml` 等三个示例**不会**从 classpath 加载——只在 IDE / 源码运行时生效。发布版本里用户必须把示例拷到 `~/.jimi/commands/` 才能使用。**这是 07 篇已经揭示过的同一处简化**（Hook 同样受影响）。

### 6.2 内置 3 个示例命令（`src/main/resources/commands/`）

直接源于仓库 `src/main/resources/commands/` 下的 3 个 YAML（逐字核对）：

| 文件 | name | aliases | category | 执行类型 | 核心动作 |
|---|---|---|---|---|---|
| `git-commit.yaml` | `git-commit` | `gc` | `git` | `composite` | 2 步：`git add .` → `git commit -m "${MESSAGE}"` |
| `quick-review.yaml` | `quick-review` | `qr` | `code` | `prompt`（对齐 Claude Code 标准） | 把模板里的 `$ARGUMENTS` 替换后 `engineClient.runCommand(...)` 交给 Agent |
| `format.yaml` | `format` | `fmt` | `build` | `script` | 执行 `mvn spotless:apply`，timeout=120s，workingDir=`${JIMI_WORK_DIR}` |

**三个示例分别覆盖 `composite` / `prompt` / `script` 三种执行类型**，剩下的 `agent` 类型没有内置示例。

**`git-commit.yaml` 的 preconditions**：
- `dir_exists: .git` （不是 Git 仓库则报错 "当前目录不是 Git 仓库"）
- `command_exists: git`

**`format.yaml` 的 preconditions**：
- `file_exists: pom.xml`
- `command_exists: mvn`

### 6.3 `CustomCommandSpec` 字段全量清单

源码 `command/custom/CustomCommandSpec.java` 的全部 Lombok `@Data @Builder` 字段：

| 字段 | 类型 | 默认值 | 必需性 | 说明 |
|---|---|---|---|---|
| `name` | `String` | — | **必需**（`validate()` 空检查） | 命令名，不含 `/` |
| `description` | `String` | — | **必需** | 一行描述 |
| `category` | `String` | `"custom"` | 可选 | 自定义命令默认 category，区别于内建默认 `"general"` |
| `priority` | `int` | `0` | 可选 | 同分类排序 |
| `aliases` | `List<String>` | `[]` | 可选 | 别名 |
| `usage` | `String` | `null` → `"/" + name` | 可选 | 用法 |
| `parameters` | `List<ParameterSpec>` | `[]` | 可选 | 参数定义 |
| `execution` | `ExecutionSpec` | — | **与 `prompt` 二选一必需** | `script` / `agent` / `composite` |
| `prompt` | `String` | `null` | **与 `execution` 二选一必需** | Prompt 模板（对齐 Claude Code） |
| `preconditions` | `List<PreconditionSpec>` | `[]` | 可选 | 前置条件（4 种 type） |
| `requireApproval` | `boolean` | `false` | 可选 | **声明了但未消费**（见 §8） |
| `enabled` | `boolean` | `true` | 可选 | 启用开关 |
| `configFilePath` | `String` | `null` | 运行时 | 配置文件路径（运行时设置） |

**`validate()` 规则**（源码逐字）：
- `name` 空 → `IllegalArgumentException("Command name is required")`
- `description` 空 → `IllegalArgumentException("Command description is required for: " + name)`
- `execution` 与 `prompt` **均空** → `IllegalArgumentException("Either 'execution' or 'prompt' is required for command: " + name)`
- `execution` 非空时递归 `execution.validate()`
- `parameters` / `preconditions` 非空时逐个 `validate()`

**`isPromptType()`**：`prompt != null && !prompt.trim().isEmpty()`——只要声明了非空 prompt，**即使同时声明了 execution，execute 时也会走 prompt 分支**（见 §6.5）。

**`getExecutionTypeName()`**：`isPromptType()` 返 `"prompt"`；否则返 `execution.getType()`；均无则返 `"unknown"`。

### 6.4 `ParameterSpec` / `PreconditionSpec` / `CompositeStepSpec` 字段

**`ParameterSpec`**（4 种 type）：

| 字段 | 默认 | 说明 |
|---|---|---|
| `name` | — | 参数名，必需 |
| `type` | `"string"` | 允许值：`string` / `boolean` / `integer` / `path`，其它直接 `IllegalArgumentException` |
| `defaultValue` | `null` | 默认值 |
| `required` | `false` | 是否必填 |
| `description` | `null` | 描述 |

`toEnvironmentVariableName()`：`name.toUpperCase().replace('-', '_')` ——例如 `skip-tests` → `SKIP_TESTS`，写入 script 的环境变量。

**`PreconditionSpec`**（4 种 type）：

| type | 必需字段 | 行为 |
|---|---|---|
| `file_exists` | `path` | `Files.exists(Paths.get(resolvedPath))` |
| `dir_exists` | `path` | `Files.isDirectory(Paths.get(resolvedPath))` |
| `env_var` | `var`（可选 `value`） | 环境变量存在；若声明 `value` 则还要值相等 |
| `command_exists` | `command` | `new ProcessBuilder("which", command).start()` 5 秒超时 + `exitValue()==0` |

所有 `path` 字段都经过 `resolveVariables` 替换 `${JIMI_WORK_DIR}` / `${PROJECT_ROOT}` / `${HOME}`。

**`CompositeStepSpec`** 存在一个"字段但无代码路径消费"的事实：源码里 `CompositeStepSpec` 本身定义了 `command` 字段（`type: "command"` 时用）和 `step.type == "command"` 的校验分支，**但 `ConfigurableCommandHandler#executeComposite` 只把每步当 `script` 执行**（见下一节），也就是说 **composite 的 `type: "command"` 目前没有运行时路径**。另外，真正被 `ExecutionSpec.ExecutionStep` 消费的字段是**嵌套静态类** `ExecutionSpec.ExecutionStep`（不同于 `CompositeStepSpec`），两者**都定义在仓库中且功能重叠**。下文 §8 会列为落差。

### 6.5 `ConfigurableCommandHandler#execute` 四分支

源码 `command/custom/ConfigurableCommandHandler.java`（核心分派逻辑）：

```java
public void execute(CommandContext context) throws Exception {
    if (!checkPreconditions(context)) return;            // 1) 前置条件
    Map<String, String> parameterValues = resolveParameters(context);  // 2) 参数解析
    if (spec.isPromptType()) {                           // 3) prompt 优先
        executePrompt(context, parameterValues);
    } else {
        ExecutionSpec execution = spec.getExecution();
        switch (execution.getType()) {
            case "script"    -> executeScript(execution, context, parameterValues);
            case "agent"     -> executeAgent(execution, context, parameterValues);
            case "composite" -> executeComposite(execution, context, parameterValues);
            default          -> out.printError("Unknown execution type: " + execution.getType());
        }
    }
}
```

**关键细节逐分支列出**：

**(a) 前置条件 `checkPreconditions`**：任一前置条件不满足即 `printError(precondition.getErrorMessage())`（或默认 `"Precondition failed: " + type"`）并 `return`（即 `execute` 方法直接返回，**不抛异常**）。

**(b) 参数解析 `resolveParameters`**：
1. 按 `spec.getParameters()` 的声明顺序，从 `context.getArg(i)` 按位置取值。
2. 空值 fallback 到 `ParameterSpec.defaultValue`。
3. `required=true` 且值仍空 → `printError("Required parameter missing: " + name)`，**立即 `return parameterValues`**。由于 `resolveParameters` 的返回类型是 `Map<String, String>`（而非 `Optional` 或抛异常），`execute` 主干拿到返回值后**无法区分**它是"正常完成"还是"中途因缺参返回"，因此会继续把这个**不完整的 parameterValues** 塞给后续的 `executeScript` / `executePrompt` / `executeAgent`。脚本里的 `${MESSAGE}` 等占位符将无法在 shell 层展开（对应环境变量不存在）。这是一处典型的简化，见 §8。
4. 参数名通过 `toEnvironmentVariableName` 转大写+下划线，作为 Map key。
5. **始终**额外塞一个 `ARGUMENTS` key：`parameterValues.put("ARGUMENTS", context.getArgsAsString())`——对齐 Claude Code 标准的 `$ARGUMENTS` 占位符。

**(c) Prompt 分支 `executePrompt`**：
1. 取 `spec.getPrompt()`。
2. `String resolvedPrompt = promptContent.replace("$ARGUMENTS", arguments)` — **无 `${...}` 包裹的 `$ARGUMENTS` 首先被整体替换**。
3. 遍历 `parameterValues` 每对 `key → value`，把 `${KEY}` 格式替换为 value。
4. `engineClient.runCommand(resolvedPrompt).block()` 交给 Agent 执行。
   
   注意：`$ARGUMENTS` 与 `${ARGUMENTS}` 语法都能用——前者是字面量 replace，后者由于第 3 步的 `${ARGUMENTS}` 也在 `parameterValues` 里，会被第二次替换成同一个值。

**(d) Script 分支 `executeScript`**（重点，最复杂）：
1. 选内联 `execution.getScript()` 或外部 `execution.getScriptFile()`（后者优先，用 `Files.readString` 读取）。
2. `resolveVariables(scriptContent, workDir)` 替换 `${JIMI_WORK_DIR}` / `${PROJECT_ROOT}` / `${HOME}` 三个内置变量。
   
   **陷阱**：这一步**不会**替换参数变量 `${MESSAGE}` 等——参数变量是通过**环境变量**传给子进程的，不会在脚本正文里替换。换句话说 `git-commit.yaml` 里 `git commit -m "${MESSAGE}"` 依赖的是 shell 展开环境变量，而非 Java 侧字符串替换。
3. `ProcessBuilder("/bin/sh", "-c", resolvedScript)` 启动子进程。
4. 工作目录：`execution.getWorkingDir()` 非空时 `resolveVariables` 后使用；否则 `workDir.toFile()`。
5. 环境变量：先 `JIMI_WORK_DIR` / `PROJECT_ROOT` 两个内置键，再 `putAll(parameterValues)`，最后 `putAll(execution.getEnvironment())`——后者覆盖前者。
6. `redirectErrorStream(true)` 把 stderr 合并到 stdout，边读边打印到 `outputFormatter`。
7. `process.waitFor(execution.getTimeout(), TimeUnit.SECONDS)`：超时 → `destroyForcibly` + `printWarning`；非零退出 → `printError("Script exited with code: ...")`；0 → `printSuccess("Script completed successfully")`。

**(e) Agent 分支 `executeAgent`**：
1. `resolveVariables(execution.getTask(), workDir)` 替换 3 个内置变量。
2. 对每个 `parameterValues` 条目，把 `${KEY}` 替换为 value。
3. `printInfo("Delegating to agent: " + execution.getAgent())` 打印目标 agent 名。
4. `engineClient.runCommand(taskDescription).block()`。

**关键观察**：`executeAgent` **只使用 task 文本**，`execution.getAgent()` 字段**只在 `printInfo` 里出现，从不参与 agent 切换**——即"把任务委托给 agent X"这个语义**实际落空**，所有 `type: "agent"` 命令都只是把 task 字符串当作自然语言输入喂给**当前 agent**。这与 `/agents run <name>` 的"暂不支持运行时切换代理"是同一处落差。

**(f) Composite 分支 `executeComposite`**（再次注意）：

```java
for (int i = 0; i < steps.size(); i++) {
    ExecutionSpec.ExecutionStep step = steps.get(i);
    ...
    try {
        ExecutionSpec stepExecution = ExecutionSpec.builder()
                .type(step.getType())
                .script(step.getScript())
                .timeout(step.getTimeout())
                .build();
        executeScript(stepExecution, context, parameterValues);   // ← 全部当 script 执行
    } catch (Exception e) { ...; if (!step.isContinueOnFailure()) return; }
}
```

**重要事实**：
- 源码里**所有 step 都走 `executeScript`**，不管 `step.type` 是 `"script"` 还是 `"command"`。`CompositeStepSpec` 里的 `command` 字段和 `CompositeStepSpec.validate()` 里对 `"command"` 类型的校验，在主执行路径**没有实际效果**。
- step 级 `continueOnFailure` 默认 `false`，失败则整体 return；为 true 时仅 `printWarning("  Continuing despite failure...")` 后跳到下一步。
- 每步输出前缀 `[i/total] description`。

### 6.6 `/commands` 子命令管理

源码 `CommandsCommandHandler#execute` 的完整分派（核对）：

```java
if (argCount == 0) → listAllCommands();
switch (subCommand) {
    case "list"    → listAllCommands();
    case "reload"  → reloadCommands(context);
    case "enable"  → 需 argCount ≥ 2，否则报错
    case "disable" → 需 argCount ≥ 2，否则报错
    default        → showCommandDetails(subCommand);   // ← 其它任意字符串均视为查看命令详情
}
```

即 `/commands foo` 不报错"未知子命令"，而是按 `foo` 当命令名去 `CustomCommandRegistry.getCommandSpec("foo")` 查详情，查不到才 `printError("未找到自定义命令: foo")`。

**`reloadCommands`**：调 `customCommandRegistry.reloadCommands(engineClient.getWorkDir())`，内部先 `unregisterCommand` 所有旧 spec，再 `loadAndRegisterCommands` 重新加载——**项目目录会被更新到 `CustomCommandRegistry.projectDirectory` 字段**。

**`enable` / `disable`**：
- `enableCommand(name)`：设 `spec.setEnabled(true)`，若该 spec 此前因 disabled 未注册 handler，则新建 `ConfigurableCommandHandler` 并 `commandRegistry.register(handler)`。
- `disableCommand(name)`：设 `spec.setEnabled(false)`，并 `commandRegistry.unregister(name)` 从全局注册表中移除。

即 **disable 的命令其 spec 仍保留在 `CustomCommandRegistry.commandSpecs` 里**，只是不在 `CommandRegistry.handlers` 里——`/commands list` 仍会把它显示出来（带 ❌ 图标）。

**`showCommandDetails`** 的显示逻辑按 `ExecutionTypeName` 分支：
- `prompt`：显示前 80 字符（长则 `77 + "..."`）
- `script`：优先显示 `scriptFile`，否则显示 `script` 前 50 字符
- `agent`：显示 `Agent:` 与 `任务:`
- `composite`：仅显示 `步骤数:`

**一个要点**：`listAllCommands` 读 `customCommandRegistry.getAllCustomCommands()`，**只显示自定义命令、不显示内建命令**。因此 `/commands list` 看到的清单与 `CommandRegistry` 注册的完整清单**不是同一回事**——内建命令要通过 `/help` 或 Tab 补全发现。

**`@Lazy @Autowired` 的必要性**：`CommandsCommandHandler` 本身是一个 `@Component CommandHandler`，会被 `CommandRegistry` 构造器里 `List<CommandHandler>` 自动注入；而 `CustomCommandRegistry` 依赖 `CommandRegistry`。如果 `CommandsCommandHandler` 直接 `@Autowired CustomCommandRegistry`，Spring 将检测到 `CommandRegistry → CommandsCommandHandler → CustomCommandRegistry → CommandRegistry` 的循环依赖。通过 `@Lazy` 把 `CustomCommandRegistry` 的注入延迟到首次实际访问，避免了循环初始化。

### 6.7 启动时自动创建用户目录

`CustomCommandRegistry#initialize` 里在 `@PostConstruct` 阶段会调用：

```java
commandLoader.ensureUserCommandsDirectory();
loadAndRegisterCommands(null);
```

`ensureUserCommandsDirectory` 委托 `YamlConfigLoader#ensureUserDirectory("commands")`，内部 `Files.createDirectories(Paths.get(System.getProperty("user.home"), ".jimi", "commands"))`——这意味着**首次启动 Jimi 时会自动创建 `~/.jimi/commands/`**（如果尚不存在）。创建失败仅 `log.error` 不抛异常。

---

## 7 · JLine 交互层与渲染器细节

本章补全 §2 已点名的辅助组件。**所有组件都在 `ShellUI` 构造时 `new` 出来，不走 Spring**。

### 7.1 `JimiParser`

源码 `ui/shell/jline/JimiParser.java`：

```java
public JimiParser() {
    this.defaultParser = new DefaultParser();
    this.defaultParser.setEscapeChars(null);   // 禁用转义字符处理
}
@Override public boolean isEscapeChar(char ch)     { return false; }
@Override public boolean validCommandName(String n) { return true; }
@Override public boolean validVariableName(String n){ return true; }
@Override public ParsedLine parse(...) { return defaultParser.parse(...); }
```

**关键事实**：`JimiParser` 只是 `DefaultParser` 的薄包装，**禁用了反斜杠转义**。它的 `parse` 方法在 `lineReader.readLine` 过程中被 JLine 用来切词、光标定位、触发补全——**但 `MetaCommandProcessor` 并不消费 `ParsedLine`**，而是对**原始字符串**用正则 split。因此 Parser 的切词结果实际只服务于 `JimiCompleter` 的 `line.word()` / `line.wordIndex()`。

### 7.2 `JimiHighlighter`

源码 `ui/shell/jline/JimiHighlighter.java`：

```java
private static final Pattern META_COMMAND_PATTERN = Pattern.compile("^/\\w+");

@Override public AttributedString highlight(LineReader reader, String buffer) {
    if (META_COMMAND_PATTERN.matcher(buffer).find()) {
        // 蓝色加粗
        AttributedStyle style = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE).bold();
        builder.styled(style, buffer);
    } else {
        builder.append(buffer);
    }
    return builder.toAttributedString();
}
```

**行为**：只要 buffer 以 `/\w+` 开头，**整行**变蓝色加粗；否则无样式。颜色**硬编码为 `AttributedStyle.BLUE`**，**不随 `ThemeConfig` 变化**——这是一处与主题系统的脱耦（`ThemeCommandHandler` 切主题后元命令颜色不变）。

### 7.3 `JimiCompleter` 的四条补全路径

源码 `ui/shell/jline/JimiCompleter.java#complete`：

```java
if (word.startsWith("/"))  →  completeMetaCommands(word, candidates);    return;
if (word.startsWith("@"))  →  completeFilePaths(word, candidates);       return;
if (shouldCompletePhrase(line, word))  →  completeCommonPhrases(word, candidates);
if (shouldSuggestFiles(fullLine, word)) →  suggestFileTypes(word, candidates);
```

**路径 1 — 元命令补全**：遍历 `commandRegistry.getAllHandlers()`，对每个 handler 做 2 件事：
- 若 `name.toLowerCase().startsWith(prefix)`：加候选 `/<name>`，description 形如 `"命令描述 (alias1, alias2)"`。
- 遍历每个 alias，若 `alias.toLowerCase().startsWith(prefix)`：加候选 `/<alias>`（但 Candidate 的 `value` 仍是主命令 `/<name>`——即**光标上显示别名、按 Tab 插入主命令**），description 形如 `"命令描述 (alias for /name)"`。

**路径 2 — 文件路径补全**（`@` 前缀）：
1. 去掉 `@`，按 `File.separator` 拆出目录基路径 + 文件前缀。
2. `basePath = workingDir.resolve(dirPath)`；不存在或非目录直接返回。
3. `Files.list(basePath)` → `filter(!shouldIgnore)` → `filter(startsWith(fragment))` → `limit(50)` → **目录优先+字典序**排序 → 最多 50 个 Candidate。
4. 候选 `value` 格式为 `@<workDir 相对路径>` 或 `@<...>/`（目录）。`complete` 标志：文件 true（补完空格），目录 false（方便继续输入子路径）。

**`shouldIgnore` 黑名单**（`IGNORED_DIRS` 常量，共 17 项）：
```
.git, .svn, .hg, .bzr,
node_modules, .gradle, .maven,
target, build, out, bin,
.idea, .vscode, .eclipse,
__pycache__, .pytest_cache,
.DS_Store, Thumbs.db
```

**`shouldIgnore` 的三段判定逻辑**（源码 `JimiCompleter.java#295-303`）：

```java
if (fileName.startsWith(".") && !fileName.equals(".") && !fileName.equals("..")) {
    return IGNORED_DIRS.contains(fileName);    // 分支 A：隐藏项
}
if (Files.isDirectory(path)) {
    return IGNORED_DIRS.contains(fileName);    // 分支 B：目录
}
return false;                                   // 分支 C：普通文件一律放行
```

这导致一个**实际生效/不生效不对称**的结果：
- `.git`/`.idea` 等以 `.` 开头的目录会在分支 A 命中黑名单 → 被过滤 ✅
- `target`/`node_modules`/`build` 等普通目录会在分支 B 命中黑名单 → 被过滤 ✅
- `.DS_Store`（以 `.` 开头的**文件**）在分支 A 命中黑名单 → 被过滤 ✅
- **`Thumbs.db`（既不以 `.` 开头也不是目录）永远走到分支 C → `return false` → 会被展示**，尽管它在黑名单里

即 `Thumbs.db` 这一项实际上是**黑名单里形同虚设的条目**。此外 `shouldIgnore` 对**所有以 `.` 开头但不在 IGNORED_DIRS 的文件/目录**会**默认展示**（比如 `.jimi/`、`.env` 会出现在候选里）。

**路径 3 — 常用短语补全**：在 `shouldCompletePhrase` 为 true 时触发——条件是 `wordIndex == 0`（行首）或 `line.trim().split("\\s+").length <= 2`（≤2 个 token）。候选来自 15 个硬编码短语：`help me, show me, explain, what is, how to, please, analyze, fix, refactor, implement, create, update, delete, find, search`。

**路径 4 — 文件类型建议**：`shouldSuggestFiles` 条件——`fullLine` 包含 `file`/`read`/`write`/`open` 或 `word` 以 `.` 开头。候选来自 8 个硬编码扩展名的 `Map`：`.java, .py, .js, .ts, .md, .yaml, .json, .xml`。

**观察**：路径 3 和路径 4 不做 return，可能**同时触发**——例如输入 `read .` 会同时进入 "文件类型" 建议。

### 7.4 `PromptBuilder` 的三种风格

源码 `ui/shell/style/PromptBuilder.java#build`：

```java
return switch (uiConfig.getPromptStyle()) {
    case "simple" -> buildSimple();   //  <icon> jimi> 
    case "rich"   -> buildRich();     //  [HH:mm:ss] <icon> jimi[📦状态] [💬N 💡Ktokens]> 
    default       -> buildNormal();   //  <icon> jimi[📦]> 
};
```

**状态到图标的硬编码映射**（`getIconForStatus`）：

| status | icon |
|---|---|
| 以 `"thinking"` 开头 | `🧠` |
| `"compacting"` | `🗂️` |
| `"interrupted"` | `⚠️` |
| `"error"` | `❌` |
| 其他（默认 `"ready"`） | `✨` |

**状态到颜色的映射**（`getStyleForStatus`，取自 `theme`）：

| status | 颜色字段 | 是否 bold |
|---|---|---|
| `"thinking*"` | `theme.getThinkingColor()` | `theme.isBoldPrompt()` |
| `"compacting"` | `theme.getStatusColor()` | 否 |
| `"interrupted"` / `"error"` | `theme.getErrorColor()` | 否 |
| 其他 | `theme.getPromptColor()` | `theme.isBoldPrompt()` |

**rich 风格额外两个条件特性**：
- `uiConfig.isShowTimeInPrompt()` 为 true → 前缀 `[HH:mm:ss]`。
- `uiConfig.isShowContextStats()` 为 true → 后缀 `[💬<消息数>💡<tokenK>]`，其中 token ≥ 1000 时显示 `"%.1fK"`。这里 `engineClient.getHistorySize()` / `getTokenCount()` 会在**每次提示符构建时**被调用——因此如果 token 统计接口有性能问题会直接影响 REPL 响应。

**`currentStatus` 的写入方**：`WireMessageHandler` 的 `handleStepBegin`（写 `"thinking (step N)"`）、`handleStepInterrupted`（写 `"interrupted"`）、`handleCompactionBegin`/`End`（写 `"compacting"` / `"ready"`）、`handleStatusUpdate`（按 Wire 消息写入）。**`currentStatus` 共享到 `PromptBuilder`——这是提示符能"跟随 Agent 状态变色"的技术机制**。

### 7.5 `AssistantTextRenderer` 的流式输出

`handleContentPart` 把 `TextPart.getText()` 经 `AssistantTextRenderer.print(text, isReasoning)` 输出。核心行为：

1. **首次输出换行**：`outputStarted.getAndSet(true)` 首次为 false → 先 `println()` 换到新行，随后输出。
2. **推理/正式模式切换**：当 `isReasoning` 与 `isInReasoningMode` 不一致时，先换行，然后打印一行标签：
   - 推理：`"💡 [思考过程]"`（**硬编码青色斜体**）
   - 正式：`"✅ [正式回答]"`（**硬编码绿色加粗**）
   
   这两个标签的颜色**同样不走 `ThemeConfig`**，是 `AttributedStyle.CYAN`/`GREEN` 直写——与 JimiHighlighter 属于同一种"脱耦主题"的简化。
3. **按字符 flush 逐字渲染**，遇 `\n` 或超过 `terminalWidth - 4` 即自动换行。
4. **中文字符宽度**：`isChineseChar(ch) = (ch >= 0x4E00 && ch <= 0x9FA5)`——**仅覆盖 CJK 统一汉字 BMP 基本区间**（20902 个字符），不覆盖扩展 A/B/...（如生僻字）、CJK 符号标点（0x3000–0x303F）、全角符号（0xFF00–0xFFEF）、emoji。这些字符会被按 1 个宽度计算，**可能导致换行过迟**（表现为输出行宽超过终端实际列数）。
5. `flushLineIfNeeded()`：外部组件（如 `InteractionHandler.handleApprovalRequest`）在弹审批框前调用，强制换行结束当前未完的助手输出。

### 7.6 `WireMessageHandler` 的消息类型分派

源码逐字核对 `WireMessageHandler#handle` 的 `instanceof` 链：

| Wire 消息类型 | 处理方法 |
|---|---|
| `StepBegin` | `handleStepBegin` — `printStatus("🧠 Step N - Thinking...")` + `spinnerManager.start("正在思考...")` + 首步触发 `shortcutsHintCallback("thinking")` |
| `StepInterrupted` | `handleStepInterrupted` — 设 status=interrupted + `printError("⚠️ Step interrupted")` + 清 activeTools + 触发 `shortcutsHintCallback("error")` |
| `CompactionBegin` | `handleCompactionBegin` — 设 status=compacting + `printStatus("🗜️  Compacting context...")` |
| `CompactionEnd` | `handleCompactionEnd` — 设 status=ready + `printSuccess("✅ Context compacted")` |
| `StatusUpdate` | 从 `status.get("status")` 读字符串写入 `currentStatus` |
| `ContentPartMessage` | 停 spinner + 如果是 `TextPart`，按 `ContentType == REASONING` 标志调 `renderer.print` |
| `ToolCallMessage` | 停 spinner + `renderer.flushLineIfNeeded()` + 根据 `uiConfig.getToolDisplayMode()` 分三支显示 |
| `ToolResultMessage` | 按 displayMode 分三支显示成功/失败 |
| `TokenUsageMessage` | 回调 `tokenUsageCallback` → `WelcomeRenderer.showTokenUsage` |
| `ApprovalRequest` | 转交 `interactionHandler.handleApprovalRequest` |
| `HumanInputRequest` | 转交 `interactionHandler.handleHumanInputRequest` |
| 其他 | `log.debug("Unhandled wire message type: ...")` |

**`toolDisplayMode` 的 3 种模式**（`uiConfig.getToolDisplayMode()`）：
- `"minimal"`：仅 `🔧 <toolName>` / `✅ <toolName>` / `❌ <toolName>`
- `"compact"`：`🔧 <toolName> | <args 截断到 uiConfig.getToolArgsTruncateLength()>` / `→ ✅ 成功` 或 `→ ❌ 失败: <msg>`
- 其他（默认）：委托 `ToolVisualization.onToolCallStart/Complete`（富格式）

### 7.7 `InteractionHandler` 的两类阻塞式交互

`ApprovalRequest` 与 `HumanInputRequest` 都在 **Wire 订阅线程**（`boundedElastic` 调度器）上同步阻塞调用 `lineReader.readLine(prompt)`——这是 `ShellUI#subscribeWire` 专门选择 `boundedElastic` 的原因。

**审批流 `handleApprovalRequest`**：
1. 打印操作类型/描述。
2. 提示符 `"❓ 是否批准？[y/n/a] (y=批准, n=拒绝, a=本次会话全部批准): "`（黄色加粗，**硬编码 YELLOW**，不走 theme）。
3. **最多重试 3 次**读空输入——空输入不会拒绝，而是重读。
4. 最终输入 `y`/`yes` → `APPROVE`；`a`/`all` → `APPROVE_FOR_SESSION`；`n`/`no` → `REJECT`；**其他任意输入**（包括空）→ `REJECT`（带 `log.info("Unrecognized input '{}', treating as reject")`）。
5. `UserInterruptException`（Ctrl-C）→ `"❌ 审批已取消"` + `REJECT`。

**人机交互 `handleHumanInputRequest`** —— `HumanInputRequest.InputType` 枚举定义了 3 个值：`CONFIRM` / `FREE_INPUT` / `CHOICE`（见 `tool/core/ask/HumanInputRequest.java` 第 60–73 行）。`InteractionHandler` 的 switch 分支逐一处理：

| InputType | 提示符颜色 | 行为 |
|---|---|---|
| `CONFIRM` | **YELLOW bold**（y/m/n 主问）；**CYAN**（修改意见二次提问） | 读一行；`y/yes/满意` → `approved()`；`m/modify/修改` → 再读一行 `"📝 请输入修改意见: "` → `needsModification(mod)`；其他 → `rejected()` |
| `FREE_INPUT` | **CYAN** | 读一行；空且有 `defaultValue` 时用默认值（否则原样保留空字符串）；返回 `inputProvided(input)` |
| `CHOICE` | **CYAN** | 打印 `"请从以下选项中选择:"` + 序号枚举 → 读序号 → `Integer.parseInt` 成功且 `0 ≤ idx < choices.size()` → `inputProvided(choices[idx])`；否则 / `NumberFormatException` → `rejected()`；`choices` 为 null 或空 → `printError("❌ 没有可用的选项") + rejected()` |

此外 switch 还有一个 `default` 分支：`printError("❌ 未知的输入类型") + rejected()`——但由于 `InputType` 是**封闭枚举只有 3 个值**，这个 default 在正常调用路径中**永远不会命中**（除非未来新增枚举值而 InteractionHandler 未同步更新）。属于防御性代码，非真实第四种类型。

### 7.8 `WelcomeRenderer` 的启动时展示

`printWelcome()` 依次做：
1. 空行。
2. `printBanner()` — 打印一段 `String.format` 的带框 ASCII 横幅，其中 `version` 来自 `getVersionInfo()`：先试 `getClass().getPackage().getImplementationVersion()`（从 JAR Manifest 读），**失败 fallback 到 `"v0.1.0"`**。`javaMajorVersion` = `java.version.split("\\.")[0]`。
3. `printSuccess("Welcome to Jimi ")`。
4. `printInfo("Type /help for available commands, or just start chatting!")`。
5. `showShortcutsHint("welcome")`。

**值得一提的不一致**：`VersionCommandHandler` 打印的版本是**硬编码字符串 `"0.1.0"`**；而 `WelcomeRenderer.printBanner` 的版本**优先从 Manifest 读**，只在读不到时 fallback 到 `"v0.1.0"`。JAR 打包后两者可能显示**不同版本**（前者永远 0.1.0，后者可能是 Maven 真实版本）——这是一处可见的简化。

**`showShortcutsHint` 频率控制**（`uiConfig.getShortcutsHintFrequency()`）：
- `"first_time"`：每个 hintType 只显示一次（`welcomeHintShown`/`inputHintShown`/`thinkingHintShown` 三个 `AtomicBoolean`）。
- `"periodic"`：`interactionCount % uiConfig.getShortcutsHintInterval() == 0` 时显示。
- 其他（默认 `"always"`）：每次都显示。

仅支持两种 hint 文案（`getHintForType`）：
- `"error"` → `"💡 提示: /reset 清空上下文 | /status 查看状态 | /history 查看历史"`
- `"approval"` → `"💡 快捷键: y (批准) | n (拒绝) | a (全部批准)"`

其他 hintType（`welcome`/`input`/`thinking`）**虽然在代码中有频率判断和 compareAndSet 状态**，但 `getHintForType` 对它们返回 `null`——即 `printWelcome()` 里 `showShortcutsHint("welcome")` 实际**什么都不打印**。这是一处**预留但未实装**的功能点。

### 7.9 `SpinnerManager` 动画

`start(message)` 启动一个 daemon 线程，按 `uiConfig.getSpinnerIntervalMs()` 毫秒切换一帧。三种动画类型（`uiConfig.getSpinnerType()`）：

| type | 帧序列 |
|---|---|
| `"arrows"` | `← ↖ ↑ ↗ → ↘ ↓ ↙` |
| `"circles"` | `◐ ◓ ◑ ◒` |
| 默认（`"dots"` 及其它） | `⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏` |

**`stop()` 的同步方式**：`spinnerThread.join(500)` 最多等 500ms，超时就强制 null。因此 `WireMessageHandler.handleContentPart` 调 `spinnerManager.stop()` 后**可能有最长 500ms 的延迟**。

`start` 的幂等保护：`if (spinnerThread != null && spinnerThread.isAlive()) return;` ——已有线程在跑时 `start` 是 no-op，仅 `spinnerMessage.set(...)` 不生效。**这是一处可能的交互问题**：Thinking 阶段开启 "正在思考..." spinner 后，如果后续还想切换到 "正在执行工具..." 的 spinner，必须先 `stop()` 再 `start()`——但 `WireMessageHandler` 并没有这么做，它只在 `ContentPart` / `ToolCall` 时 `stop()`，不再 `start()`，因此实际运行中每步只会有 Thinking 那一次 spinner。

---

## 8 · 设计与实现的落差汇总

经过源码亲读，09 篇揭示的落差按严重程度分为"语义落差"、"命名/风格分歧"、"预留未实装"、"硬编码简化" 四类：

### 8.1 语义落差（命令/字段名暗示的能力未实现）

| # | 位置 | 暗示能力 | 实际行为 |
|---|---|---|---|
| L1 | `/compact` 命令 | "压缩上下文" | 源码 `CompactCommandHandler.execute` 只有两个分支：`checkpointCount==0` 时 `printInfo("上下文为空，无需压缩")` 直接 return；`>0` 时 `printStatus("🗃️ 正在压缩上下文...")` + `printSuccess("✅ 上下文已压缩")` + `printInfo("注意：上下文压缩将在下次 Agent 运行时自动触发")`——**两条路径都没有实际触发任何压缩逻辑**，`Compaction` 服务从未被调用 |
| L2 | `/agents run <name>` | "切换代理" | 校验 agent 存在后打印硬编码的"请退出并重启"指引，**不切换 agent** |
| L3 | `ExecutionSpec.type: "agent"` 自定义命令 | "委托给指定 Agent" | `execution.getAgent()` 只在 `printInfo` 里出现，`runCommand(task)` 始终喂给**当前 agent**，agent 名无实际路由效果 |
| L4 | `CustomCommandSpec.requireApproval` 字段 | "执行前需要审批" | 字段被 `@Builder.Default private boolean requireApproval = false` 声明，但 `ConfigurableCommandHandler#execute` 从不读取——**任何自定义命令都不会触发审批流**（即使显式设 `requireApproval: true`） |
| L5 | `ExecutionSpec.async` 字段 | "异步执行" | 字段声明了（默认 false），但 `ConfigurableCommandHandler` 的所有分支都 `.block()` 同步执行——**async 配置无效** |
| L6 | `ExecutionSpec.ExecutionStep.type: "command"` 分支 | "执行某个 / 前缀命令" | `executeComposite` 对每个 step 构造 `ExecutionSpec.builder().type(step.getType()).script(step.getScript()).timeout(step.getTimeout()).build()` 后**一律直接调 `executeScript`**——**完全不读 `step.getType()` 做分派**。因此 type=`command` 无运行时路径：若该 step 的 `script` 字段为 null，`executeScript` 内部 `Runtime.exec(null)` 将直接 NPE；若 script 字段写了一个 `/reset` 这样的值，会被当作 shell 命令原样交给 `bash -c` 执行 |
| L7 | `CompositeStepSpec` 与 `ExecutionSpec.ExecutionStep` | 两个类"看起来都在做同一件事" | 运行时消费的是 `ExecutionSpec.ExecutionStep` 嵌套类；`CompositeStepSpec` 定义存在但 `ConfigurableCommandHandler` 不使用它——**两个 Spec 类共存、功能重叠、仅一个被消费** |
| L8 | `resolveParameters` 的 `required=true` 校验 | "参数缺失时终止" | 只打印错误并 `return parameterValues`，`execute` 主干无法区分"正常完成"与"提前返回"，**继续用不完整参数执行脚本/prompt**，失败延迟到运行时才暴露 |
| L9 | `CompactCommandHandler` 的 `checkpointCount > 0` 分支 | 暗示根据上下文是否为空"触发压缩" | 两个分支本质上都不触发压缩，差异**仅在文案**（"上下文为空，无需压缩" vs "正在压缩上下文…/已压缩/下次 Agent 运行时自动触发"）；源码注释 `// 手动触发压缩（通过运行一个空步骤触发压缩检查）` 之后那行"空步骤"**并未编写**，下面直接就是 printSuccess |

### 8.2 命名/风格分歧

| # | 位置 | 分歧 |
|---|---|---|
| S1 | `CommandHandler.getCategory()` 默认值 | `"general"`（英文） |
| S2 | `MemoryCommandHandler.getCategory` | `"knowledge"`（英文） |
| S3 | `CommandsCommandHandler.getCategory` | `"system"`（英文） |
| S4 | `WikiCommandHandler.getCategory` | `"documentation"`（英文） |
| S5 | **`IndexCommandHandler.getCategory`** | **`"上下文管理"`（中文字符串）** |
| S6 | `CustomCommandSpec.category` 默认值 | `"custom"`（英文） |
| S7 | 内置 3 个示例命令的 category | `"git"` / `"code"` / `"build"` |

`CommandRegistry.categorizedHandlers` 以字符串为 key，不做归一化，导致**中英混杂的分类 key 会原样保留**。目前没有消费 `CommandRegistry.categorizedHandlers` 的展示路径，所以用户感知不到；但 `/commands list` 展示自定义命令时按 `spec.getCategory()` 分组，如果用户 YAML 里自由写 category 名，可能出现重复（"git" 与 "Git"）。

### 8.3 预留未实装

| # | 位置 | 预留情况 |
|---|---|---|
| P1 | `CommandHandler.isAvailable(ctx)` 默认恒 true | 所有 18 个内建命令都不 override，但 `CommandRegistry.execute` 会调用它抛 `IllegalStateException`——**预留但无实例** |
| P2 | `WelcomeRenderer.showShortcutsHint(type)` 支持 welcome/input/thinking/approval/error 5 种 | `getHintForType` 只对 `error` 和 `approval` 返非空——**welcome/input/thinking 的 compareAndSet 防重复机制白做了，实际不输出任何内容** |
| P3 | `ExecutionSpec.environment` 在 composite 子步骤的 `ExecutionStep` 上 | `ExecutionStep` 没有 `environment` 字段，所以 composite 的每步不能单独定义环境变量 |
| P4 | `PreconditionSpec.value`（env_var 的期望值）支持精确匹配 | 支持但**不支持正则/通配符**——要与 "release" 等常用值做模糊匹配需外置 `command_exists` + 自定义 script |

### 8.4 硬编码简化

| # | 位置 | 硬编码项 |
|---|---|---|
| H1 | `HelpCommandHandler#execute` | 整段帮助文本作为字符串字面量硬编码；`/new`/`/wiki`/`/index` 漏收 |
| H2 | `InitCommandHandler#buildInitPrompt` | 2000+ 字符的中文 prompt 写死在 Java 源码里（实际 4441 字符），不外化为资源 |
| H3 | `VersionCommandHandler#execute` | 版本号 `"0.1.0"` 写死，不与 `WelcomeRenderer` 的 Manifest 读取对齐 |
| H4 | `JimiHighlighter` 元命令颜色 | `AttributedStyle.BLUE`，不随 ThemeConfig |
| H5 | `AssistantTextRenderer` 的 "思考过程/正式回答" 标签 | CYAN / GREEN 硬编码，不随主题 |
| H6 | `InteractionHandler` 的审批/输入提示符颜色 | YELLOW / CYAN 硬编码 |
| H7 | `ShellShortcutProcessor` 的 `timeout=60` | 硬编码到 JSON 字符串 `"{\"command\":...,\"timeout\":60}"`，用户无法调整 |
| H8 | `JimiCompleter.COMMON_PHRASES` | 15 个短语写死 |
| H9 | `JimiCompleter.IGNORED_DIRS` | 17 个目录/文件名黑名单写死 |
| H10 | `ThemeConfig.getPresetTheme` 的 5 个预设名 | `default/dark/light/minimal/matrix` 写死；`ThemeCommandHandler.isValidTheme` 复制一份，两处同步更新才能新增主题 |
| H11 | `AssistantTextRenderer.isChineseChar` | 仅判 `0x4E00-0x9FA5`，CJK 扩展/全角符号/emoji 按 1 宽度计算 |
| H12 | `WelcomeRenderer.printBanner` | 框线 ASCII 与整段布局写死 |
| H13 | `AgentsCommandHandler` 通用/专业分类 | 仅凭 `"Default Agent".equals(spec.getName())` 二分 |
| H14 | `ToolsCommandHandler` 的工具分类 | 4 类完全基于 `name.contains("file"/"bash"/"web"/...)` 子串判断，不使用工具元信息 |

---

## 9 · 一句话总结

Jimi 的 CLI 交互层是 **JLine 原生能力 + 手写 3 层分派（InputProcessor → CommandRegistry → CommandHandler）+ Wire 消息单向流** 的组合。命令系统通过 Spring 自动注入 `List<CommandHandler>` 实现零配置插件化；自定义命令通过 YAML + `YamlConfigLoader` 三层加载机制支持用户扩展；但**存在 20+ 处"字段声明了但运行时未消费"或"命令名暗示了但实际不执行"的落差**——它们都在 §8 里按类明示，任何基于本文档的二次开发都应以源码为准。
