# Jimi Loop Engineering 完整支持技术文档

## 文档概述

**文档版本**: v1.0  
**创建日期**: 2025-06-20  
**参考来源**: Addy Osmani - Loop Engineering  

Loop Engineering 是 Prompt Engineering 的下一阶段进化——从"人手动驱动 Agent"转变为"设计系统来自动驱动 Agent"。本文档详细阐述 Jimi 如何完整支持 Loop Engineering 的六大核心原语，包括已有能力、新增设计和实现方案。

---

## 目录

- [1. Loop Engineering 概述](#1-loop-engineering-概述)
- [2. 架构总览](#2-架构总览)
- [3. Automations — 自动化调度](#3-automations--自动化调度)
- [4. Worktrees — 并行隔离](#4-worktrees--并行隔离)
- [5. Skills — 项目知识固化](#5-skills--项目知识固化)
- [6. Plugins & Connectors — MCP 连接器](#6-plugins--connectors--mcp-连接器)
- [7. Sub-agents — Maker/Checker 分离](#7-sub-agents--makerchecker-分离)
- [8. State & Memory — 跨会话状态持久化](#8-state--memory--跨会话状态持久化)
- [9. 组合实战：构建完整 Loop](#9-组合实战构建完整-loop)
- [10. 命令参考](#10-命令参考)
- [11. 配置参考](#11-配置参考)
- [12. 最佳实践与注意事项](#12-最佳实践与注意事项)

---

## 1. Loop Engineering 概述

### 1.1 什么是 Loop Engineering

> "You shouldn't be prompting coding agents anymore. You should be designing loops that prompt your agents." — Peter Steinberger

Loop Engineering 是设计一个自动化系统来替代人类手动 prompt Agent。你不再逐轮对话，而是构建一个小型控制系统，由它来发现工作、分配任务、检查结果、记录进展、决定下一步行动。

### 1.2 六大核心原语

| # | 原语 | 职责 |
|---|------|------|
| 1 | **Automations** | 按计划定时运行，自动发现和分流任务 |
| 2 | **Worktrees** | 并行隔离，多个 Agent 不互相踩踏 |
| 3 | **Skills** | 固化项目知识，Agent 不用每次从零猜测 |
| 4 | **Plugins/Connectors** | 通过 MCP 协议连接外部真实工具 |
| 5 | **Sub-agents** | Maker 和 Checker 分离，避免自己批改自己作业 |
| 6 | **State/Memory** | 跨会话持久化状态，Agent 忘了但文件系统不会 |

### 1.3 Jimi 支持矩阵

| 原语 | Jimi 对应实现 | 状态 |
|------|-------------|------|
| Automations | Hook 事件驱动 + `/loop` + `/goal` | ✅ 完整支持 |
| Worktrees | SubAgent 上下文隔离 + Git Worktree 集成 | ✅ 完整支持 |
| Skills | SkillRegistry + SKILL.md + 渐进式披露 | ✅ 完整支持 |
| Plugins/Connectors | MCP 协议 (STDIO/HTTP) + Plugin 三层加载 | ✅ 完整支持 |
| Sub-agents | SubAgentTool + TeamAgentTool + 调度策略 | ✅ 完整支持 |
| State/Memory | MEMORY.md + Session JSONL + AGENTS.md | ✅ 完整支持 |

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                      Loop Control Layer                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐  │
│  │  /loop   │  │  /goal   │  │  Hooks   │  │ Cron/Schedule │  │
│  │ (cadence)│  │(condition)│  │ (event)  │  │  (external)   │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └──────┬────────┘  │
│       │              │              │               │           │
│       └──────────────┴──────────────┴───────────────┘           │
│                              │                                   │
├──────────────────────────────┼───────────────────────────────────┤
│                      Agent Orchestration                         │
│  ┌──────────────┐  ┌────────────────┐  ┌─────────────────┐     │
│  │  SubAgentTool │  │ TeamAgentTool  │  │  AgentRegistry  │     │
│  │  (isolation)  │  │ (collaboration)│  │  (agent specs)  │     │
│  └──────┬───────┘  └───────┬────────┘  └────────┬────────┘     │
│         │                   │                     │              │
├─────────┴───────────────────┴─────────────────────┴──────────────┤
│                      Execution Layer                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐    │
│  │ JimiEngine│  │ ToolReg  │  │ MCP Tools│  │ Git Worktree │    │
│  │  (ReAct)  │  │ (native) │  │ (remote) │  │  (isolation) │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────┘    │
│                                                                   │
├───────────────────────────────────────────────────────────────────┤
│                      Persistence Layer                            │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐    │
│  │MEMORY.md │  │Session   │  │ Skills   │  │ State File   │    │
│  │(long-term)│  │(.jsonl)  │  │(SKILL.md)│  │ (progress)   │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────┘    │
└───────────────────────────────────────────────────────────────────┘
```

---

## 3. Automations — 自动化调度

Automations 是 Loop 的心跳。它负责发现工作并触发后续流程。

### 3.1 Hook 事件驱动（已有）

Jimi 的 Hook 系统提供 16 种事件触发点，支持脚本/Agent/复合步骤执行：

```yaml
# ~/.jimi/hooks/auto-triage.yaml
name: auto-triage-ci-failures
trigger:
  type: SessionStart
execution:
  type: script
  script: |
    # 每次会话启动时自动检查 CI 状态
    cd ${JIMI_WORK_DIR}
    FAILURES=$(gh run list --status failure --limit 5 --json name,conclusion 2>/dev/null)
    if [ -n "$FAILURES" ]; then
      echo "{\"ci_failures\": $FAILURES}"
    fi
  timeout: 30
```

### 3.2 `/loop` 命令 — 按间隔重复执行

`/loop` 在会话内按指定时间间隔重复执行一个 prompt，直到用户手动停止。

**用法:**

```
/loop [interval] [prompt]
```

**参数:**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `interval` | 执行间隔 (如 `5m`, `1h`, `30s`) | `5m` |
| `prompt` | 每次循环执行的 prompt | 必需 |

**示例:**

```bash
# 每 5 分钟检查编译状态
/loop 5m 检查项目编译状态，如果有错误就修复

# 每 30 秒监控测试
/loop 30s 运行 mvn test -pl core 并报告失败的测试

# 每小时整理代码
/loop 1h 扫描 src/main 下的 TODO 注释，选择一个完成它
```

**控制命令:**

```bash
/loop stop       # 停止当前循环
/loop status     # 查看循环状态
/loop pause      # 暂停循环
/loop resume     # 恢复循环
```

**实现要点:**

```java
/**
 * LoopCommandHandler — /loop 命令处理器
 *
 * 核心机制：
 * 1. 解析 interval 和 prompt
 * 2. 启动 ScheduledExecutorService 按间隔触发
 * 3. 每次触发时向当前 JimiEngine 提交 prompt
 * 4. 支持 pause/resume/stop 生命周期管理
 */
@Component
public class LoopCommandHandler implements CommandHandler {

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> currentLoop;
    private volatile boolean paused = false;

    @Override
    public String getCommand() { return "loop"; }

    public void startLoop(Duration interval, String prompt, JimiEngine engine) {
        stopLoop(); // 确保只有一个活跃循环
        scheduler = Executors.newSingleThreadScheduledExecutor();
        currentLoop = scheduler.scheduleAtFixedRate(() -> {
            if (!paused) {
                engine.run(prompt).subscribe();
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stopLoop() {
        if (currentLoop != null) {
            currentLoop.cancel(false);
            currentLoop = null;
        }
    }
}
```

### 3.3 `/goal` 命令 — 目标驱动迭代

`/goal` 设定一个可验证的目标条件，Agent 持续工作直到条件满足。关键特性：**验证者和执行者使用不同的判断**，避免自己评判自己。

**用法:**

```
/goal [condition]
```

**示例:**

```bash
# 直到所有测试通过
/goal 所有 src/test 下的单元测试通过且 lint 无警告

# 直到编译成功
/goal mvn compile 成功且无 ERROR 输出

# 直到性能达标
/goal JMH benchmark 中 parseJson 方法的吞吐量 > 10000 ops/s

# 复合条件
/goal 以下条件全部满足: 1) mvn test 通过 2) checkstyle 无违规 3) 测试覆盖率 > 80%
```

**控制命令:**

```bash
/goal stop       # 放弃当前目标
/goal status     # 查看目标进度
/goal pause      # 暂停执行
/goal resume     # 恢复执行
```

**实现机制:**

```java
/**
 * GoalCommandHandler — /goal 命令处理器
 *
 * 核心流程：
 * 1. 解析目标条件描述
 * 2. 进入 goal-loop：
 *    a. 执行者 Agent 向目标方向工作一步
 *    b. 验证者（独立模型/SubAgent）评估是否满足条件
 *    c. 如果满足 → 结束，否则 → 继续
 * 3. 内置安全限制：最大步数、超时、token 预算
 */
@Component
public class GoalCommandHandler implements CommandHandler {

    /** 验证者使用的轻量模型（与执行者不同） */
    private static final String VERIFIER_MODEL = "gpt-4o-mini";

    /** 最大迭代次数（防止无限循环） */
    private static final int MAX_ITERATIONS = 50;

    @Override
    public String getCommand() { return "goal"; }

    public Mono<GoalResult> executeGoal(String condition, JimiEngine engine) {
        return Mono.defer(() -> {
            AtomicInteger iteration = new AtomicInteger(0);

            return Flux.generate(sink -> {
                if (iteration.get() >= MAX_ITERATIONS) {
                    sink.complete();
                    return;
                }
                sink.next(iteration.incrementAndGet());
            })
            .concatMap(step -> {
                // 1. 执行者工作一步
                return engine.run(buildWorkerPrompt(condition, step))
                    // 2. 验证者独立评估
                    .then(verify(condition, engine.getContext()));
            })
            .takeUntil(GoalVerification::isSatisfied)
            .last()
            .map(v -> GoalResult.of(v.isSatisfied(), iteration.get()));
        });
    }

    /**
     * 验证者：使用独立模型评估目标是否满足
     * 关键：验证者不是执行者，避免 "自己批改自己作业"
     */
    private Mono<GoalVerification> verify(String condition, Context context) {
        String verifyPrompt = String.format("""
            评估以下目标条件是否已经满足。只返回 JSON。
            目标条件: %s
            当前工作状态: %s
            返回格式: {"satisfied": true/false, "reason": "..."}
            """, condition, context.getLastToolResults());

        return verifierLLM.chat(verifyPrompt)
            .map(GoalVerification::parse);
    }
}
```

**安全限制配置:**

```yaml
# application.yml
jimi:
  loop:
    max-iterations: 50          # /goal 最大迭代次数
    max-tokens-per-goal: 500000 # 单次 goal 的 token 预算
    verify-interval: 1          # 每 N 步验证一次
    timeout-minutes: 60         # 超时自动停止
  schedule:
    enabled: true
    thread-pool-size: 2
```

### 3.4 外部调度集成

对于需要脱离终端持续运行的场景，通过 CLI + cron/CI 实现：

```bash
# crontab 示例：每天早上 9 点运行 triage
0 9 * * * cd /path/to/project && jimi --non-interactive \
  --prompt "检查昨天的 CI 失败和新 Issue，写入 .jimi/triage.md"

# GitHub Actions 示例
name: Jimi Daily Triage
on:
  schedule:
    - cron: '0 1 * * *'  # UTC 01:00 = 北京 09:00
jobs:
  triage:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run Jimi Triage
        run: |
          jimi --non-interactive \
            --skill triage \
            --prompt "分析最近 24 小时的变更和 Issue，更新 .jimi/progress.md"
```

---

## 4. Worktrees — 并行隔离

### 4.1 上下文隔离（已有）

SubAgent 已具备完整的上下文隔离：独立历史文件、独立工具注册表、独立 Context。

```java
// SubAgentTool 创建独立上下文
Path subHistoryFile = createTempHistoryFile(agent.getName());
Context subContext = new Context(subHistoryFile, objectMapper);
ToolRegistry subToolRegistry = createSubToolRegistry(subAgentSpec);
JimiEngine subEngine = createSubEngine(agent, subContext, subToolRegistry);
```

### 4.2 Git Worktree 文件系统隔离

当多个 Agent 需要修改同一个仓库的文件时，上下文隔离不够——还需要文件系统级别的隔离。

**配置方式（Agent YAML）:**

```yaml
# agents/feature-developer.yaml
name: feature-developer
model: gpt-4o
isolation: worktree    # 启用 git worktree 隔离
worktree:
  auto_cleanup: true   # 任务完成后自动清理
  branch_prefix: jimi/ # 分支前缀
```

**配置方式（Team YAML）:**

```yaml
# agents/team-lead.yaml
team:
  name: parallel-features
  max_concurrency: 3
  teammates:
    - teammate_id: dev-auth
      agent_path: agents/developer
      isolation: worktree     # 每个 Teammate 独立 worktree
    - teammate_id: dev-api
      agent_path: agents/developer
      isolation: worktree
    - teammate_id: reviewer
      agent_path: agents/reviewer
      isolation: shared       # Reviewer 共享主 worktree（只读）
```

**实现设计:**

```java
/**
 * WorktreeManager — Git Worktree 生命周期管理
 *
 * 职责：
 * 1. 为 SubAgent/Teammate 创建独立的 git worktree
 * 2. 任务完成后合并变更或清理
 * 3. 冲突检测与报告
 */
@Service
public class WorktreeManager {

    /**
     * 为指定 Agent 创建隔离的 worktree
     *
     * @param agentName Agent 名称（用于分支命名）
     * @param baseDir   主仓库目录
     * @return worktree 的工作目录路径
     */
    public Path createWorktree(String agentName, Path baseDir) throws IOException {
        String branchName = "jimi/" + agentName + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path worktreeDir = baseDir.resolve(".jimi/worktrees/" + agentName);

        ProcessBuilder pb = new ProcessBuilder(
            "git", "worktree", "add", "-b", branchName,
            worktreeDir.toString(), "HEAD"
        );
        pb.directory(baseDir.toFile());
        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("Failed to create worktree: exit code " + exitCode);
        }

        log.info("Created worktree for agent '{}' at: {}", agentName, worktreeDir);
        return worktreeDir;
    }

    /**
     * 清理 worktree 并删除临时分支
     */
    public void cleanupWorktree(Path worktreeDir, Path baseDir) throws IOException {
        // 1. 移除 worktree
        ProcessBuilder pb = new ProcessBuilder(
            "git", "worktree", "remove", "--force", worktreeDir.toString()
        );
        pb.directory(baseDir.toFile());
        pb.start().waitFor();

        log.info("Cleaned up worktree: {}", worktreeDir);
    }

    /**
     * 合并 worktree 的变更到主分支
     */
    public MergeResult mergeWorktree(Path worktreeDir, Path baseDir, String targetBranch)
            throws IOException {
        // 获取 worktree 的分支名
        String branch = getWorktreeBranch(worktreeDir);

        ProcessBuilder pb = new ProcessBuilder(
            "git", "merge", "--no-ff", branch, "-m",
            "Merge agent work from " + branch
        );
        pb.directory(baseDir.toFile());
        Process process = pb.start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            return MergeResult.conflict(readStream(process.getErrorStream()));
        }
        return MergeResult.success();
    }
}
```

### 4.3 Worktree 与 Team 的协作流程

```
┌─────────────┐     ┌─────────────────────────────────────────────┐
│  Team Lead  │     │             Git Repository                   │
│  (Main Agent)│     │                                             │
└──────┬──────┘     │  main branch ────────────────────────►      │
       │            │       │                                      │
       │ spawn      │       ├── jimi/dev-auth-a1b2 (worktree A)   │
       ├───────────►│       │       └── dev-auth Agent works here  │
       │            │       │                                      │
       │ spawn      │       ├── jimi/dev-api-c3d4  (worktree B)   │
       ├───────────►│       │       └── dev-api Agent works here   │
       │            │       │                                      │
       │ verify     │       └── (shared) reviewer reads main       │
       ├───────────►│                                              │
       │            └──────────────────────────────────────────────┘
       │
       │ merge results
       ▼
  [PR / Commit]
```

---

## 5. Skills — 项目知识固化

### 5.1 Skill 系统概述（已有）

Skills 是 Jimi 中"一次编写，每次运行都生效"的项目知识。文章中的核心观点是：

> 没有 Skills，Loop 每个周期都从零重新推导整个项目。有了 Skills，它会复合增长。

**Jimi 的 Skill 格式：**

```markdown
---
name: project-conventions
description: 本项目的编码约定和架构规范
version: 1.0.0
category: development
triggers:
  - 代码规范
  - 编码约定
  - 架构
dependencies:
  - code-quality-suite
---

## 项目约定

### 包结构
- `core/` — 核心引擎，不依赖外部框架
- `tool/` — 工具实现，每个工具一个类
- `config/` — 配置相关，使用 @ConfigurationProperties

### 命名规范
- 工具类命名: XxxTool (如 ReadFileTool, WriteFileTool)
- 配置类命名: XxxConfig (如 LlmConfig, MemoryConfig)
- 测试类命名: XxxTest (如 ReadFileToolTest)

### 禁止事项
- 不使用 @Deprecated，直接删除过时代码
- 不使用 Lombok @Data 在不可变对象上
- 不在 Reactor 链中使用 .block()
```

### 5.2 Loop 专用 Skill 模板

为 Loop Engineering 场景定制的 Skill：

```markdown
---
name: loop-triage
description: Loop 自动分流技能 — 读取 CI/Issue/Commit 并生成优先级任务列表
version: 1.0.0
category: loop
triggers:
  - triage
  - 分流
  - CI 失败
---

## Triage 流程

### 输入源
1. `gh run list --status failure` — 最近的 CI 失败
2. `gh issue list --state open --label bug` — 未关闭的 Bug
3. `git log --since="yesterday"` — 昨天的提交

### 输出格式
将发现写入 `.jimi/triage.md`，格式如下：

```markdown
# Triage Report — {{date}}

## Critical (立即处理)
- [ ] CI: test-auth 失败 — NullPointerException in AuthService#validate

## High (今天内处理)
- [ ] Issue #42: 登录接口超时

## Low (本周内处理)
- [ ] Commit abc123 引入了新的 TODO
```

### 决策规则
- CI 失败 → Critical
- Bug 标签 Issue → High
- TODO/FIXME → Low
```

### 5.3 Skill 目录布局

```
~/.jimi/skills/
├── project-conventions/
│   └── SKILL.md
├── loop-triage/
│   └── SKILL.md
├── code-review-checklist/
│   └── SKILL.md
└── git-commit-guide/
    └── SKILL.md

<project>/.jimi/skills/
├── api-conventions/
│   └── SKILL.md
└── test-patterns/
    └── SKILL.md
```

---

## 6. Plugins & Connectors — MCP 连接器

### 6.1 MCP 协议支持（已有）

Jimi 完整实现了 MCP (Model Context Protocol)，支持 STDIO 和 HTTP 两种传输方式：

```json
// .jimi/mcp.json
{
  "mcpServers": {
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "${GITHUB_TOKEN}"
      }
    },
    "linear": {
      "command": "npx",
      "args": ["-y", "mcp-linear"],
      "env": {
        "LINEAR_API_KEY": "${LINEAR_API_KEY}"
      }
    },
    "slack": {
      "url": "https://mcp-slack.example.com/sse",
      "headers": {
        "Authorization": "Bearer ${SLACK_TOKEN}"
      }
    }
  }
}
```

### 6.2 Loop 中 Connector 的作用

在 Loop Engineering 中，Connectors 是"循环能在真实环境中行动"的关键：

| Connector | Loop 中的用途 |
|-----------|-------------|
| GitHub MCP | 读取 Issue/PR、创建 PR、更新状态 |
| Linear MCP | 读取任务看板、更新任务状态 |
| Slack MCP | 发送通知、汇报进展 |
| Database MCP | 查询数据验证修复效果 |
| CI/CD MCP | 触发构建、获取构建结果 |

### 6.3 Loop 自动化 + Connector 实战

```yaml
# .jimi/hooks/loop-pr-on-complete.yaml
name: auto-pr-on-goal-complete
trigger:
  type: Stop
  matcher: ".*goal.*complete.*"
execution:
  type: composite
  steps:
    - type: script
      script: |
        cd ${JIMI_WORK_DIR}
        BRANCH=$(git rev-parse --abbrev-ref HEAD)
        if [ "$BRANCH" != "main" ] && [ "$BRANCH" != "master" ]; then
          git add -A
          git commit -m "feat: auto-fix by Jimi loop"
          git push origin $BRANCH
          echo "{\"branch\": \"$BRANCH\", \"pushed\": true}"
        fi
    - type: agent
      prompt: |
        使用 GitHub MCP 工具为当前分支创建 Pull Request。
        标题使用最近一次 commit message。
        正文包含本次修改的摘要。
```

### 6.4 Plugin 分发

将 Loop 相关的 Skills + Hooks + MCP 配置打包为 Plugin：

```yaml
# ~/.jimi/plugins/loop-kit/plugin.yaml
name: loop-kit
version: 1.0.0
description: Loop Engineering 全套工具包
provides:
  skills:
    - skills/loop-triage
    - skills/loop-verify
  hooks:
    - hooks/auto-pr-on-complete.yaml
    - hooks/daily-triage.yaml
  mcp:
    - mcp/github.json
    - mcp/linear.json
compatibility:
  jimi_version: ">=0.1.0"
```

---

## 7. Sub-agents — Maker/Checker 分离

### 7.1 为什么需要分离

> "The model that wrote the code is way too nice grading its own homework." — Addy Osmani

Loop 中最有价值的结构性设计是：**写代码的 Agent 和检查代码的 Agent 必须是两个不同的实例**，最好使用不同的模型或不同的 system prompt。

### 7.2 SubAgent 配置（已有）

```yaml
# agents/main.yaml
name: main-agent
model: gpt-4o
subagents:
  explorer:
    path: agents/explorer
    description: 探索代码库，理解结构
  implementer:
    path: agents/implementer
    description: 实现功能代码
  reviewer:
    path: agents/reviewer
    description: 代码审查，找出问题
```

```yaml
# agents/reviewer.yaml
name: reviewer
model: claude-sonnet-4-20250514  # 使用不同模型增强多样性
system_prompt: |
  你是一个严格的代码审查员。你的职责是找出代码中的问题，而不是赞美它。
  重点关注：
  1. 逻辑错误和边界条件
  2. 安全漏洞
  3. 性能问题
  4. 不符合项目约定的地方
  
  如果代码有问题，明确指出问题和修复建议。
  如果代码没有问题，只说"LGTM"，不要编造问题。
```

### 7.3 Team 协作模式（已有）

```yaml
# agents/loop-team.yaml
name: loop-team-lead
model: gpt-4o
team:
  name: feature-loop
  max_concurrency: 3
  strategy: SPECIALTY_MATCH
  timeout_seconds: 1800
  teammates:
    - teammate_id: analyst
      agent_path: agents/analyst
      description: 分析需求和现有代码
      specialties: [analysis, architecture]
    - teammate_id: developer
      agent_path: agents/developer
      description: 实现功能代码
      specialties: [coding, implementation]
      isolation: worktree
    - teammate_id: tester
      agent_path: agents/tester
      description: 编写和运行测试
      specialties: [testing, verification]
      isolation: worktree
    - teammate_id: reviewer
      agent_path: agents/reviewer
      description: 审查代码质量
      specialties: [review, security]
```

### 7.4 Loop 中的典型 Agent 分工

```
┌──────────────────────────────────────────────────────┐
│                    Loop 一次迭代                       │
│                                                      │
│  ┌─────────┐   ┌─────────────┐   ┌──────────────┐  │
│  │ Explorer │──►│ Implementer │──►│   Reviewer   │  │
│  │ (分析)   │   │  (实现)      │   │  (验证)      │  │
│  └─────────┘   └─────────────┘   └──────┬───────┘  │
│                                          │          │
│                                    ┌─────▼─────┐    │
│                                    │ 通过? │    │
│                                    └─────┬─────┘    │
│                                     Yes  │  No      │
│                                          │  │       │
│                              ┌───────────┘  │       │
│                              ▼              ▼       │
│                         [完成/PR]    [反馈给实现者]   │
│                                                      │
└──────────────────────────────────────────────────────┘
```

---

## 8. State & Memory — 跨会话状态持久化

### 8.1 三层记忆系统（已有）

| 层级 | 文件 | 生命周期 | 用途 |
|------|------|----------|------|
| Layer 1 | `~/.jimi/memory/MEMORY.md` | 永久 | 长期记忆，注入到每次 System Prompt |
| Layer 2 | `.jimi/sessions/<id>.jsonl` | 会话 | 会话历史，支持恢复 |
| Layer 3 | `AGENTS.md` | 项目 | 项目级知识，团队共享 |

### 8.2 Loop 状态文件

Loop Engineering 的核心：**Agent 忘了，但文件不会忘**。

**推荐的 Loop 状态文件格式：**

```markdown
<!-- .jimi/progress.md — Loop 状态文件 -->
# Loop Progress

## 当前迭代
- **迭代号**: #7
- **开始时间**: 2025-06-20 09:00
- **目标**: 所有测试通过且覆盖率 > 80%

## 已完成
- [x] #1: 修复 AuthService NPE (CI green)
- [x] #2: 补充 UserController 单元测试 (覆盖率 65% → 72%)
- [x] #3: 修复 RateLimiter 并发 bug
- [x] #4: 添加 TokenCounter 边界测试
- [x] #5: 重构 SessionManager 减少圈复杂度
- [x] #6: 补充集成测试 (覆盖率 72% → 78%)

## 进行中
- [ ] #7: 补充 MemoryStore 单元测试 (目标: 覆盖率 > 80%)

## 待处理
- [ ] CI 告警: SkillLoader 未覆盖 JAR 模式分支
- [ ] Code Review: TeamManager timeout 逻辑需要验证

## 阻塞项
- (无)

## 统计
- 总迭代: 7
- 成功率: 100%
- Token 消耗: 约 125,000
- 总耗时: 35 分钟
```

### 8.3 状态文件与 /goal 联动

```bash
# /goal 自动使用状态文件追踪进度
/goal 覆盖率达到 80% --state .jimi/progress.md
```

`/goal` 每完成一步自动更新状态文件，下一步基于状态文件决定做什么。即使 Agent 的上下文被压缩或重置，它仍然可以从状态文件恢复进度。

### 8.4 与外部工具的状态同步

通过 MCP Connector 将状态同步到外部系统：

```yaml
# Hook: 每次状态更新时同步到 Linear
name: sync-progress-to-linear
trigger:
  type: PostToolUse
  matcher: "WriteFile"
conditions:
  - type: tool_result_contains
    pattern: "progress.md"
execution:
  type: agent
  prompt: |
    读取 .jimi/progress.md 的最新内容，
    使用 Linear MCP 更新对应的任务状态。
    将"已完成"标记为 Done，"进行中"标记为 In Progress。
```

---

## 9. 组合实战：构建完整 Loop

### 9.1 Daily Triage Loop

一个完整的"每日自动分流"Loop 示例：

**Step 1: 创建 Triage Skill**

```markdown
---
name: daily-triage
description: 每日自动分流 — 检查 CI、Issue、提交历史
version: 1.0.0
triggers:
  - triage
  - daily check
---

## 执行步骤

1. 运行 `gh run list --status failure --limit 10` 获取最近 CI 失败
2. 运行 `gh issue list --state open --label bug --limit 10` 获取 Bug Issue
3. 运行 `git log --since="1 day ago" --oneline` 获取昨日提交
4. 分析以上信息，按优先级分类
5. 将结果写入 `.jimi/triage.md`
6. 对于 Critical 级别的问题，尝试自动修复
```

**Step 2: 配置 Agent 团队**

```yaml
# agents/triage-team.yaml
name: triage-lead
model: gpt-4o
team:
  name: auto-triage
  max_concurrency: 2
  strategy: SPECIALTY_MATCH
  teammates:
    - teammate_id: fixer
      agent_path: agents/developer
      description: 修复代码问题
      specialties: [fix, implementation]
      isolation: worktree
    - teammate_id: verifier
      agent_path: agents/reviewer
      description: 验证修复正确性
      specialties: [verification, testing]
```

**Step 3: 设置 Hook 串联**

```yaml
# .jimi/hooks/daily-loop.yaml
name: daily-loop-trigger
trigger:
  type: SessionStart
conditions:
  - type: script
    script: |
      # 仅在工作日 9:00-10:00 触发
      HOUR=$(date +%H)
      DOW=$(date +%u)
      [ "$DOW" -le 5 ] && [ "$HOUR" -eq 9 ]
execution:
  type: agent
  prompt: |
    使用 daily-triage 技能执行今日分流。
    对于 Critical 级别的问题，启动团队修复。
    完成后更新 .jimi/progress.md。
```

**Step 4: 运行 Loop**

```bash
# 方式 1: 手动触发一次
jimi --skill daily-triage

# 方式 2: 会话内循环
/loop 4h 执行 daily-triage 技能，处理新发现的问题

# 方式 3: 设定目标
/goal CI 全绿且无 Critical 级别 Issue --state .jimi/progress.md

# 方式 4: 外部 cron 调度
0 9 * * 1-5 cd /project && jimi --non-interactive --skill daily-triage
```

### 9.2 Feature Development Loop

一个"自动开发功能"的 Loop：

```bash
# 1. 设定目标
/goal 实现用户注册功能，包含：\
  1) POST /api/users/register 接口 \
  2) 参数校验（邮箱格式、密码强度）\
  3) 单元测试覆盖率 > 90% \
  4) 接口文档更新 \
  --state .jimi/feature-register.md

# Loop 自动执行：
# 迭代 1: Explorer 分析现有代码结构
# 迭代 2: Implementer 创建 Controller + Service
# 迭代 3: Reviewer 发现缺少参数校验
# 迭代 4: Implementer 添加校验逻辑
# 迭代 5: Tester 编写单元测试
# 迭代 6: Reviewer 确认通过
# 迭代 7: Implementer 更新 API 文档
# 验证者确认所有条件满足 → Loop 结束
```

### 9.3 Continuous Refactoring Loop

```bash
# 持续重构循环 — 每次改善一点
/loop 10m 从以下列表中选择一项执行：\
  1. 找到最长的方法，拆分它 \
  2. 找到重复代码，抽取公共方法 \
  3. 找到缺少测试的公共方法，补充测试 \
  4. 找到过时的注释，更新或删除 \
  每次只做一项，做完后运行 mvn test 确保不破坏现有功能
```

---

## 10. 命令参考

### /loop

| 子命令 | 说明 | 示例 |
|--------|------|------|
| `/loop <interval> <prompt>` | 启动循环 | `/loop 5m 检查编译状态` |
| `/loop stop` | 停止循环 | — |
| `/loop pause` | 暂停循环 | — |
| `/loop resume` | 恢复循环 | — |
| `/loop status` | 查看状态 | — |

**interval 格式:** `30s`, `5m`, `1h`, `2h30m`

### /goal

| 子命令 | 说明 | 示例 |
|--------|------|------|
| `/goal <condition>` | 设定目标 | `/goal 所有测试通过` |
| `/goal stop` | 放弃目标 | — |
| `/goal pause` | 暂停执行 | — |
| `/goal resume` | 恢复执行 | — |
| `/goal status` | 查看进度 | — |

**选项:**

| 选项 | 说明 | 默认值 |
|------|------|--------|
| `--state <file>` | 状态文件路径 | `.jimi/goal-state.md` |
| `--max-steps <n>` | 最大步数 | `50` |
| `--timeout <duration>` | 超时时间 | `60m` |
| `--verify-model <model>` | 验证者模型 | 配置文件中的默认值 |

---

## 11. 配置参考

### application.yml 完整配置

```yaml
jimi:
  # Loop Engineering 配置
  loop:
    enabled: true
    # /loop 配置
    schedule:
      thread-pool-size: 2        # 调度线程池大小
      max-concurrent-loops: 3    # 最大并发 loop 数
    # /goal 配置
    goal:
      max-iterations: 50         # 最大迭代次数
      max-tokens: 500000         # token 预算
      verify-interval: 1         # 每 N 步验证一次
      timeout-minutes: 60        # 超时时间
      verifier-model: ""         # 验证者模型（空则用默认）
    # Worktree 配置
    worktree:
      enabled: true
      base-dir: ".jimi/worktrees" # worktree 存放目录
      auto-cleanup: true          # 完成后自动清理
      branch-prefix: "jimi/"      # 分支前缀
    # 状态持久化配置
    state:
      default-file: ".jimi/progress.md"  # 默认状态文件
      auto-update: true                   # 自动更新状态
      sync-interval: "1m"                 # 同步间隔

  # 已有配置保持不变
  memory:
    enabled: true
    storage-path: "~/.jimi/memory"
  skills:
    auto-activate: true
  mcp:
    config-file: ".jimi/mcp.json"
```

---

## 12. 最佳实践与注意事项

### 12.1 设计原则

1. **从小开始，逐步扩展**
   - 先用 `/goal` 验证单个任务能否自动完成
   - 成功后再组合成 `/loop`
   - 最后通过 cron/CI 实现无人值守

2. **始终保留人类审查点**
   - Loop 跑得越快，你越需要读它产出的代码
   - Reviewer Agent 是辅助而非替代人类审查
   - 关键变更设置 Hook 要求人工确认

3. **Token 成本意识**
   - `/goal` 设置合理的 `max-iterations` 和 token 预算
   - `/loop` 的 interval 不要设得太短
   - 使用 Skills 减少每轮的 prompt 重复

4. **Maker/Checker 必须分离**
   - 写代码的 Agent 永远不要自己验证自己
   - 验证者尽量用不同模型或不同 temperature
   - 验证条件要可量化（测试通过、覆盖率达标、lint 无警告）

### 12.2 常见反模式

| 反模式 | 问题 | 改正 |
|--------|------|------|
| Loop 不设限制 | Token 爆炸、无限循环 | 设置 max-iterations 和 timeout |
| 单 Agent 做全部 | 上下文膨胀、质量下降 | 拆分为 SubAgent/Team |
| 不用状态文件 | 上下文压缩后丢失进度 | 始终使用 `--state` 选项 |
| 循环间隔太短 | 浪费资源、产出低质量 | 10 秒以下的间隔几乎没有意义 |
| 不读 Loop 产出 | 理解力退化、认知投降 | 定期 review .jimi/progress.md |
| 共享工作目录 | 多 Agent 文件冲突 | 使用 `isolation: worktree` |

### 12.3 安全考量

1. **Token 预算**: 始终设置上限，防止失控的 loop 消耗大量费用
2. **操作审批**: 对破坏性操作（删文件、force push）保留 Hook 审批
3. **Worktree 清理**: 确保 `auto_cleanup: true`，避免磁盘泄漏
4. **敏感信息**: MCP 配置中的 token 使用环境变量，不要硬编码
5. **回滚机制**: Loop 产出的变更始终在独立分支，方便回滚

### 12.4 调试 Loop

```bash
# 查看 Loop 日志
tail -f ~/.jimi/logs/jimi.log | grep -i "loop\|goal"

# 查看状态文件变化
watch -n 5 cat .jimi/progress.md

# 检查活跃的 worktree
git worktree list

# Token 消耗统计
/stats   # 查看当前会话的 token 使用情况
```

---

## 附录 A：Loop Engineering 速查表

```
┌─────────────────────────────────────────────────────────┐
│              Jimi Loop Engineering Cheatsheet            │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  快速开始:                                              │
│    /goal 所有测试通过                                    │
│    /loop 5m 检查并修复编译错误                           │
│                                                         │
│  团队协作:                                              │
│    配置 team yaml → TeamAgentTool 自动编排               │
│    isolation: worktree → 文件系统级隔离                   │
│                                                         │
│  状态持久化:                                             │
│    .jimi/progress.md — Loop 状态文件                     │
│    MEMORY.md — 长期记忆                                  │
│    AGENTS.md — 项目知识                                  │
│                                                         │
│  外部集成:                                              │
│    .jimi/mcp.json — MCP 连接器配置                       │
│    .jimi/hooks/ — 事件驱动自动化                          │
│    crontab/GitHub Actions — 外部调度                      │
│                                                         │
│  安全限制:                                              │
│    --max-steps 50  (防止无限循环)                         │
│    --timeout 60m   (防止永不停止)                         │
│    token budget    (防止费用失控)                         │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 附录 B：文件布局总览

```
<project>/
├── .jimi/
│   ├── hooks/                    # 事件驱动自动化
│   │   ├── auto-triage.yaml
│   │   └── auto-pr-on-complete.yaml
│   ├── skills/                   # 项目级 Skills
│   │   └── project-conventions/
│   │       └── SKILL.md
│   ├── mcp.json                  # MCP 连接器配置
│   ├── progress.md               # Loop 状态文件
│   ├── triage.md                 # Triage 结果
│   ├── sessions/                 # 会话历史
│   │   └── <session-id>.jsonl
│   └── worktrees/                # Git Worktree 目录
│       ├── dev-auth/
│       └── dev-api/
├── agents/                       # Agent 定义
│   ├── main.yaml
│   ├── developer.yaml
│   ├── reviewer.yaml
│   └── triage-team.yaml
├── AGENTS.md                     # 项目知识文档
└── ...

~/.jimi/
├── memory/
│   └── <hash>/MEMORY.md          # 长期记忆
├── skills/                       # 全局 Skills
├── hooks/                        # 全局 Hooks
├── plugins/                      # 全局 Plugins
│   └── loop-kit/
│       └── plugin.yaml
└── config.yml                    # 全局配置
```
