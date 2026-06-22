# Jimi 技术 Wiki

> **Jimi** —— 纯 Java 实现的 AI 驱动智能代理系统，为 Java 程序员打造的开源 ClaudeCode 体验。

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.10-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](../LICENSE)

本 Wiki 对 Jimi 项目的**技术架构、核心模块、扩展机制**做系统性整理，目标读者包括：

- 想理解一个"Java 版 AI CLI Agent"是如何从零搭起来的**开发者**
- 想在 Jimi 基础上二次开发、接入新工具 / 新模型 / 新技能的**扩展者**
- 想借鉴 Jimi 架构来实现自己 AI Agent 产品的**架构师**

---

## 📚 Wiki 目录

### 入门篇

- **[01 · 项目概述与快速开始](01-项目概述与快速开始.md)**
  项目定位、核心能力、技术栈全景、安装运行与 5 分钟上手。

### 架构篇

- **[02 · 系统架构与核心引擎](02-系统架构与核心引擎.md)**
  分层架构、`JimiEngine` / `AgentExecutor` / `ReactLoop` 三层协作、整体数据流转。

- **[03 · Agent 多智能体系统](03-Agent多智能体系统.md)**
  `Agent` / `AgentSpec` / `AgentRegistry`、内置 6 大专业 Agent、YAML 自定义 Agent、异步 Subagent、Agent Team。

- **[04 · 工具系统与 ToolRegistry](04-工具系统与ToolRegistry.md)**
  `Tool` SPI、`ToolProvider`、`ToolRegistry`、18 个内置工具 + MCP 动态工具、并发执行、JSON Schema 自动生成。

- **[05 · LLM 接入层与多模型支持](05-LLM接入层与多模型支持.md)**
  `ChatProvider` 抽象、OpenAI 兼容 / Kimi / Cursor、流式响应、Caffeine 缓存、Token 估算与限流。

### 知识增强篇

- **[06 · Skills 技能包系统](06-Skills技能包系统.md)**
  技能包规范、作用域（全局 / 项目）、触发词匹配、渐进式披露、SkillRegistry 与 SkillsTool。

- **[07 · Hooks 自动化系统](07-Hooks自动化系统.md)**
  15 种 Hook 事件（Claude Code 对齐 9 + Jimi 扩展 3 + Agent Teams 扩展 3）、触发器 / 条件 / 执行器、典型自动化场景。

- **[08 · 代码图谱与 RAG 检索](08-代码图谱与RAG检索.md)**
  JavaParser AST 解析、`GraphManager`、混合检索（图 + 向量）、影响分析、`RagManager` 与 Embedding Provider。

- **[10 · 记忆管理与会话机制](10-记忆管理与会话机制.md)**
  三层记忆架构（MEMORY.md / Topic / Session）、ReCAP 压缩、`SessionManager`、`Approval` 审批与 YOLO 模式。

### 交互与集成篇

- **[09 · 自定义命令与 CLI 交互](09-自定义命令与CLI交互.md)**
  元命令注册、4 种执行类型（script / agent / composite / prompt）、JLine Shell UI、Wire 消息总线。

- **[11 · MCP 协议集成](11-MCP协议集成.md)**
  Model Context Protocol 客户端、stdio / http 两种传输、MCPTool 动态加载、JSON-RPC 协议栈。

### 扩展篇

- **[12 · 扩展开发指南](12-扩展开发指南.md)**
  自定义 Tool / Agent / Skill / Hook / Command 的完整流程与示例，含调试技巧。

- **[13 · 插件系统与扩展分发](13-插件系统与扩展分发.md)**
  `PluginRegistry` / `PluginLoader` / `PluginDispatcher` 三件套、5 种 `PluginModuleAdapter`、`PluginInstaller` 原子切换、`/plugin` 命令全集、`VersionRange` 与 doctor 诊断。

### 自动化篇

- **[14 · Loop Engineering 循环工程](14-Loop Engineering循环工程.md)**
  `/loop` 定时调度、`/goal` 目标驱动迭代、`GoalVerifier` Maker/Checker 分离、`WorktreeManager` 并行隔离、`LoopStateManager` 状态持久化。

---

## 🗺️ 速查表

### 核心模块与源码位置

