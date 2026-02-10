# Jimi ADK Demo

演示如何使用 Jimi ADK 构建 AI Agent 应用。

## 示例列表

### 1. QuickStartDemo - 快速入门
最简单的 Agent 应用，展示基本的创建和运行流程。

**运行方式：**
```bash
cd jimi2/jimi-adk-demo
export OPENAI_API_KEY=your_api_key
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.QuickStartDemo"
```

**核心步骤：**
- 创建 Agent（配置名称、描述、系统提示词）
- 创建 LLM（指定模型和 API Key）
- 构建 JimiRuntime（统一组装运行时环境）
- 执行对话

---

### 2. ToolDemo - 工具使用
演示如何创建和注册自定义工具，让 Agent 能够调用外部能力。

**运行方式：**
```bash
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.ToolDemo"
```

**核心内容：**
- 实现 `Tool` 接口定义工具
- 配置工具的参数类型和执行逻辑
- 将工具注册到 Agent
- Agent 自动选择并调用工具

**示例工具：**
- `get_weather` - 查询城市天气
- `get_current_time` - 获取系统时间

---

### 3. MultiTurnConversationDemo - 多轮对话
演示如何利用 Context 进行多轮对话，Agent 能够记住上下文。

**运行方式：**
```bash
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.MultiTurnConversationDemo"
```

**核心内容：**
- 使用 `Context` 管理对话历史
- 多轮对话中的上下文保持
- 查看完整对话历史

---

### 4. ConfigDemo - 配置管理
演示如何通过配置管理 Agent 和 LLM 的各种参数。

**运行方式：**
```bash
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.ConfigDemo"
```

**核心内容：**
- 通过 `LLMConfig` 配置模型参数（温度、最大token等）
- 管理 Agent 的系统提示词和元数据
- 工作目录的隔离管理

---

### 5. ContextDemo - 上下文持久化
演示如何保存和恢复对话上下文，实现会话延续。

**运行方式：**
```bash
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.ContextDemo"
```

**核心内容：**
- 使用 `PersistableContext` 保存对话状态
- 从文件恢复上下文
- 实现跨会话的记忆能力

---

### 6. LLMProviderDemo - 多种LLM提供商
演示如何切换不同的 LLM 提供商。

**运行方式：**
```bash
# 设置多个提供商的 API Key
export OPENAI_API_KEY=sk-xxx
export DEEPSEEK_API_KEY=sk-xxx
export KIMI_API_KEY=sk-xxx
export QWEN_API_KEY=sk-xxx

mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.LLMProviderDemo"
```

**支持的提供商：**
- OpenAI (gpt-4o-mini)
- DeepSeek (deepseek-chat)
- Kimi (moonshot-v1-8k)
- 通义千问 (qwen-plus)

---

### 7. AsyncDemo - 异步执行与并发
演示如何使用 Reactor 进行异步执行、并发处理和响应式编程。

**运行方式：**
```bash
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.AsyncDemo"
```

**核心内容：**
- 顺序执行 (`concatMap`)
- 并行执行 (`flatMap` + `Schedulers`)
- 超时控制 (`timeout`)
- 重试机制 (`retry`)

---

### 8. SessionDemo - 会话管理
演示如何创建和管理会话，实现用户隔离和状态跟踪。

**运行方式：**
```bash
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.SessionDemo"
```

**核心内容：**
- 创建用户会话 (`Session`)
- 多用户隔离
- 会话状态检查
- 会话活动更新

---

### 9. ErrorHandlingDemo - 错误处理
演示如何处理各种错误情况，包括网络错误、API 限制、超时等。

**运行方式：**
```bash
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.ErrorHandlingDemo"
```

**核心内容：**
- 基本错误处理 (`onErrorResume`)
- 降级处理 (Fallback)
- 指数退避重试 (`retryWhen`)
- 错误恢复策略

---

### 10. MultiAgentDemo - 多 Agent 协作
演示如何让多个 Agent 协作完成复杂任务。

**运行方式：**
```bash
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.MultiAgentDemo"
```

**核心内容：**
- 任务分解与协作
- 专家咨询模式
- 结果汇总与整合
- 多角色 Agent 设计

---

### 11. SkillDemo - Skill 系统
演示 Skill 系统的核心功能，包括 Skill 定义、加载、匹配、注入和依赖管理。

