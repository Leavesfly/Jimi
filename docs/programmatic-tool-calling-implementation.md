# Programmatic Tool Calling 实现方案

## 1. 背景与目标

### 1.1 什么是 Programmatic Tool Calling

Programmatic Tool Calling 是 Claude 推出的高级功能，允许 LLM 生成代码来编排多个工具调用，而不是传统的串行 API 调用方式。

**核心优势**:
- 减少 30-40% 的 context token 消耗
- 支持循环、条件判断等复杂编排逻辑
- 中间结果在沙箱中处理，不占用 context

### 1.2 实施目标

在 Jimi 中实现轻量级的 Programmatic Tool Calling 功能：
- 基于 JShell 实现代码执行沙箱
- 提供工具调用桥接机制
- 与现有 ToolProvider SPI 架构无缝集成
- 支持 Java 代码编排（非 Python）

## 2. 技术架构

### 2.1 整体架构

```
MetaToolProvider (SPI)
    ↓
MetaTool (工具实现)
    ↓
JShellCodeExecutor (代码执行引擎)
    ↓
ToolBridge (工具调用桥接)
    ↓
ToolRegistry (现有工具注册表)
```

### 2.2 核心组件设计

#### 2.2.1 MetaTool

**职责**: 接收 Java 代码并执行，返回最终结果

**参数结构**:
```java
class Params {
    String code;              // Java 代码片段
    int timeout;              // 执行超时（秒），默认30
    List<String> allowedTools; // 允许调用的工具列表（可选）
}
```

**执行流程**:
1. 验证代码安全性（基础检查）
2. 调用 JShellCodeExecutor 执行代码
3. 返回执行结果或错误信息

#### 2.2.2 JShellCodeExecutor

**职责**: 管理 JShell 实例，执行代码

**核心功能**:
- 初始化 JShell 环境
- 注入 ToolBridge 实例
- 执行代码并捕获结果
- 超时控制（30秒）
- 资源清理

**安全限制**:
- 禁止 System.exit()
- 禁止反射调用危险方法
- 限制执行时间
- 限制内存使用（通过 JVM 参数）

#### 2.2.3 ToolBridge

**职责**: 在 JShell 中提供工具调用能力

**核心方法**:
```java
public String callTool(String toolName, String arguments)
public String callTool(String toolName, Map<String, Object> arguments)
```

**实现要点**:
- 同步等待工具执行结果（block on Mono）
- 记录工具调用日志
- 处理工具执行错误
- 返回 JSON 格式结果

#### 2.2.4 MetaToolProvider

**职责**: SPI 提供者，决定是否加载 MetaTool

**加载条件**:
- 配置文件中 `meta-tool.enabled=true`
- Agent 工具列表包含 "MetaTool"

## 3. 实施步骤

### 阶段 0: 配置准备

**文件**: `src/main/resources/application.yml`

```yaml
jimi:
  meta-tool:
    enabled: true           # 是否启用 MetaTool
    max-execution-time: 30  # 最大执行时间（秒）
    max-code-length: 10000  # 最大代码长度
```

**文件**: `src/main/java/io/leavesfly/jimi/config/MetaToolConfig.java`

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "jimi.meta-tool")
public class MetaToolConfig {
    private boolean enabled = true;
    private int maxExecutionTime = 30;
    private int maxCodeLength = 10000;
}
```

### 阶段 1: 创建基础设施

#### 1.1 创建 meta 包

**目录结构**:
```
src/main/java/io/leavesfly/jimi/tool/meta/
├── MetaTool.java
├── MetaToolProvider.java
├── JShellCodeExecutor.java
├── ToolBridge.java
└── CodeExecutionContext.java
```

#### 1.2 实现 CodeExecutionContext

**用途**: 封装代码执行的上下文信息

```java
@Data
@Builder
public class CodeExecutionContext {
    private String code;
    private int timeout;
    private List<String> allowedTools;
    private ToolRegistry toolRegistry;
}
```

### 阶段 2: 实现核心组件

#### 2.1 实现 ToolBridge

**关键点**:
- 持有 ToolRegistry 引用
- 提供同步的工具调用方法
- 处理 Reactor Mono → 同步结果转换

```java
public class ToolBridge {
    private final ToolRegistry toolRegistry;
    
