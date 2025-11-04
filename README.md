# Jimi ：你的AI生产工具

<div align="center">

一个强大的 Java CLI 智能代理，专为软件开发任务和终端操作而设计

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.9+-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

## 📖 目录

- [项目简介](#-项目简介)
- [核心功能特性](#-核心功能特性)
- [快速开始](#-快速开始)
- [使用说明](#-使用说明)
- [目录结构](#-目录结构)
- [自定义扩展](#-自定义扩展)
- [常见问题](#-常见问题)
- [贡献指南](#-贡献指南)

---

## 🚀 项目简介

Jimi 是一个基于 Java 17 和 Spring Boot 3 构建的智能 CLI 代理，旨在通过集成大语言模型（LLM）来辅助软件开发工作流程。它提供了交互式 Shell 模式和批处理模式，支持文件操作、代码分析、Shell 执行、Web 搜索等多种工具，并通过 Agent 系统实现任务的智能化处理。

### 设计理念

- **模块化架构**：核心模块清晰分离，易于扩展和维护
- **响应式编程**：基于 Project Reactor 实现异步非阻塞操作
- **智能协作**：支持主 Agent 和多个 Subagent 协同工作
- **安全可控**：内置审批机制，危险操作需用户确认
- **协议集成**：支持 MCP（Model Context Protocol）和 ACP（Agent Client Protocol）

---

## ✨ 核心功能特性

### 🎯 智能核心

- **JimiSoul**：智能主控循环，协调 LLM、工具调用和用户交互
- **上下文管理**：自动压缩上下文，支持检查点保存与恢复
- **D-Mail 机制**：时间旅行式错误回滚能力，快速恢复到历史状态

### 🔧 工具系统

**文件操作工具**
- `ReadFile` - 读取文件内容
- `WriteFile` - 写入文件（需审批）
- `StrReplaceFile` - 字符串替换（需审批）
- `PatchFile` - 应用补丁
- `Glob` - 文件模式匹配
- `Grep` - 正则搜索

**执行工具**
- `Bash` - 执行 Shell 命令（需审批）

**网络工具**
- `SearchWeb` - Web 搜索
- `FetchURL` - 获取网页内容

**辅助工具**
- `Think` - 结构化思考
- `Todo` - 任务列表管理
- `Task` - 委托任务给 Subagent

### 🤖 Agent 系统

- **Default Agent**：通用开发助手
- **Build Agent**：专注于项目构建和编译
- **Test Agent**：专注于测试执行和分析
- **Debug Agent**：专注于调试和错误修复
- **Research Agent**：专注于技术调研和信息搜索

支持自定义 Agent 配置，实现专业化分工和上下文隔离。

### 🔌 协议集成

- **MCP（Model Context Protocol）**：集成外部工具和服务
- **ACP（Agent Client Protocol）**：支持 IDE 集成

### 🛡️ 安全机制

- **审批机制**：危险操作（文件写入、命令执行）需用户确认
- **YOLO 模式**：可选的自动批准模式，适合可信环境

### 💬 交互模式

- **Shell 模式**：交互式命令行界面，支持元命令
- **Print 模式**：批处理模式，执行单次命令并输出结果
- **会话管理**：支持保存和恢复历史会话

---

## 🏁 快速开始

### 环境要求

- ✅ **Java 17** 或更高版本
- ✅ **Maven 3.9+**（仅构建时需要）
- ✅ **LLM API Key**（Moonshot/OpenAI/DeepSeek/Qwen）

### 1. 克隆项目

```bash
git clone https://github.com/your-org/jimi.git
cd jimi
```

### 2. 构建项目

```bash
# 使用 Maven 构建
mvn clean package

# 或使用 Makefile（推荐）
make build
```

构建成功后会生成 `target/jimi-0.1.0.jar`（约 27MB）。

### 3. 配置

#### 创建配置目录

```bash
mkdir -p ~/.config/jimi
```

#### 复制配置模板

```bash
cp src/main/resources/config-template.yaml ~/.config/jimi/config.yaml
cp src/main/resources/providers-config-examples.yaml ~/.config/jimi/providers.yaml
```

#### 设置 API Key

选择一个 LLM 提供商并设置环境变量：

```bash
# Moonshot (推荐)
export MOONSHOT_API_KEY="sk-your-api-key-here"

# 或 OpenAI
export OPENAI_API_KEY="sk-your-api-key-here"

# 或 DeepSeek
export DEEPSEEK_API_KEY="sk-your-api-key-here"

# 或 Qwen
export QWEN_API_KEY="sk-your-api-key-here"
```

### 4. 运行

```bash
# 查看版本
./jimi --version

# 显示帮助
./jimi --help

# 启动交互式 Shell
./jimi -w /path/to/your/project

# 执行单次命令
./jimi -w /path/to/your/project -c "分析项目结构"

# 继续上一个会话
./jimi -w /path/to/your/project -C
```

### 5. 系统安装（可选）

```bash
# 安装到 ~/.local/bin
./script/deploy.sh

# 或使用 Makefile
make install

# 安装后可直接使用
jimi --help
jimi -w /path/to/your/project
```

---

## 📚 使用说明

### 命令行参数

```
Usage: jimi [OPTIONS]

核心选项:
  -w, --work-dir PATH      工作目录（必填，默认当前目录）
  -c, --command TEXT       执行单次命令（Print 模式）
  -C, --continue           继续上一个会话

模型配置:
  -m, --model MODEL        指定模型（如 moonshot-v1-32k）
  --agent-file PATH        自定义 Agent 配置文件

MCP 集成:
  --mcp-config-file PATH   MCP 配置文件（可多次指定）

行为控制:
  -y, --yolo, --yes        自动批准所有操作（危险）
  --verbose                详细输出
  --debug                  调试日志

帮助:
  -h, --help               显示帮助
  -V, --version            显示版本
```

### Shell 模式元命令

在交互式 Shell 中，可以使用以下元命令：

| 命令 | 描述 |
|------|------|
| `/help` | 显示所有可用命令 |
| `/config` | 查看当前配置信息 |
| `/tools` | 查看所有可用工具 |
| `/status` | 查看会话状态 |
| `/history` | 查看对话历史 |
| `/init` | 初始化项目上下文 |
| `/compact` | 手动压缩上下文 |
| `/clear` | 清空屏幕 |
| `/reset` | 重置会话 |
| `/version` | 显示版本信息 |
| `exit` 或 `quit` | 退出 Shell |

### 配置文件说明

#### 主配置文件 (`~/.config/jimi/config.yaml`)

```yaml
# 循环控制配置
loop_control:
  max_steps_per_run: 50           # 每次运行的最大步数
  max_retries_per_step: 3         # 每步的最大重试次数
  max_total_llm_requests: 100     # 总 LLM 请求上限

# 会话配置
session:
  id: default                     # 会话 ID
  work_dir: .                     # 工作目录
  history_file: .jimi_history.jsonl  # 历史文件
```

#### LLM 提供商配置 (`~/.config/jimi/providers.yaml`)

```yaml
llm:
  providers:
    moonshot:
      api_key: "${MOONSHOT_API_KEY}"  # 使用环境变量
      base_url: "https://api.moonshot.cn/v1"
      models:
        moonshot-v1-8k:
          context_size: 8192
        moonshot-v1-32k:
          context_size: 32768
        moonshot-v1-128k:
          context_size: 131072
    
    openai:
      api_key: "${OPENAI_API_KEY}"
      base_url: "https://api.openai.com/v1"
      models:
        gpt-4:
          context_size: 8192
        gpt-4-turbo:
          context_size: 128000
```

### 使用示例

#### 示例 1：分析项目结构

```bash
./jimi -w ~/my-java-project -c "分析这个 Maven 项目的结构和依赖关系"
```

#### 示例 2：修复编译错误

```bash
./jimi -w ~/my-project -c "构建项目并修复所有编译错误"
```

#### 示例 3：交互式开发

```bash
# 启动 Shell
./jimi -w ~/my-project

# 在 Shell 中进行对话
> 分析 src/main/java 下的代码质量
> 找出所有未使用的导入
> 重构这个方法，提高可读性
```

#### 示例 4：使用特定模型

```bash
./jimi -w ~/my-project -m moonshot-v1-128k -c "深度分析整个项目架构"
```

#### 示例 5：YOLO 模式（自动批准）

```bash
./jimi -w ~/my-project -y -c "运行所有测试并修复失败的用例"
```

---

## 📂 目录结构

```
jimi/
├── doc/                          # 文档目录
│   ├── QUICKSTART.md            # 快速入门
│   ├── RUNNING.md               # 运行指南
│   └── MCP_LOCAL_IMPLEMENTATION.md  # MCP 实现文档
│
├── script/                       # 脚本目录
│   ├── Makefile                 # Make 构建脚本
│   ├── deploy.sh                # 部署脚本
│   └── jimi.bat                 # Windows 启动脚本
│
├── src/
│   ├── main/
│   │   ├── java/io/leavesfly/jimi/
│   │   │   ├── agent/           # Agent 系统
│   │   │   │   ├── Agent.java
│   │   │   │   ├── AgentSpec.java
│   │   │   │   ├── AgentSpecLoader.java
│   │   │   │   ├── ResolvedAgentSpec.java
│   │   │   │   └── SubagentSpec.java
│   │   │   │
│   │   │   ├── cli/             # 命令行入口
│   │   │   │   └── CliApplication.java
│   │   │   │
│   │   │   ├── command/         # 元命令系统
│   │   │   │   ├── handlers/    # 命令处理器
│   │   │   │   ├── CommandContext.java
│   │   │   │   ├── CommandHandler.java
│   │   │   │   └── CommandRegistry.java
│   │   │   │
│   │   │   ├── config/          # 配置系统
│   │   │   │   ├── ConfigLoader.java
│   │   │   │   ├── JimiConfig.java
│   │   │   │   └── LLMProviderConfig.java
│   │   │   │
│   │   │   ├── exception/       # 异常定义
│   │   │   │
│   │   │   ├── llm/             # LLM 通信层
│   │   │   │   ├── message/     # 消息模型
│   │   │   │   ├── provider/    # LLM 提供商实现
│   │   │   │   ├── LLM.java
│   │   │   │   └── LLMFactory.java
│   │   │   │
│   │   │   ├── session/         # 会话管理
│   │   │   │   ├── Session.java
│   │   │   │   ├── SessionManager.java
│   │   │   │   └── WorkDirMetadata.java
│   │   │   │
│   │   │   ├── soul/            # 智能核心
│   │   │   │   ├── approval/    # 审批机制
│   │   │   │   ├── compaction/  # 上下文压缩
│   │   │   │   ├── runtime/     # 运行时上下文
│   │   │   │   ├── Context.java
│   │   │   │   ├── JimiSoul.java
│   │   │   │   └── Soul.java
│   │   │   │
│   │   │   ├── tool/            # 工具系统
│   │   │   │   ├── bash/        # Bash 工具
│   │   │   │   ├── file/        # 文件操作工具
│   │   │   │   ├── mcp/         # MCP 集成工具
│   │   │   │   ├── task/        # 任务工具
│   │   │   │   ├── think/       # 思考工具
│   │   │   │   ├── todo/        # 待办工具
│   │   │   │   ├── web/         # Web 工具
│   │   │   │   ├── Tool.java
│   │   │   │   ├── ToolRegistry.java
│   │   │   │   └── ToolResult.java
│   │   │   │
│   │   │   ├── ui/              # 用户界面
│   │   │   │   ├── shell/       # Shell 界面
│   │   │   │   └── visualization/  # 可视化组件
│   │   │   │
│   │   │   ├── wire/            # 消息传输
│   │   │   │   ├── message/     # 消息类型
│   │   │   │   ├── Wire.java
│   │   │   │   └── WireImpl.java
│   │   │   │
│   │   │   ├── JimiApplication.java  # 主启动类
│   │   │   └── JimiFactory.java      # 工厂类
│   │   │
│   │   └── resources/
│   │       ├── agents/          # Agent 配置
│   │       │   ├── default/     # 默认 Agent
│   │       │   ├── build/       # 构建 Agent
│   │       │   ├── test/        # 测试 Agent
│   │       │   ├── debug/       # 调试 Agent
│   │       │   ├── research/    # 研究 Agent
│   │       │   └── SUBAGENTS_USAGE.md
│   │       ├── application.yml  # Spring Boot 配置
│   │       ├── config-template.yaml  # 配置模板
│   │       └── providers-config-examples.yaml
│   │
│   └── test/                     # 测试代码
│
├── pom.xml                       # Maven 配置
└── README.md                     # 项目说明
```

### 核心模块说明

#### agent 模块
负责 Agent 的定义、加载和解析。支持主 Agent 和 Subagent 的层级结构。

#### soul 模块
智能核心，包含：
- `JimiSoul`：主控循环，协调 LLM 和工具
- 上下文管理器：自动压缩和检查点
- 审批机制：危险操作的用户确认

#### llm 模块
LLM 通信层，支持多种提供商：
- Moonshot
- OpenAI
- DeepSeek
- Qwen
- Ollama（本地）

#### tool 模块
工具注册和执行系统，通过 `ToolRegistry` 管理所有工具。

#### session 模块
会话管理，支持历史保存和恢复。

---

## 🔨 自定义扩展

### 创建自定义 Agent

#### 1. 创建 Agent 配置目录

```bash
mkdir -p src/main/resources/agents/custom
```

#### 2. 创建 agent.yaml

```yaml
# src/main/resources/agents/custom/agent.yaml
name: Custom Agent
description: My specialized agent for specific tasks

system_prompt: system_prompt.md
system_prompt_args: {}

tools:
  - ReadFile
  - WriteFile
  - Bash
  - Think

subagents: {}
```

#### 3. 创建系统提示词

```markdown
<!-- src/main/resources/agents/custom/system_prompt.md -->
# Custom Agent System Prompt

You are a specialized agent for [specific domain].

## Your Mission
- Analyze [specific type] of code
- Provide [specific type] of suggestions
- Follow [specific coding standards]

## Available Tools
You have access to file operations, code execution, and thinking tools.

## Best Practices
- Always verify before making changes
- Document your reasoning
- Test thoroughly
```

#### 4. 使用自定义 Agent

```bash
./jimi -w /path/to/project --agent-file src/main/resources/agents/custom/agent.yaml
```

### 开发自定义工具

#### 1. 创建工具类

```java
package io.leavesfly.jimi.tool.custom;

import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.Data;
import reactor.core.publisher.Mono;

public class MyCustomTool extends AbstractTool<MyCustomTool.Params> {

    @Override
    public String getName() {
        return "MyCustomTool";
    }

    @Override
    public String getDescription() {
        return "Description of what this tool does";
    }

    @Override
    public Class<Params> getParamsType() {
        return Params.class;
    }

    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.fromCallable(() -> {
            // 实现工具逻辑
            String result = processInput(params.input);
            
            return ToolResult.success(result);
        });
    }

    private String processInput(String input) {
        // 实际处理逻辑
        return "Processed: " + input;
    }

    @Data
    public static class Params {
        private String input;
    }
}
```

#### 2. 注册工具

在 `ToolRegistry.createStandardRegistry()` 中注册：

```java
// 在 ToolRegistry.java 中
public static ToolRegistry createStandardRegistry(...) {
    ToolRegistry registry = new ToolRegistry(objectMapper);
    
    // ... 现有工具注册 ...
    
    // 注册自定义工具
    registry.register(new MyCustomTool());
    
    return registry;
}
```

#### 3. 在 Agent 中启用

```yaml
# agent.yaml
tools:
  - MyCustomTool  # 添加你的工具
  - ReadFile
  - WriteFile
```

### 开发自定义命令处理器

```java
package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MyCommandHandler implements CommandHandler {

    @Override
    public String getName() {
        return "mycommand";
    }

    @Override
    public List<String> getAliases() {
        return List.of("mc");
    }

    @Override
    public String getDescription() {
        return "My custom command description";
    }

    @Override
    public void execute(CommandContext context) throws Exception {
        // 实现命令逻辑
        context.getWriter().println("My command executed!");
    }

    @Override
    public boolean isAvailable(CommandContext context) {
        return true;  // 设置可用条件
    }
}
```

Spring 会自动发现并注册带 `@Component` 注解的命令处理器。

---

## ❓ 常见问题

### Q1: 找不到 Java 17

**问题**: 运行时报错 `Java version not compatible`

**解决方案**:
```bash
# 检查 Java 版本
java -version

# 应该显示 17 或更高

# 设置 JAVA_HOME
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
```

### Q2: API Key 未设置

**问题**: 报错 `LLM provider not configured`

**解决方案**:
```bash
# 方式 1: 设置环境变量
export MOONSHOT_API_KEY="sk-your-key"

# 方式 2: 在配置文件中直接设置
vim ~/.config/jimi/providers.yaml
# 修改 api_key: "sk-your-key"
```

### Q3: 构建失败

**问题**: Maven 构建过程中出错

**解决方案**:
```bash
# 清理后重新构建
make clean
make build

# 或使用 Maven 详细模式
mvn clean package -X
```

### Q4: 内存不足

**问题**: 运行时内存溢出

**解决方案**:
```bash
# 增加 JVM 内存
export JVM_OPTS="-Xms1g -Xmx4g"

# 或修改启动脚本
vim jimi  # 修改 JVM_OPTS 默认值
```

### Q5: 配置文件未找到

**问题**: 启动时报错配置文件不存在

**解决方案**:
```bash
# 确保配置目录存在
mkdir -p ~/.config/jimi

# 复制配置模板
cp src/main/resources/config-template.yaml ~/.config/jimi/config.yaml
cp src/main/resources/providers-config-examples.yaml ~/.config/jimi/providers.yaml
```

### Q6: 工具执行被拒绝

**问题**: 文件写入或命令执行总是被拒绝

**解决方案**:
- 在交互模式下会提示确认，输入 `y` 批准
- 使用 `-y` 或 `--yolo` 参数自动批准所有操作（谨慎使用）

### Q7: 如何查看调试日志

**问题**: 需要查看详细的执行日志

**解决方案**:
```bash
# 启用调试模式
./jimi --debug -w /path/to/project

# 查看日志文件
tail -f ~/.kimi-cli/logs/jimi.log
```

### Q8: 会话历史在哪里

**问题**: 想要查看或清理会话历史

**解决方案**:
```bash
# 会话文件位置
~/.kimi-cli/sessions/

# 清理会话
rm -rf ~/.kimi-cli/sessions/*

# 或在 Shell 中使用 /reset 命令
```

---

## 🤝 贡献指南

我们欢迎所有形式的贡献！

### 如何贡献

1. **Fork 项目**
   ```bash
   git clone https://github.com/your-username/jimi.git
   ```

2. **创建特性分支**
   ```bash
   git checkout -b feature/amazing-feature
   ```

3. **提交更改**
   ```bash
   git commit -m 'Add some amazing feature'
   ```

4. **推送到分支**
   ```bash
   git push origin feature/amazing-feature
   ```

5. **开启 Pull Request**

### 开发指南

#### 环境设置

```bash
# 克隆项目
git clone https://github.com/your-org/jimi.git
cd jimi

# 构建
mvn clean package

# 运行测试
mvn test

# 开发模式运行
make dev
```

#### 代码规范

- 遵循 Java 编码规范
- 使用有意义的变量和方法名
- 添加必要的注释和文档
- 为新功能编写单元测试

#### 提交规范

使用语义化的提交消息：

```
feat: 添加新功能
fix: 修复 Bug
docs: 文档更新
style: 代码格式调整
refactor: 代码重构
test: 测试相关
chore: 构建或辅助工具变动
```

#### 测试

```bash
# 运行所有测试
make test

# 运行特定测试
mvn test -Dtest=ConfigLoaderTest

# 生成测试覆盖率报告
mvn clean verify jacoco:report
```

### 报告 Bug

请通过 [GitHub Issues](https://github.com/your-org/jimi/issues) 报告 Bug，包含：

- 问题描述
- 复现步骤
- 预期行为
- 实际行为
- 环境信息（Java 版本、操作系统等）
- 相关日志

### 功能请求

欢迎提出新功能建议！请通过 Issues 描述：

- 功能的使用场景
- 期望的行为
- 可能的实现方案

---

## 📄 许可证

本项目采用 [MIT License](LICENSE) 许可证。

---

## 🙏 致谢

- [Spring Boot](https://spring.io/projects/spring-boot) - 核心框架
- [Project Reactor](https://projectreactor.io/) - 响应式编程支持
- [Picocli](https://picocli.info/) - 命令行参数解析
- [JLine](https://github.com/jline/jline3) - 终端交互
- [Moonshot AI](https://www.moonshot.cn/) - LLM 服务

---

## 📞 联系方式

- **GitHub Issues**: [https://github.com/your-org/jimi/issues](https://github.com/your-org/jimi/issues)
- **GitHub Discussions**: [https://github.com/your-org/jimi/discussions](https://github.com/your-org/jimi/discussions)

---

## 📚 延伸阅读

- [快速入门指南](doc/QUICKSTART.md)
- [运行配置详解](doc/RUNNING.md)
- [MCP 集成文档](doc/MCP_LOCAL_IMPLEMENTATION.md)
- [Subagent 使用指南](src/main/resources/agents/SUBAGENTS_USAGE.md)

---

<div align="center">

**Made with ❤️ by Jimi Team**

⭐ 如果这个项目对你有帮助，请给我们一个 Star！

</div>