| 模块 | 包路径 | 关键类 |
|------|--------|--------|
| CLI 入口 | `io.leavesfly.jimi` | `CliApplication`、`JimiApplication` |
| 核心引擎 | `io.leavesfly.jimi.core` | `JimiEngine`、`JimiFactory` |
| ReAct 循环 | `io.leavesfly.jimi.core.engine` | `AgentExecutor`、`ReactLoop`、`JimiRuntime` |
| Agent 系统 | `io.leavesfly.jimi.core.agent` | `Agent`、`AgentSpec`、`AgentRegistry` |
| 工具系统 | `io.leavesfly.jimi.tool` | `Tool`、`ToolRegistry`、`ToolProvider` |
| LLM 层 | `io.leavesfly.jimi.llm` | `LLM`、`ChatProvider`、`LLMFactory` |
| Skills | `io.leavesfly.jimi.skill` | `SkillSpec`、`SkillRegistry`、`SkillLoader` |
| Hooks | `io.leavesfly.jimi.core.hook` | `HookSpec`、`HookType`、`HookRegistry`、`HookExecutor` |
| 插件系统 | `io.leavesfly.jimi.plugin` | `PluginRegistry`、`PluginLoader`、`PluginDispatcher`、`PluginInstaller`、`PluginModuleAdapter` |
| 代码图谱 | `io.leavesfly.jimi.knowledge.graph` | `GraphManager`、`GraphSearchEngine` |
| RAG | `io.leavesfly.jimi.knowledge.rag` | `RagManager`、`InMemoryVectorStore`、`EmbeddingProvider` |
| 记忆系统 | `io.leavesfly.jimi.memory` | `MemoryManager`、`MemoryStore`、`MemoryExtractor` |
| MCP | `io.leavesfly.jimi.mcp` | `JsonRpcClient`、`MCPConfig`、`StdIoJsonRpcClient` |
| 命令系统 | `io.leavesfly.jimi.command` | `CommandRegistry`、`CommandHandler`、`CustomCommandSpec` |
| Loop Engineering | `io.leavesfly.jimi.core.loop` | `LoopManager`、`GoalVerifier`、`LoopStateManager`、`WorktreeManager` |
| UI | `io.leavesfly.jimi.ui.shell` | `ShellUI` |

### 配置目录约定

| 目录 | 用途 | 优先级 |
|------|------|--------|
| `src/main/resources/agents/` | 内置 Agent 规范（打包在 jar 内） | 低 |
| `~/.jimi/agents/` | 用户级自定义 Agent | 中 |
| `<project>/.jimi/agents/` | 项目级自定义 Agent | 高 |
| `~/.jimi/skills/` | 用户级技能包 | 中 |
| `<project>/.jimi/skills/` | 项目级技能包 | 高（覆盖全局同名） |
| `~/.jimi/hooks/` | 用户级 Hooks | 中 |
| `<project>/.jimi/hooks/` | 项目级 Hooks | 高 |
| `~/.jimi/commands/` | 用户级自定义命令 | 中 |
| `<project>/.jimi/commands/` | 项目级自定义命令 | 高 |
| `~/.jimi/plugins/<name>/plugin.yaml` | 用户级插件（打包 Skills/Hooks/Commands/MCP/Agents） | 中 |
| `<project>/.jimi/plugins/<name>/plugin.yaml` | 项目级插件 | 高 |
| `~/.jimi/config.yml` | LLM / 全局配置（YAML，后缀 `.yml`） | - |
| `~/.jimi/metadata.json` | 工作目录 → 历史 session 映射 | - |
| `~/.jimi/sessions/<hash>/<uuid>.jsonl` | 会话历史（按 workDir 哈希分桶，见 `WorkDirMetadata#getSessionsDir`） | - |
| `~/.jimi/memory/` | 长期记忆（`MEMORY.md` + Topic 文件） | - |

---

## 📖 外部参考文档

本 Wiki 与仓库 `docs/` 目录下的"用户视角"文档互补：

| 已有文档 | 定位 |
|----------|------|
| [README](../README.md) | 项目门面 |
| [用户使用指南](../用户使用指南.md) | 完整用户手册 |
| [docs/HOOKS.md](../docs/HOOKS.md) | Hooks 用户指南 |
| [docs/GRAPH_GUIDE.md](../docs/GRAPH_GUIDE.md) | 代码图谱使用 |
| [docs/CUSTOM_COMMANDS.md](../docs/CUSTOM_COMMANDS.md) | 自定义命令使用 |
| [docs/RAG配置指南.md](../docs/RAG配置指南.md) | RAG 配置 |
| [docs/TECHNICAL_ARCHITECTURE.md](../docs/TECHNICAL_ARCHITECTURE.md) | 完整技术架构 |
| [docs/PLUGIN_DEVELOPMENT.md](../docs/PLUGIN_DEVELOPMENT.md) | 插件开发用户手册（与本 Wiki 第 13 篇互补） |

本 Wiki 则更聚焦"**开发者视角**"，贴近源码，用于**理解机制、定位代码、二次扩展**。

---

<div align="center">

Made with ❤️ for Java Developers who love AI.

</div>