**运行方式：**
```bash
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.SkillDemo"
```

**核心内容：**
- Skill 定义与注册 (`SkillSpec`)
- 关键词匹配 (`SkillMatcher`)
- 内容注入 (`SkillInjector`)
- 依赖管理 (`dependencies`)
- 组合 Skill

**示例 Skill：**
- `code-review` - 代码审查指南
- `unit-testing` - 单元测试指南
- `refactoring` - 重构指南（依赖 code-review）
- `fullstack-dev` - 全栈开发工作流（组合 Skill）

---

## 快速开始

### 1. 环境准备

**必需：**
- Java 17+
- Maven 3.6+
- OpenAI API Key（或兼容的 LLM API）

**设置环境变量：**
```bash
export OPENAI_API_KEY=sk-xxx
```

### 2. 编译项目

```bash
cd jimi2
mvn clean install -DskipTests
```

### 3. 运行示例

```bash
cd jimi-adk-demo
mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.QuickStartDemo"
```

---

## 核心概念

### Agent（智能体）
- 代表一个 AI 助手的配置
- 包含：名称、描述、系统提示词、工具列表
- 通过 `Agent.builder()` 创建

### LLM（大语言模型）
- 提供 AI 推理能力
- 支持 OpenAI、DeepSeek、Kimi、Qwen 等兼容 API
- 通过 `LLMFactory.create(LLMConfig)` 创建

### JimiRuntime（运行时）
- 统一组装 Engine、Context、ToolRegistry、Wire 等核心组件
- 提供完整的 Agent 运行环境
- 简化了组件初始化和依赖注入

### Tool（工具）
- Agent 可调用的外部能力
- 需实现 `Tool<P>` 接口（泛型参数）
- 定义工具名称、描述、参数类型和执行逻辑

### Context（上下文）
- 管理对话历史
- 支持多轮对话
- 通过 `PersistableContext` 实现持久化

---

## 项目结构

```
jimi-adk-demo/
├── pom.xml                          # Maven 配置
├── README.md                        # 本文档
└── src/main/
    ├── java/io/leavesfly/jimi/adk/demo/
    │   ├── QuickStartDemo.java      # 快速入门示例
    │   ├── ToolDemo.java            # 工具使用示例
    │   ├── MultiTurnConversationDemo.java  # 多轮对话示例
    │   ├── ConfigDemo.java          # 配置管理示例
    │   ├── ContextDemo.java         # 上下文持久化示例
    │   ├── LLMProviderDemo.java     # 多LLM提供商示例
    │   ├── AsyncDemo.java           # 异步执行示例
    │   ├── SessionDemo.java         # 会话管理示例
    │   ├── ErrorHandlingDemo.java   # 错误处理示例
    │   ├── MultiAgentDemo.java      # 多Agent协作示例
    │   └── SkillDemo.java           # Skill系统示例
    └── resources/
        └── logback.xml              # 日志配置
```

---

## 进阶扩展

### 1. 切换 LLM 提供商

支持 DeepSeek、Kimi、Qwen 等兼容 OpenAI API 的模型：

```java
LLMConfig llmConfig = LLMConfig.builder()
        .provider("deepseek")           // 提供商
        .model("deepseek-chat")         // 模型
        .apiKey(apiKey)                 // API Key
        .baseUrl("https://api.deepseek.com/v1")  // 基础 URL（可选）
        .build();
```

### 2. 自动加载工具

通过 SPI 机制自动发现和加载工具：

```java
JimiRuntime runtime = JimiRuntime.builder()
    .agent(agent)
    .llm(llm)
    .workDir(workDir)
    .autoLoadTools(true)  // 启用自动工具加载
    .build();
```

---

## 常见问题

### Q1: 运行时提示找不到 API Key
**A:** 确保已设置环境变量 `OPENAI_API_KEY`：
```bash
export OPENAI_API_KEY=sk-xxx
```

### Q2: 编译失败
**A:** 确保先编译整个 jimi-adk：
```bash
cd jimi2
mvn clean install
```

### Q3: 如何查看详细日志？
**A:** 修改 `src/main/resources/logback.xml`，将日志级别改为 `DEBUG`。

---

## 参考资料

- [Jimi ADK 架构文档](../../docs/ARCHITECTURE_OVERVIEW.md)
- [更多示例](../jimi-cli)