    public String callTool(String toolName, String arguments) {
        // 调用 ToolRegistry.execute() 并 block 等待结果
        // 返回 JSON 格式的结果
    }
}
```

#### 2.2 实现 JShellCodeExecutor

**关键点**:
- 使用 `jdk.jshell.JShell` API
- 注入 ToolBridge 实例
- 使用 CompletableFuture 实现超时控制
- 捕获执行结果和异常

```java
public class JShellCodeExecutor {
    public Mono<String> execute(CodeExecutionContext context) {
        return Mono.fromCallable(() -> {
            // 创建 JShell 实例
            // 注入 ToolBridge
            // 执行代码
            // 返回结果
        }).timeout(Duration.ofSeconds(context.getTimeout()));
    }
}
```

#### 2.3 实现 MetaTool

**关键点**:
- 继承 AbstractTool
- 验证参数
- 调用 JShellCodeExecutor
- 返回 ToolResult

```java
@Component
@Scope("prototype")
public class MetaTool extends AbstractTool<MetaTool.Params> {
    private final JShellCodeExecutor executor;
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        // 验证代码
        // 构建执行上下文
        // 调用 executor
        // 返回结果
    }
}
```

#### 2.4 实现 MetaToolProvider

**关键点**:
- 检查配置是否启用
- 检查 Agent 是否配置 MetaTool
- 创建 MetaTool 实例

```java
@Component
public class MetaToolProvider implements ToolProvider {
    @Override
    public boolean supports(AgentSpec agentSpec, Runtime runtime) {
        return metaToolConfig.isEnabled() 
            && agentSpec.getTools() != null
            && agentSpec.getTools().contains("MetaTool");
    }
}
```

### 阶段 3: 集成到 ToolRegistryFactory

**修改**: `src/main/java/io/leavesfly/jimi/tool/ToolRegistryFactory.java`

无需修改，Spring 会自动发现 MetaToolProvider。

### 阶段 4: Agent 提示词增强

**修改**: Agent 配置文件（如 `default` agent）

在系统提示词中添加 MetaTool 使用说明：

```markdown
## MetaTool - 代码编排工具

当需要执行多步操作（如循环处理文件、批量数据转换）时，可以使用 MetaTool 编写 Java 代码来编排工具调用。

### 可用方法
- `String callTool(String toolName, String arguments)` - 调用工具并获取结果

### 示例1: 批量读取文件
```java
String[] files = {"file1.txt", "file2.txt", "file3.txt"};
StringBuilder result = new StringBuilder();
for (String file : files) {
    String content = callTool("ReadFile", "{\"path\":\"" + file + "\"}");
    result.append(content).append("\n---\n");
}
return result.toString();
```

### 示例2: 条件执行
```java
String info = callTool("Bash", "{\"command\":\"uname -s\"}");
if (info.contains("Linux")) {
    return callTool("Bash", "{\"command\":\"apt list --installed\"}");
} else {
    return callTool("Bash", "{\"command\":\"brew list\"}");
}
```

### 注意事项
- 代码在隔离环境中执行
- 中间工具调用结果不会占用 context
- 只有最终返回值会加入对话历史
- 执行超时限制为 30 秒
```

### 阶段 5: 测试验证

#### 5.1 单元测试

**文件**: `src/test/java/io/leavesfly/jimi/tool/meta/JShellCodeExecutorTest.java`

测试场景:
- 简单代码执行
- 工具调用桥接
- 超时控制
- 异常处理

#### 5.2 集成测试

**文件**: `src/test/java/io/leavesfly/jimi/tool/meta/MetaToolIntegrationTest.java`

测试场景:
- 循环调用工具
- 条件判断
- 错误处理
- 与真实 ToolRegistry 集成

## 4. 文件清单

### 新增文件

| 文件路径 | 用途 | 预计行数 |
|---------|------|---------|
| `config/MetaToolConfig.java` | 配置类 | 30 |
| `tool/meta/CodeExecutionContext.java` | 执行上下文 | 40 |
| `tool/meta/ToolBridge.java` | 工具桥接 | 120 |
| `tool/meta/JShellCodeExecutor.java` | 代码执行引擎 | 200 |
| `tool/meta/MetaTool.java` | 主工具类 | 150 |
| `tool/meta/MetaToolProvider.java` | SPI 提供者 | 60 |
| `test/.../JShellCodeExecutorTest.java` | 单元测试 | 200 |
| `test/.../MetaToolIntegrationTest.java` | 集成测试 | 150 |

**总计**: 约 950 行代码

### 修改文件

| 文件路径 | 修改内容 | 预计修改行数 |
|---------|---------|------------|
| `resources/application.yml` | 添加 meta-tool 配置 | +5 |
| `agents/default/system-prompt.md` | 添加 MetaTool 使用说明 | +50 |

## 5. 风险与限制

### 5.1 安全风险

- **代码注入**: LLM 可能生成恶意代码
- **缓解措施**: 
  - 基础安全检查（禁止 System.exit 等）
  - 超时限制
  - 未来可考虑 SecurityManager（Java 17+ 已废弃）

### 5.2 功能限制

- **仅支持 Java**: 不支持 Python（与 Claude 不同）
- **同步调用**: 工具调用是同步的，可能影响性能
- **缓解措施**: 
  - 提示词中说明 Java 语法
  - 未来可升级到 GraalVM 支持 Python

### 5.3 兼容性

- **JShell 要求**: 需要 Java 9+（项目使用 Java 17，满足要求）
- **Reactor 集成**: ToolBridge 需要 block Mono，可能影响性能

## 6. 未来演进

### 6.1 短期优化（1-2个月）

- 添加代码静态分析（AST 解析）
- 优化工具调用性能（缓存 JShell 实例）
- 支持更多 Java 语法糖

### 6.2 长期演进（3-6个月）

- 集成 GraalVM 支持 Python
- 实现 Docker 沙箱隔离
- 支持流式代码执行反馈

## 7. 成功指标

- ✅ 成功执行循环工具调用
- ✅ Context token 减少 30%+
- ✅ 单元测试覆盖率 > 80%
- ✅ 集成测试通过
- ✅ 无安全漏洞

---

**文档版本**: v1.0  
**创建日期**: 2025-12-09  
**作者**: Jimi Team
