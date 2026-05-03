# Jimi 插件开发指南

> 本指南介绍如何为 Jimi 开发、测试、分发和安装一个插件（Plugin）。
> 插件是把 **Skills / Hooks / Commands / MCP / Subagent** 打包成统一可分发单元的机制。

## 目录

- [1. 概述](#1-概述)
- [2. 插件包结构](#2-插件包结构)
- [3. `plugin.yaml` 完整参考](#3-pluginyaml-完整参考)
- [4. 五种扩展点编写指南](#4-五种扩展点编写指南)
- [5. 插件的安装与分发](#5-插件的安装与分发)
- [6. `/plugin` 命令参考](#6-plugin-命令参考)
- [7. 本地开发与调试](#7-本地开发与调试)
- [8. 常见问题 FAQ](#8-常见问题-faq)

---

## 1. 概述

### 1.1 为什么需要插件

Jimi 原生支持五种扩展点：

| 扩展点 | 作用 | 原生目录 |
| --- | --- | --- |
| **Skills** | 注入系统提示的专家知识 | `~/.jimi/skills/` |
| **Hooks** | 事件触发的自动化脚本 | `~/.jimi/hooks/` |
| **Commands** | 自定义斜杠命令 | `~/.jimi/commands/` |
| **MCP** | Model Context Protocol 服务器 | `~/.jimi/mcp/` |
| **Subagent** | 子 Agent 定义 | `~/.jimi/agents/` |

在没有插件机制之前，一个"Java 开发全家桶"扩展需要用户把 Skills、Hooks、Commands 文件手动分发到不同目录，升级/卸载极其繁琐。

**插件**把这些扩展点封装成一个 **有版本号、有清单、可整体分发** 的目录（或 ZIP 归档），用 `/plugin install <source>` 一键安装、`/plugin uninstall <name>` 一键卸载。

### 1.2 插件的三层作用域

| 作用域 | 来源 | 优先级 |
| --- | --- | --- |
| `CLASSPATH` | `src/main/resources/plugins/` 或 JAR 内 `plugins/` | 最低（Jimi 内置） |
| `USER` | `~/.jimi/plugins/` | 中 |
| `PROJECT` | `<project>/.jimi/plugins/` | 最高 |

**后加载覆盖**：同名插件后加载的覆盖先加载的，符合 Jimi 原有的"项目 > 用户 > 内置"语义。

### 1.3 核心设计约束

- **零侵入**：插件系统不改写任何 Jimi 核心类，通过适配器（`PluginModuleAdapter`）桥接
- **白名单保护**：`provides` 显式声明对外暴露的扩展项，未列出的不会被注册
- **渐进增强**：MVP 阶段 Skills 不支持运行时卸载（需重启会话），Hooks/Commands 支持即时 enable/disable

---

## 2. 插件包结构

一个完整的 Jimi 插件目录如下：

```
my-plugin/
├── plugin.yaml              # 必需：插件清单
├── skills/                  # 可选：Skills 扩展点
│   └── <skill-name>/
│       └── SKILL.md
├── hooks/                   # 可选：Hooks 扩展点
│   └── <hook-name>.yaml
├── commands/                # 可选：自定义命令扩展点
│   └── <command-name>.yaml
├── mcp/                     # 可选：MCP 服务器
│   └── servers.json
└── agents/                  # 可选：Subagent 定义
    └── <agent-name>/
        └── agent.yaml
```

**目录约定**：

- `plugin.yaml`（或 `plugin.yml`）必须位于插件根目录，否则不会被识别
- 五个扩展子目录均为**可选**，按需创建
- 子目录名固定小写（`skills` / `hooks` / `commands` / `mcp` / `agents`）

---

## 3. `plugin.yaml` 完整参考

### 3.1 最小清单

```yaml
name: hello-world
version: 1.0.0
description: 我的第一个 Jimi 插件
```

**必需字段**：`name` / `version` / `description` 三项缺一不可（Loader 会在解析阶段抛 `IllegalArgumentException`）。

### 3.2 完整清单示例

```yaml
# ========== 基本元信息 ==========
name: java-dev-kit
version: 1.2.0
description: Java 开发者全套扩展（Skills + Hooks + Commands）
author: jimi-team
homepage: https://github.com/leavesfly/jimi-plugin-java-dev-kit
repository: https://github.com/leavesfly/jimi-plugin-java-dev-kit
license: Apache-2.0
keywords:
  - java
  - maven
  - spring

# ========== 兼容性约束（Loader 在加载前强校验） ==========
compatibility:
  jimi_version: ">=0.1.0,<1.0.0"   # 支持逗号分隔的多约束
  java_version: ">=17"
  os:                               # 可选，不填则不限制
    - linux
    - mac

# ========== 扩展点白名单（显式声明对外暴露什么） ==========
provides:
  skills:
    - java-expert
    - spring-boot-helper
  hooks:
    - auto-format-java
  commands:
    - qbuild
    - qtest
  mcp_servers:
    - filesystem
  agents:
    - code-reviewer

# ========== 默认启用状态 ==========
defaults:
  enabled: true                     # 插件级开关
  modules:                          # 模块级开关
    skills: true
    hooks: true
    commands: true
    mcp: false                      # 默认不启用 MCP 子模块
    agents: true

# ========== 运行环境依赖（Loader 在加载前检查） ==========
requirements:
  binaries:
    - name: mvn
      check: "mvn --version"        # 可选，默认用 command -v 探测
      install_hint: "brew install maven"
  env_vars:
    - JAVA_HOME
  files:
    - lib/custom-tool.jar           # 相对插件目录；也支持绝对路径

# ========== 插件间依赖（MVP 阶段仅版本校验） ==========
dependencies:
  - name: git-toolkit
    version: ">=0.3.0"

# ========== 生命周期脚本（占位，P2 未实现执行器） ==========
lifecycle:
  install: "scripts/install.sh"
  enable: "scripts/enable.sh"
  disable: "scripts/disable.sh"
```

### 3.3 字段详解

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `name` | string | ✅ | 插件唯一标识，建议 kebab-case，全局唯一 |
| `version` | string | ✅ | 语义化版本号，如 `1.2.0` / `0.1.0-SNAPSHOT` |
| `description` | string | ✅ | 简短描述 |
| `author` / `homepage` / `repository` / `license` | string | ❌ | 元信息，用于插件市场展示 |
| `keywords` | list\<string\> | ❌ | 关键词，便于检索 |
| `compatibility.jimi_version` | string | ❌ | Jimi 版本范围，支持 `>=` / `<=` / `>` / `<` / `=`，逗号分隔多约束 |
| `compatibility.java_version` | string | ❌ | Java 版本范围，规则同上 |
| `compatibility.os` | list\<string\> | ❌ | 支持的 OS，取值 `linux` / `mac` / `windows` |
| `provides.skills/hooks/commands/mcp_servers/agents` | list\<string\> | ❌ | 白名单；**为空/未声明则放行所有** |
| `defaults.enabled` | bool | ❌ | 默认 `true`；设为 `false` 则插件被加载但不激活 |
| `defaults.modules.<name>` | bool | ❌ | 默认 `true`；用于细粒度关闭单个模块 |
| `requirements.binaries[].name` | string | ❌ | 可执行命令名，默认通过 `command -v` 探测 |
| `requirements.binaries[].check` | string | ❌ | 自定义 Shell 探测命令，返回码 0 视为通过 |
| `requirements.binaries[].install_hint` | string | ❌ | 失败时展示给用户的安装指引 |
| `requirements.env_vars` | list\<string\> | ❌ | 必需的环境变量名，不存在或为空即失败 |
| `requirements.files` | list\<string\> | ❌ | 必需文件，相对路径相对插件目录，绝对路径直接查找 |
| `dependencies[].name` / `.version` | string | ❌ | 依赖的其他插件（当前仅做版本校验） |

### 3.4 校验失败的行为

| 失败类型 | 行为 |
| --- | --- |
| 必需字段缺失 | 插件加载阶段抛 `IllegalArgumentException`，该插件被跳过 |
| 兼容性不匹配 | 日志 `WARN Plugin 'X' rejected: ...`，状态标记为 `rejected`，不激活 |
| `requirements` 不满足 | 日志 `WARN ... unmet requirements: ...`，该插件被跳过 |

---

## 4. 五种扩展点编写指南

### 4.1 Skills

**目录结构**：

```
skills/
└── java-expert/
    └── SKILL.md
```

**`SKILL.md` 格式**（YAML Front Matter + Markdown 正文）：

```markdown
---
name: java-expert
description: Java 语言与生态专家，擅长 JVM 调优与并发编程
version: 1.0.0
category: development
triggers:                 # 可选；未声明时由 name/description 自动生成
  - java
  - jvm
  - 并发
---

# Java Expert

你是一名资深 Java 工程师，精通...

## 核心能力
- JVM 内存模型
- 并发与锁优化
- ...
```

**注册行为**：`SkillModuleAdapter` 复用 `SkillLoader.loadSkillsFromDirectory` 扫描，被 `SkillRegistry` 接纳。

**⚠️ MVP 限制**：`SkillRegistry` 当前没有公开的 `unregister` 接口，`/plugin disable` 对 Skills 不即时生效，需重启会话。

### 4.2 Hooks

**目录结构**：

```
hooks/
└── auto-format-java.yaml
```

**文件示例**：

```yaml
name: auto-format-java
description: 写 Java 文件后自动 google-java-format
enabled: true
priority: 0

trigger:
  type: POST_TOOL_USE           # 可选值见 HookType 枚举
  tools:
    - WriteFile
    - EditFile
  file_patterns:                # 可选：glob 模式过滤
    - "*.java"

execution:
  type: script
  script: "google-java-format -i ${MODIFIED_FILE}"
  async: true
```

**触发类型**（`trigger.type`）：`PRE_TOOL_USE` / `POST_TOOL_USE` / `POST_TOOL_USE_FAILURE` / `PRE_AGENT_SWITCH` / `POST_AGENT_SWITCH` / `SUBAGENT_STOP` / `ON_ERROR` / `USER_PROMPT_SUBMIT` / `SESSION_START` / `SESSION_END` / `NOTIFICATION` / `STOP`。

**执行类型**（`execution.type`）：`script` / `agent` / `composite`，沿用 Jimi 原生 `HookSpec` 定义。

**支持即时 enable/disable**：`HookRegistry` 提供 `registerHook` / `unregisterHook`，`/plugin disable` 立即生效。

### 4.3 Commands（自定义斜杠命令）

**目录结构**：

```
commands/
└── qbuild.yaml
```

**文件示例**：

```yaml
name: qbuild
description: 快速构建（mvn clean install -DskipTests）
category: build
aliases:
  - qb
usage: "/qbuild"

execution:
  type: script
  script: "mvn clean install -DskipTests"
```

或 Prompt 类型命令（对齐 Claude Code 标准）：

```yaml
name: explain-code
description: 让 AI 解释当前选中的代码
prompt: |
  请逐行解释以下代码的作用，并指出潜在的风险点：

  $ARGUMENTS
```

**支持即时 enable/disable**：`CustomCommandRegistry` 提供 `registerCommand` / `unregisterCommand`。

### 4.4 MCP 服务器

**目录结构**：

```
mcp/
└── servers.json
```

**文件示例**：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"]
    },
    "fetch": {
      "url": "https://mcp.example.com/sse",
      "headers": {
        "Authorization": "Bearer ${MCP_TOKEN}"
      }
    }
  }
}
```

**⚠️ MVP 限制**：`McpModuleAdapter` 只做 **发现与登记**（写入 `ModuleLoadResult.getLoadedItems()`），真实注入到 `MCPToolProvider` 需要 `JimiFactory` 在创建 Engine 时聚合所有插件的 `mcp/` 目录——该集成是 P4 阶段工作。

### 4.5 Subagent

**目录结构**：

```
agents/
└── code-reviewer/
    ├── agent.yaml
    └── prompt.md               # 可选，system_prompt 可引用
```

**文件示例**：

```yaml
name: code-reviewer
description: 专注代码审查的子 Agent
system_prompt: prompt.md        # 也可直接写内联 prompt 字符串
tools:
  - ReadFile
  - WriteFile
  - Grep
```

**⚠️ MVP 限制**：同 MCP，`AgentModuleAdapter` 当前只做发现与登记，真实注入到 `AgentRegistry` 是 P4 阶段工作。

---

## 5. 插件的安装与分发

### 5.1 三种安装来源

`PluginInstaller` 支持统一入口 `install(source)`，自动根据来源格式分派：

| 来源格式 | 示例 | 行为 |
| --- | --- | --- |
| `owner/repo[/subpath]` | `leavesfly/jimi-java-kit` | 下载 `https://github.com/owner/repo/archive/refs/heads/main.zip` 并解压 |
| `http(s)://...zip` | `https://example.com/plugin.zip` | 直接下载 ZIP 归档 |
| 本地目录 | `/path/to/my-plugin/` | 直接复制目录 |
| 本地 `.zip` 文件 | `/path/to/plugin.zip` | 本地解压 |

### 5.2 安装流程

```
/plugin install <source>
         │
         ▼
┌─────────────────────┐
│ PluginInstaller     │
│ ├─ 分派来源类型     │
│ ├─ 下载/定位 ZIP    │
│ ├─ 解压到临时目录   │
│ ├─ 递归查找         │
│ │    plugin.yaml    │
│ ├─ 解析清单并 validate │
│ ├─ 复制到           │
│ │  ~/.jimi/plugins/<name>/│
│ └─ 清理临时文件     │
└─────────────────────┘
         │
         ▼
  PluginRegistry.reload()
         │
         ▼
   插件在当前会话立即生效
```

**安全保护**：

- **Zip Slip 防护**：`ArchiveInstaller.unzipFile` 检测到条目路径指向 `targetDir` 之外时抛 `IOException`
- **覆盖安装**：同名插件已存在时先删后装，保证目录状态干净
- **兼容性强校验**：`PluginLoader.passAllChecks` 在 reload 阶段会拒绝不兼容或依赖不满足的插件

### 5.3 发布插件到 GitHub

一个推荐的发布流程：

1. 在 GitHub 新建仓库 `jimi-plugin-<name>`
2. 在仓库根目录放置 `plugin.yaml` + 各扩展子目录
3. 在仓库 README 写清安装命令：
   ```bash
   /plugin install <owner>/<repo>
   ```
4. 使用 Git tags 管理版本（`plugin.yaml` 的 `version` 字段需要与 tag 对齐）

> 当前 P2 阶段只支持 main 分支下载；指定分支 / tag 的能力在后续迭代。

### 5.4 卸载

```
/plugin uninstall <name>
         │
         ▼
  PluginInstaller.uninstall(name)
         │
         ├─ 定位 ~/.jimi/plugins/<name>/
         ├─ 递归删除目录
         └─ 返回是否删除
         │
         ▼
  PluginRegistry.reload()
```

**注意**：`uninstall` 仅删除 **USER 层** 插件，不会触碰：

- `CLASSPATH` 层内置插件
- `PROJECT` 层项目级插件（需手工删除 `<project>/.jimi/plugins/<name>/`）

---

## 6. `/plugin` 命令参考

| 子命令 | 说明 | 示例 |
| --- | --- | --- |
| `/plugin` 或 `/plugin list` | 列出所有已注册插件及其状态 | `/plugin list` |
| `/plugin info <name>` | 查看插件详情（模块状态、白名单、兼容性） | `/plugin info hello-world` |
| `/plugin <name>` | `/plugin info <name>` 的便捷语法 | `/plugin hello-world` |
| `/plugin enable <name>` | 启用一个被禁用的插件 | `/plugin enable java-kit` |
| `/plugin disable <name>` | 禁用一个已启用的插件 | `/plugin disable auto-format` |
| `/plugin reload` | 重新加载所有插件 | `/plugin reload` |
| `/plugin install <source>` | 从 GitHub / URL / 本地路径安装插件 | `/plugin install owner/repo` |
| `/plugin uninstall <name>` | 卸载用户级插件（`remove` 为别名） | `/plugin uninstall old-kit` |

### 6.1 `/plugin list` 输出示例

```
✓ 插件列表 (共 3 个, 启用 2, 禁用 1, 拒绝 0)

  ✅ hello-world              v1.0.0    [USER]    - 示例插件
  ✅ java-dev-kit             v1.2.0    [USER]    - Java 开发者全套扩展
  ⏸️  experimental-agent       v0.0.3    [USER]    - 实验性 Agent
      └─ 已禁用

→ 使用 '/plugin info <name>' 查看插件详情
```

### 6.2 `/plugin info` 输出示例

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✓ 插件详情: java-dev-kit
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📝 基本信息:
  名称:       java-dev-kit
  版本:       1.2.0
  描述:       Java 开发者全套扩展
  作用域:     USER
  作者:       jimi-team
  许可:       Apache-2.0
  安装路径:   /Users/xxx/.jimi/plugins/java-dev-kit
  状态:       ✅ 已启用

🔧 兼容性:
  Jimi 版本:  >=0.1.0,<1.0.0
  Java 版本:  >=17

🧩 扩展点白名单:
  Skills   : java-expert, spring-boot-helper
  Hooks    : auto-format-java
  Commands : qbuild, qtest
  MCP      : (未使用)
  Agents   : (未使用)

📦 加载结果:
  ✅ skills     - 2 个
  ✅ hooks      - 1 个
  ✅ commands   - 2 个
```

---

## 7. 本地开发与调试

### 7.1 在 IDE 中开发插件

推荐在 Jimi 仓库外独立开发：

```bash
mkdir -p ~/my-jimi-plugin
cd ~/my-jimi-plugin
cat > plugin.yaml <<EOF
name: my-plugin
version: 0.1.0
description: 我的第一个插件
EOF
```

然后通过本地路径安装：

```
/plugin install ~/my-jimi-plugin
```

每次修改后使用 `/plugin install ~/my-jimi-plugin` 覆盖安装 + 自动 reload，无需重启 Jimi。

### 7.2 项目级插件（团队共享）

在项目根目录创建 `.jimi/plugins/<name>/`，提交到版本控制即可：

```
my-project/
├── .jimi/
│   └── plugins/
│       └── team-conventions/
│           ├── plugin.yaml
│           ├── hooks/
│           │   └── pre-commit.yaml
│           └── commands/
│               └── ship.yaml
├── src/
└── pom.xml
```

团队成员克隆仓库后打开 Jimi 会话时，`JimiFactory` 会自动调用 `PluginRegistry.loadProjectPlugins(projectDir)` 加载项目级插件。

### 7.3 单元测试你的插件

可以直接调用 `PluginLoader` 与对应 Adapter 做 smoke test：

```java
@Test
void myPluginLoads() throws Exception {
    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    PluginLoader loader = new PluginLoader();
    ReflectionTestUtils.setField(loader, "yamlObjectMapper", yamlMapper);

    PluginSpec spec = loader.parsePluginManifest(
            Paths.get("path/to/my-plugin"), PluginScope.USER);

    assertEquals("my-plugin", spec.getName());
}
```

更完整的 fixture 示例见 `src/test/resources/plugins/hello-world/`。

### 7.4 日志排查

启动 Jimi 后，关键日志前缀：

| 前缀 | 含义 |
| --- | --- |
| `PluginLoader` | 插件发现、清单解析、兼容性/依赖校验 |
| `PluginDispatcher` | 模块分发顺序、Adapter 跳过原因 |
| `<Xxx>ModuleAdapter` | 单模块加载数量、白名单过滤 |
| `PluginRegistry` | 启动加载汇总、enable/disable/reload |

调试建议：在 `application.yaml` 临时调高日志级别

```yaml
logging:
  level:
    io.leavesfly.jimi.plugin: DEBUG
```

---

## 8. 常见问题 FAQ

### Q1: 插件被加载但状态是 "已拒绝"？

查看日志中的 `WARN Plugin 'X' rejected: ...`，常见原因：

- `compatibility.jimi_version` 不匹配当前运行版本
- `requirements.binaries` 声明的命令在 PATH 中找不到
- `requirements.env_vars` 中的环境变量未设置
- `compatibility.os` 不包含当前系统

### Q2: `provides` 白名单为什么空着？

`provides` 为空或未声明时**视为全放行**。显式声明 `provides` 的价值：

- **安全审计**：维护者可以一眼看出这个插件对外暴露的所有扩展项
- **精确控制**：不想暴露的 Skill / Hook 即使在目录里也不会被注册

### Q3: 为什么 `/plugin disable` 后 Skills 还在生效？

`SkillRegistry` 当前没有 `unregister` 接口（MVP 限制）。下列扩展点支持即时 disable：

- ✅ Hooks（`HookRegistry.unregisterHook`）
- ✅ Commands（`CustomCommandRegistry.unregisterCommand`）
- ⚠️ Skills（需要重启会话）
- ⚠️ MCP / Agents（MVP 阶段仅发现登记，未真正注入）

### Q4: 同名插件被多次安装会怎样？

覆盖安装：先 `deleteDirectory(~/.jimi/plugins/<name>)`，再复制新版本。清单中的 `version` 字段仅作展示，不强制要求递增。

### Q5: 插件间依赖（`dependencies`）生效吗？

P2 阶段 **仅做版本范围校验**（对应字段被读取进 `PluginSpec.dependencies`），不做依赖的拓扑排序或传递性安装。后续迭代计划在插件市场（P3）引入自动依赖解析。

### Q6: 插件能修改 Jimi 的核心行为吗？

不能。设计上的硬约束：

- 插件不能注入 `@Service` / `@Component` Bean
- 插件不能调用 Jimi 内部类的私有 API
- 插件只能通过五种扩展点（Skills/Hooks/Commands/MCP/Agents）来影响运行时

如果需要更深层的扩展（比如自定义工具 `Tool`），请考虑直接 fork Jimi 或发 RFC 讨论。

### Q7: 插件系统本身的测试怎么跑？

```bash
# 仅跑 plugin 包
mvn test -Dtest='io.leavesfly.jimi.plugin.**'

# 预期输出：Tests run: 93, Failures: 0, Errors: 0, Skipped: 0
```

fixture 位于 `src/test/resources/plugins/hello-world/`，涵盖 5 种扩展点的最小合法样例。

---

## 附录: 相关源码导航

| 关注点 | 类路径 |
| --- | --- |
| 清单模型 | `io.leavesfly.jimi.plugin.spec.*` |
| 扫描与校验 | `io.leavesfly.jimi.plugin.PluginLoader` |
| 分发器 | `io.leavesfly.jimi.plugin.dispatcher.PluginDispatcher` |
| 5 个 Adapter | `io.leavesfly.jimi.plugin.dispatcher.{Skill,Hook,Command,Mcp,Agent}ModuleAdapter` |
| 门面 | `io.leavesfly.jimi.plugin.PluginRegistry` |
| 安装器 | `io.leavesfly.jimi.plugin.installer.{PluginInstaller,ArchiveInstaller}` |
| CLI 命令 | `io.leavesfly.jimi.plugin.command.PluginCommandHandler` |
| 版本范围工具 | `io.leavesfly.jimi.plugin.util.VersionRange` |

---

**文档版本**：对齐 P0+P1+P2（MVP 完整）
**最后更新**：2026-05-03
