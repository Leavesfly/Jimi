# 11 · MCP 协议集成

> 本篇讲清楚 Jimi 如何作为 **MCP Client** 接入外部 MCP Server 把它们的工具变成 Jimi 可调用的原生工具。所有结论基于 `src/main/java/io/leavesfly/jimi/mcp/**`、`src/main/java/io/leavesfly/jimi/tool/core/mcp/**`、`src/main/java/io/leavesfly/jimi/tool/provider/MCPToolProvider.java`、`src/main/java/io/leavesfly/jimi/tool/ToolRegistryFactory.java`、`src/main/java/io/leavesfly/jimi/CliApplication.java`、`scripts/test-mcp-*.sh` 亲读确认。**重要**：源码层面 Jimi 只实装了 **MCP Client 方向**；`scripts/test-mcp-*.sh` 里出现的 `--mcp-server` CLI 参数和 `jimi_execute` 工具名在 Java 代码中并不存在，详见 §7.3 M-P1。

---

## 1 · 模块地图

Jimi 的 MCP 集成代码分布在两个包，总共 11 个类：

| 层 | 包 / 文件 | 角色 |
|---|---|---|
| **协议层** | `io.leavesfly.jimi.mcp.JsonRpcClient` | 统一接口：`initialize` / `listTools` / `callTool` |
| | `io.leavesfly.jimi.mcp.AbstractJsonRpcClient` | 模板类，封装 MCP 协议语义，子类只需实现 `sendRequest` |
| | `io.leavesfly.jimi.mcp.StdIoJsonRpcClient` | 通过子进程 stdio 通信 |
| | `io.leavesfly.jimi.mcp.HttpJsonRpcClient` | 通过 HTTP 通信（`WebClient`） |
| **消息层** | `io.leavesfly.jimi.mcp.JsonRpcMessage` | JSON-RPC 2.0 `Request` / `Response` / `Error` 三个嵌套类 |
| | `io.leavesfly.jimi.mcp.MCPSchema` | MCP 协议本地 Schema（Tool / Content / InitializeResult 等） |
| **配置层** | `io.leavesfly.jimi.mcp.MCPConfig` | 配置文件对象：`mcpServers: Map<String, ServerConfig>` |
| **转换层** | `io.leavesfly.jimi.mcp.MCPResultConverter` | `CallToolResult` → Jimi `ToolResult` 静态工具类 |
| **桥接层** | `io.leavesfly.jimi.tool.core.mcp.MCPTool` | `AbstractTool<Map<String, Object>>` wrapper |
| | `io.leavesfly.jimi.tool.core.mcp.MCPToolLoader` | Spring `@Service`，读取配置 + 建立连接 + 注册工具 + `@PreDestroy` 清理 |
| **接入层** | `io.leavesfly.jimi.tool.provider.MCPToolProvider` | `ToolProvider` SPI 实现，`getOrder() = 60` |

**调用流向**（按启动时序）：

```
CliApplication --mcp-config-file <path>
   └─> JimiFactory.EngineBuilder.mcpConfigs(List<Path>)
         └─> JimiFactory.doCreateEngine(... mcpConfigFiles)
               └─> ToolRegistryFactory.create(..., mcpConfigFiles)
                     └─> applyToolProviders → MCPToolProvider.setMcpConfigFiles
                           └─> MCPToolProvider.createTools(...)
                                 └─> MCPToolLoader.loadFromFile(configFile, tempRegistry)
                                       ├─> 解析 MCPConfig (Jackson)
                                       ├─> 为每个 ServerConfig 创建 JsonRpcClient
                                       ├─> client.initialize() (2024-11-05)
                                       ├─> client.listTools()
                                       └─> 为每个 MCPSchema.Tool 创建 MCPTool 并注册
```

**注意**：Jimi 当前只实装了 **MCP Client 方向**（连接别的 MCP Server 吸收工具）。**未实装 MCP Server 方向**（把 Jimi 暴露为 MCP Server 供 IDE 等调用），详见 §7.3 M-P1。

---

## 2 · 配置文件格式（`MCPConfig`）

### 2.1 Schema

源码 `mcp/MCPConfig.java` 用 Jackson `@JsonProperty` 注解声明：

```java
public class MCPConfig {
    @JsonProperty("mcpServers")
    private Map<String, ServerConfig> mcpServers;

    public static class ServerConfig {
        @JsonProperty("command") private String command;            // STDIO 启动命令
        @JsonProperty("args")    private List<String> args;         // 命令参数
        @JsonProperty("env")     private Map<String, String> env;   // 环境变量
        @JsonProperty("url")     private String url;                // HTTP URL
        @JsonProperty("headers") private Map<String, String> headers; // HTTP 请求头

        public boolean isStdio() { return command != null && !command.isEmpty(); }
        public boolean isHttp()  { return url != null && !url.isEmpty(); }
    }
}
```

`mcpServers` 的 key 就是**服务名**（任意字符串），value 是对应的 `ServerConfig`。

### 2.2 示例

**STDIO 传输**（启动本地子进程）：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": {
        "NODE_ENV": "production"
      }
    }
  }
}
```

**HTTP 传输**（连接远程服务）：

```json
{
  "mcpServers": {
    "remote-search": {
      "url": "https://mcp.example.com/rpc",
      "headers": {
        "Authorization": "Bearer your-token"
      }
    }
  }
}
```

### 2.3 传输方式判定规则

`MCPToolLoader.createClient`（源码 `MCPToolLoader#111-119`）：

```java
if (config.isStdio()) {
    return createStdioClient(...);  // command 非空优先
} else if (config.isHttp()) {
    return createHttpClient(...);
} else {
    throw new IllegalArgumentException("Invalid MCP server config");
}
```

**关键观察**：`isStdio()` 和 `isHttp()` **不互斥**——如果 ServerConfig 同时配了 `command` 和 `url`，**STDIO 优先**，`url` 被静默忽略。没有任何 warning 提示用户配置冲突（见 §7.1 M-L2）。

## 3 · 协议层（`JsonRpcClient` 家族）

### 3.1 接口定义

`mcp/JsonRpcClient.java` 定义三个方法 + `AutoCloseable`：

```java
public interface JsonRpcClient extends AutoCloseable {
    MCPSchema.InitializeResult initialize() throws Exception;
    MCPSchema.ListToolsResult  listTools() throws Exception;
    MCPSchema.CallToolResult   callTool(String toolName, Map<String, Object> arguments) throws Exception;
}
```

**观察**：
- 所有方法都是 **同步 `throws Exception`**——没有 `Mono`/`CompletableFuture`。MCP 调用链路从协议层就是**阻塞式**的，直到桥接层 `MCPTool.execute()` 才用 `Mono.fromCallable` 包装回响应式（见 §5.1）。
- `callTool` 的第二个参数类型是 `Map<String, Object>` 而非 `JsonNode`——意味着 Jimi 在反射转换时要做一次 `Map → JSON` 的序列化。

### 3.2 `AbstractJsonRpcClient` 模板

`mcp/AbstractJsonRpcClient.java` 提供三个方法的通用实现，子类只需实现 `sendRequest`：

```java
protected abstract JsonRpcMessage.Response sendRequest(String method, Map<String, Object> params) throws Exception;
```

**`initialize` 的协议参数**（硬编码在 `AbstractJsonRpcClient#44-47`）：

```java
Map<String, Object> params = new HashMap<>();
params.put("protocolVersion", "2024-11-05");
params.put("capabilities", Map.of());           // 空能力声明
params.put("clientInfo", Map.of("name", "jimi", "version", "0.1.0"));
```

**关键事实**：
- **协议版本硬编码 `2024-11-05`**——对应 MCP 规范第一次公开发布的版本。不支持协商，也不支持在配置里覆盖。
- **capabilities 完全为空**——Jimi 不声明任何客户端能力（比如 `roots`、`sampling`、`prompts` 等）。意味着支持 `roots` 的 server 无法从 Jimi 端获取工作目录信息（见 §7.5 M-G3）。
- **`initialize` 之后不发 `notifications/initialized`**——MCP 规范要求客户端必须发这条通知来完成初始化握手，源码中**完全缺失**（见 §7.5 M-G1）。严格的 MCP Server 实现可能拒绝后续请求。

**`listTools` / `callTool` 通用处理**：统一检查 `response.getError()` 非空则 `throw new RuntimeException("... failed: " + error.getMessage())`，丢失原始 `code` 和 `data` 字段（见 §7.4 M-H5）。

**`parseContents` 的类型安全**（源码 `AbstractJsonRpcClient#93-161`）：用 `instanceof Map` / `instanceof String` 一层层校验，每层失败就 `log.warn` 并 `continue`——优于直接 `ClassCastException`，但**也会导致损坏的工具结果被静默丢弃**，调用方收到的 `CallToolResult.content` 可能少于服务端实际返回。

### 3.3 `StdIoJsonRpcClient`：子进程 + 轮询

源码 `mcp/StdIoJsonRpcClient.java` 的构造器：

```java
public StdIoJsonRpcClient(String command, List<String> args, Map<String, String> env) throws IOException {
    ProcessBuilder pb = new ProcessBuilder();
    List<String> fullCommand = new ArrayList<>();
    fullCommand.add(command);
    if (args != null) fullCommand.addAll(args);
    pb.command(fullCommand);

    if (env != null && !env.isEmpty()) pb.environment().putAll(env);  // 叠加到继承环境变量

    pb.redirectError(ProcessBuilder.Redirect.INHERIT);                  // ⚠️ stderr 继承到 Jimi 自己的 stderr
    Process startedProcess = pb.start();
    this.writer = new BufferedWriter(new OutputStreamWriter(startedProcess.getOutputStream()));
    this.reader = new BufferedReader(new InputStreamReader(startedProcess.getInputStream()));

    this.readerThread = new Thread(this::readLoop, "MCP-Reader");
    this.readerThread.setDaemon(true);
    this.readerThread.start();
}
```

**通信模型**：

```
Jimi 主线程                    MCP-Reader 线程（daemon）
──────────                    ─────────────────────────
sendRequest(method, params)
  ├─ requestId = counter++
  ├─ writer.write(JSON + "\n")
  ├─ writer.flush()
  └─ while (!responseCache.containsKey(requestId)) {
       if (closed) throw ...
       if (>30s)  throw "Request timeout"
       Thread.sleep(100);           ← 100ms 轮询
     }
     return responseCache.remove(requestId);

                              readLoop():
                              while ((line = reader.readLine()) != null) {
                                JsonRpcMessage.Response r = objectMapper.readValue(line, Response.class);
                                if (r.getId() != null)
                                  responseCache.put(r.getId(), r);
                              }
```

**硬编码清单**：
- **`Thread.sleep(100)`**：主线程每 100 ms 检查一次 `responseCache`——意味着每个 MCP 调用最少 100 ms 延迟抖动（见 §7.4 M-H1）
- **30 秒总超时**：`System.currentTimeMillis() - startTime > 30000`（见 §7.4 M-H2）
- **`synchronized sendRequest`**：`sendRequest` 方法用 `synchronized`——**所有并发调用都会串行化**，即使是同一个 MCP Server 下的不同工具（见 §7.4 M-H3）
- **`pb.redirectError(INHERIT)`**：子进程 stderr 直接打到 Jimi 自己的 stderr，**不捕获、不过滤**——可能污染 CLI 输出（见 §7.4 M-H4）

**关闭流程** `close()`：
```java
closed = true;
writer.close(); reader.close();
process.destroy();
process.waitFor();       // 阻塞等子进程退出
readerThread.interrupt();
```

没有 `destroyForcibly()` 兜底——如果子进程拒绝 SIGTERM，`waitFor()` 会**无限等**（见 §7.4 M-H6）。

### 3.4 `HttpJsonRpcClient`：WebClient + `block()`

源码 `mcp/HttpJsonRpcClient.java` 极简 70 行：

```java
public HttpJsonRpcClient(String url, Map<String, String> headers) {
    WebClient.Builder builder = WebClient.builder()
            .baseUrl(url)
            .defaultHeader("Content-Type", "application/json");
    if (headers != null && !headers.isEmpty()) headers.forEach(builder::defaultHeader);
    this.webClient = builder.build();
}

@Override
protected JsonRpcMessage.Response sendRequest(String method, Map<String, Object> params) throws Exception {
    ...
    Mono<JsonRpcMessage.Response> responseMono = webClient.post()
            .bodyValue(request)
            .retrieve()
            .bodyToMono(JsonRpcMessage.Response.class)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))   // 30 秒
            ...;
    return responseMono.block();                                      // ⚠️ 响应式 → 阻塞
}
```

**观察**：
- **`REQUEST_TIMEOUT_SECONDS = 30`** 硬编码，和 STDIO 一致，不可配置
- **`responseMono.block()`** —— `HttpJsonRpcClient` 明明拿着响应式 `WebClient`，却调 `block()` 退化成同步（因为接口要求如此）——**整条响应式链路被断**
- **MCP HTTP 规范要求 SSE 支持**（`text/event-stream`）用于 server-to-client 通知推送，Jimi 只做最简单的 `post → response` 请求-响应模式，**无 SSE**（见 §7.5 M-G2）
- `close()` 只打了条日志 `"HTTP JSON-RPC client closed"`——**没有任何连接池或 WebClient 的资源释放**。Spring 会在 ApplicationContext 关闭时回收 WebClient，但单独关闭单个 HttpJsonRpcClient **等于空操作**

## 4 · Schema 与结果转换（`MCPSchema` / `MCPResultConverter`）

### 4.1 `MCPSchema` 类族

`mcp/MCPSchema.java` 是 Jimi 对 MCP 协议的**本地 Schema 定义**（JavaDoc 写明"替代 `io.modelcontextprotocol.sdk` 中的 `McpSchema`"）。共 7 个嵌套类 + 1 个空接口：

| 嵌套类型 | 角色 | 字段 |
|---|---|---|
| `Tool` | 工具元数据 | `name`, `description`, `inputSchema: Map<String, Object>` |
| `ListToolsResult` | `tools/list` 结果 | `tools: List<Tool>` |
| `CallToolResult` | `tools/call` 结果 | `content: List<Content>`, `isError: Boolean` |
| `Content` | **接口**（空标记） | — |
| `TextContent` | 文本内容 | `type = "text"`, `text: String` |
| `ImageContent` | 图片内容 | `type = "image"`, `data: String (base64)`, `mimeType: String` |
| `EmbeddedResource` | 嵌入资源 | `type = "resource"`, `resource: ResourceContents` |
| `ResourceContents` | 资源内容 | `uri`, `mimeType`, `blob (base64)` |
| `InitializeResult` | 初始化响应 | `protocolVersion`, `capabilities: Map`, `serverInfo: Map` |

**关键事实**：
- `inputSchema` 存为 **`Map<String, Object>`** 而非强类型——直接透传 JSON Schema 给大模型（通过 `MCPTool.getCustomParametersSchema`，见 §5.1）。
- **`Content` 接口是空标记接口**（`public interface Content {}`），没有任何方法。Jackson 反序列化时**不能直接**反序列为 `Content`，而是在 `AbstractJsonRpcClient.parseContents` 中手工按 `type` 字段分派（见 §3.2）——意味着 pom.xml 引入的 `mcp-bom:0.12.1` SDK 并**没有被用于反序列化**，Jimi 完全自己造轮子（见 §7.3 M-P2）。
- `capabilities` / `serverInfo` 是 **`Map<String, Object>`**，Jimi **不解析具体能力**——即使 server 声明支持 `prompts/resources/sampling`，Jimi 也不会做任何差异化处理（见 §7.5 M-G4）。

### 4.2 `JsonRpcMessage` 三个嵌套类

`mcp/JsonRpcMessage.java` 定义 JSON-RPC 2.0 的标准三要素：

```java
public static class Request {
    private String jsonrpc = "2.0";
    private Object id;                          // counter 自增的 Integer
    private String method;                       // "initialize" | "tools/list" | "tools/call"
    private Map<String, Object> params;
}

public static class Response {
    private String jsonrpc = "2.0";
    private Object id;
    private Map<String, Object> result;           // 成功返回
    private Error error;                          // 失败返回
}

public static class Error {
    private int code;
    private String message;
    private Object data;                          // 可选附加数据
}
```

**`@JsonInclude(JsonInclude.Include.NON_NULL)`** 加在 `Request` / `Response` 类上：序列化时 `null` 字段被省略，避免发送 `"error": null` 这类冗余字段。`Error` 类**没有**这个注解——序列化 `Error` 时 `data` 即使为 null 也会写出 `"data":null`（见 §7.4 M-H8）。

### 4.3 `MCPResultConverter`：`CallToolResult` → `ToolResult`

静态工具类 `mcp/MCPResultConverter.java`，入口 `public static ToolResult convert(MCPSchema.CallToolResult mcpResult)`。

**转换流程**：

1. **null 检查**：`mcpResult == null` → `ToolResult.error("MCP result is null", "Empty result")`
2. **错误检查**：`Boolean.TRUE.equals(mcpResult.getIsError())` → `ToolResult.error("MCP tool returned error", "Tool execution error")`——**注意**：此分支**不读取**实际的 error content，丢失服务端返回的错误详情（见 §7.1 M-L1）
3. **空内容**：`content == null || isEmpty` → `ToolResult.ok("", "")`
4. **逐项转换**：`TextContent → TextPart` / `ImageContent → ImagePart (Data URL)` / `EmbeddedResource → 仅图片类型返回 ImagePart, 其他返回 null`
5. **单文本优化**：`contentParts.size() == 1 && 是 TextPart` → `ToolResult.ok(textPart.getText(), "")`
6. **多内容合并**：用 `"\n"` 拼接所有 `TextPart.text` + `ImagePart.url` + 其他 `part.toString()`——**图片 URL 和文本混在同一个字符串字段**里回传（见 §7.1 M-L3）

**Data URL 构造**：
```java
String dataUrl = String.format("data:%s;base64,%s", mimeType, data);
// 例：data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...
```

如果 `mimeType` 为 null/空，**默认 `"image/png"`**（源码 `convertImageContent#102`）——但 base64 数据的真实格式可能是 jpeg/webp，导致浏览器渲染失败（见 §7.4 M-H7）。

**嵌入资源的取舍**：`convertEmbeddedResource` 只处理 `mimeType.startsWith("image/")` 的资源，**其他类型（如 `text/markdown`、`application/json`）直接返回 null**——被 §4.3 第 6 步的循环 `if (part != null)` 过滤掉，**静默丢失**（见 §7.1 M-L4）。

## 5 · 工具桥接（`MCPTool` / `MCPToolLoader` / `MCPToolProvider`）

### 5.1 `MCPTool`：把 MCP 工具包装成 `AbstractTool`

源码 `tool/core/mcp/MCPTool.java`：

```java
public class MCPTool extends AbstractTool<Map<String, Object>> {
    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
    private final JsonRpcClient mcpClient;
    private final String mcpToolName;
    private final int timeoutSeconds;             // ⚠️ 构造器接收但 execute() 不使用
    private final JsonNode inputSchemaNode;        // MCP server 返回的 JSON Schema

    public MCPTool(MCPSchema.Tool mcpTool, JsonRpcClient mcpClient) {
        this(mcpTool, mcpClient, 20);              // 默认超时 20 秒
    }

    @Override
    public JsonNode getCustomParametersSchema() {
        return inputSchemaNode;                    // 透传 server 的 inputSchema 给大模型
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            try {
                MCPSchema.CallToolResult result = mcpClient.callTool(
                    mcpToolName, params != null ? params : new HashMap<>());
                return MCPResultConverter.convert(result);
            } catch (Exception e) {
                log.error("Failed to execute MCP tool {}: {}", mcpToolName, e.getMessage());
                return ToolResult.error(
                    "Failed to execute MCP tool: " + e.getMessage(),
                    "MCP tool execution failed");
            }
        });
    }
}
```

**关键事实**：
- **`timeoutSeconds` 字段从未被 `execute()` 读取**——构造器默认 20 秒只是形式参数，真实超时来自 `StdIoJsonRpcClient`/`HttpJsonRpcClient` 的 30 秒硬编码（见 §7.1 M-L5 / §7.4 M-H2）。这是一个典型的"预留参数但未接通"的设计。
- **`getCustomParametersSchema` 是 `Tool` 基类的新钩子**（见 04 篇工具系统）：`ToolRegistry#buildFunctionSchema` 会优先使用该方法返回的 `JsonNode`，否则回退到基于反射的自动生成。MCP 工具参数类型是 `Map<String, Object>`——反射生成不出任何字段信息，**必须**靠 `inputSchema` 透传才能让大模型知道参数名、类型、必填性。
- **`Mono.fromCallable` 包装**：协议层的同步 `throws Exception` 调用被包装成响应式，但**不指定 `subscribeOn`**——默认在调用者线程执行。也就是说 MCP 调用会在 `AgentExecutor` 的 reactor 线程里阻塞 30 秒（见 §7.4 M-H9）。
- **`convertInputSchema` 的容错**：Jackson `valueToTree` 失败时返回 null，导致工具**退回到反射模式**——大模型看到的是空参数定义，调用几乎必然失败，但 Jimi 只 `log.warn` 不抛错（见 §7.1 M-L6）。

### 5.2 `MCPToolLoader`：加载 + 生命周期

`tool/core/mcp/MCPToolLoader.java` 是 Spring `@Service`，负责建立连接、拉取工具、注册到 `ToolRegistry`。

**`loadFromConfig` 核心流程**（源码 `MCPToolLoader#71-99`）：

```java
for (Map.Entry<String, MCPConfig.ServerConfig> entry : config.getMcpServers().entrySet()) {
    String serverName = entry.getKey();
    MCPConfig.ServerConfig serverConfig = entry.getValue();
    try {
        // 1. 创建客户端连接
        JsonRpcClient client = createClient(serverName, serverConfig);
        activeClients.add(client);                          // 统一纳管
        // 2. 初始化连接
        client.initialize();
        // 3. 获取工具列表
        MCPSchema.ListToolsResult toolsResult = client.listTools();
        List<MCPSchema.Tool> tools = toolsResult.getTools();
        // 4. 包装和注册每个工具
        for (MCPSchema.Tool tool : tools) {
            MCPTool mcpTool = new MCPTool(tool, client);
            toolRegistry.register(mcpTool);
            loadedTools.add(mcpTool);
            log.info("Loaded MCP tool: {} from server: {}", tool.getName(), serverName);
        }
    } catch (Exception e) {
        log.error("Failed to load MCP tools from server {}: {}", serverName, e.getMessage());
        // 继续加载其他 server
    }
}
```

**关键事实**：
- **容错粒度 = Server**：源码 `try` 块从 `createClient(...)` 开始，到 `for (MCPSchema.Tool tool : tools)` 结束。因此任何一步抛异常都只打 `log.error(...)` 不 rethrow，**继续加载下一个 Server**。
- **`activeClients.add(client)` 发生在 `client.initialize()` 之前**（源码 `MCPToolLoader#82-84`）——意味着**即使 `initialize()` 抛异常，client 已进入 activeClients**。`@PreDestroy.closeAll()` 会对它调 `close()`，对 `StdIoJsonRpcClient` 来说子进程会被正常销毁；但如果 `createClient(...)` 本身就抛（如 `ProcessBuilder.start()` 失败），则 client 从未创建，不会泄漏。**唯一的特殊情况**是 `HttpJsonRpcClient` 的构造器不做网络探测，即使 URL 不可达也会成功创建并进 `activeClients`——之后 `initialize()` 抛 timeout，client 残留在列表里等待 `@PreDestroy` 兜底。
- **工具名冲突不检测**：如果两个 Server 都提供 `search` 工具，第二次 `toolRegistry.register(mcpTool)` 的行为取决于 `ToolRegistry#register` 实现（见 04 篇）。MCP 层面**不做 serverName 前缀隔离**（如 `filesystem.search` vs `other.search`），这是潜在的命名冲突（见 §7.3 M-P3）。

**`@PreDestroy` 清理**（源码 `MCPToolLoader#160-170`）：

```java
@PreDestroy
public void closeAll() {
    log.info("Closing {} MCP client(s)...", activeClients.size());
    for (JsonRpcClient client : activeClients) {
        try { client.close(); }
        catch (Exception e) { log.warn("Failed to close MCP client: {}", e.getMessage()); }
    }
    activeClients.clear();
}
```

**观察**：`MCPToolLoader` 作为 Spring `@Service` 是**单例**——整个 Spring 应用共享一份 `activeClients`，和 Jimi 会话数/工作目录解耦。这意味着 `--mcp-config-file` 在 CLI 启动时生效一次，会话内不能动态加载/卸载 MCP Server（见 §7.3 M-P4）。

### 5.3 `MCPToolProvider`：ToolProvider SPI 实现

`tool/provider/MCPToolProvider.java`：

```java
@Component
public class MCPToolProvider implements ToolProvider {
    @Autowired private final MCPToolLoader mcpToolLoader;
    @Autowired private final ObjectMapper objectMapper;
    private List<Path> mcpConfigFiles;   // 由外部注入（见 §6）

    public void setMcpConfigFiles(List<Path> mcpConfigFiles) { this.mcpConfigFiles = mcpConfigFiles; }

    @Override
    public boolean supports(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        return mcpConfigFiles != null && !mcpConfigFiles.isEmpty();
    }

    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        List<Tool<?>> allMcpTools = new ArrayList<>();
        ToolRegistry tempRegistry = new ToolRegistry(objectMapper);   // ⚠️ 临时注册表
        for (Path configFile : mcpConfigFiles) {
            try {
                List<MCPTool> mcpTools = mcpToolLoader.loadFromFile(configFile, tempRegistry);
                allMcpTools.addAll(mcpTools);
                log.info("Loaded {} MCP tools from {}", mcpTools.size(), configFile);
            } catch (Exception e) {
                log.error("Failed to load MCP config: {}", configFile, e);
            }
        }
        return allMcpTools;
    }

    @Override public int getOrder() { return 60; }   // 在 SubAgentTool 之后
}
```

**关键事实**：
- **`tempRegistry` 是垃圾桶**：`createTools` 创建一个空的 `ToolRegistry` 传给 `MCPToolLoader.loadFromFile`，里面 `toolRegistry.register(mcpTool)` 会把工具注册进 `tempRegistry`，但**返回后 `tempRegistry` 被直接抛弃**——`createTools` 的返回值 `allMcpTools` 才是真正被 `ToolRegistryFactory` 用来注册到生产 `registry` 的（通过 `ToolProvider` SPI 接口的契约）。意味着 `MCPToolLoader.loadFromFile` 里的 `toolRegistry.register(mcpTool)` 是一次**无意义的副作用**（见 §7.1 M-L7）。
- **`getOrder() = 60`**：ToolProvider 顺序决定工具注册次序；60 比 SubAgentTool（见 04 篇）大，意味着 MCP 工具**晚于** SubAgent 注册。如果 MCP 工具名与 SubAgent/内置工具冲突，**MCP 会覆盖内置**（见 §7.3 M-P5）。
- **`supports` 判空即放行**：没有配置文件就跳过；**不会检查文件是否存在、是否 JSON 合法**——文件缺失或格式错误会在 `createTools` 的 `try/catch` 里以 `log.error` 形式吞掉。

## 6 · 启动链路（CLI → JimiFactory → ToolRegistry）

### 6.1 CLI 参数入口

`CliApplication.java#85-86`（`@Option` 声明）：

```java
@Option(names = {"--mcp-config-file"}, description = "MCP configuration file (can be specified multiple times)")
private List<Path> mcpConfigFiles = new ArrayList<>();
```

**关键事实**：
- **参数名是单数 `--mcp-config-file`**（文档/命令行中常被误写为复数）。源码只声明了 `names = {"--mcp-config-file"}`，**未声明 `arity` 或 `split`**——因此必须通过**重复传参**的方式传多个：`--mcp-config-file a.json --mcp-config-file b.json` 得到 `[a.json, b.json]`；`--mcp-config-file "a.json,b.json"` 会被当作**单一路径**（含逗号的文件名）而非分隔符，无法生效。
- **没有 `-m-cf` 之类的短参**——短字母留给了 `-m/--model`。
- **默认值 `new ArrayList<>()` 不是 null**（源码 #86）——`MCPToolProvider.supports` 判断 `!mcpConfigFiles.isEmpty()` 时会返回 `false`，所以未配置时**跳过 MCP 加载**，不会抛错也不会触发 `MCPToolLoader.loadFromFile`。

### 6.2 EngineBuilder 链路

`CliApplication#executeMain`（源码 `CliApplication#154-162`）：

```java
JimiEngine jimiEngine = jimiFactory.createEngine()
        .session(session)
        .agentSpec(agentFile)
        .model(modelName)
        .yolo(yolo)
        .mcpConfigs(mcpConfigFiles)     // ← MCP 配置在这里传入
        .build()
        .block();
```

`JimiFactory.EngineBuilder.mcpConfigs`（源码 `JimiFactory#155-158`）：

```java
public EngineBuilder mcpConfigs(List<Path> mcpConfigFiles) {
    this.mcpConfigFiles = mcpConfigFiles;
    return this;
}
```

`JimiFactory.doCreateEngine` 的第 7 步（源码 `JimiFactory#239-240`）：

```java
// 7. 创建 ToolRegistry（委托给 ToolRegistryFactory）
ToolRegistry toolRegistry = toolRegistryFactory.create(
        jimiRuntime.getBuiltinArgs(), approval, agentSpec, jimiRuntime, mcpConfigFiles);
```

### 6.3 ToolRegistryFactory 的 Provider 分发

`ToolRegistryFactory.create` 先创建基础工具注册表，再 `applyToolProviders` 按 `ToolProvider.getOrder()` 分发（`ToolRegistryFactory#71-103`）：

```java
private void applyToolProviders(ToolRegistry registry, AgentSpec agentSpec,
                                JimiRuntime jimiRuntime, List<Path> mcpConfigFiles) {
    // 对于 MCP 提供者，需要设置配置文件
    toolProviders.stream()
            .filter(p -> p instanceof MCPToolProvider)
            .forEach(p -> ((MCPToolProvider) p).setMcpConfigFiles(mcpConfigFiles));

    // 对于 MetaToolProvider，需要提前注入 ToolRegistry
    toolProviders.stream()
            .filter(p -> p instanceof MetaToolProvider)
            .forEach(p -> ((MetaToolProvider) p).setToolRegistry(registry));

    // ...遍历所有 provider 按 getOrder() 排序后 createTools → registry.register
}
```

**关键事实**：
- **`setMcpConfigFiles` 是每次 `create` 都调一次**——但 `MCPToolProvider` 是 Spring `@Component` 单例，**上一次会话的 mcpConfigFiles 会被新一次 `create` 覆盖**。单进程多会话场景（Jimi 没有这种场景，但架构上可能）下，需警惕该字段的状态共享问题（见 §7.1 M-L8）。
- **Spring `@Service` 的 `MCPToolLoader` 是**真**单例**——多次 `createEngine` 会复用同一个 `MCPToolLoader`，`activeClients` 列表**只会累加不会清理**。如果用户多次调用 `createEngine`（例如嵌入式模式），活跃连接数会增长，直到进程退出才由 `@PreDestroy` 统一释放（见 §7.4 M-H10）。

### 6.4 非 CLI 场景：嵌入式使用

`CliApplication#embeddedMode`（Spring 配置 `jimi.embedded=true`）会**跳过 CLI 启动**。此时第三方代码可以直接拿 `jimiFactory.createEngine()` 组装 engine：

```java
// 嵌入式用法伪代码
JimiEngine engine = jimiFactory.createEngine()
        .session(session)
        .mcpConfigs(List.of(Paths.get("/path/to/mcp-config.json")))
        .build()
        .block();
```

这是把 Jimi 作为库嵌入到 IDE 插件等场景的唯一路径——但注意上面 M-H10 提到的 `activeClients` 累加问题。

## 7 · 设计落差与简化汇总

依 09/10 篇的分类习惯归纳。**符号约定**：M-L* 语义落差、M-S* 命名分歧、M-P* 预留未实装、M-H* 硬编码简化、M-G* 与 MCP 规范对标差距。

### 7.1 语义落差

| # | 位置 | 暗示能力 | 实际行为 |
|---|---|---|---|
| M-L1 | `MCPResultConverter.convert` 的 `isError` 分支 | "返回错误信息给调用方" | `ToolResult.error("MCP tool returned error", "Tool execution error")` —— **完全丢弃服务端返回的 error content**，调用方只看到通用错误字符串，失去所有上下文 |
| M-L2 | `ServerConfig.isStdio()` / `isHttp()` | 二选一配置 | 两个 boolean **不互斥**（一个 ServerConfig 可以同时 `command` 和 `url` 非空），`createClient` **优先 STDIO** 静默忽略 `url`，无警告 |
| M-L3 | `MCPResultConverter` 多内容合并 | "保留所有内容" | 把 `TextPart.text` 和 `ImagePart.url` 用 `\n` 拼接成**同一个字符串**回传——大模型无法区分哪段是图片 URL，图片能力实际不可用 |
| M-L4 | `MCPResultConverter.convertEmbeddedResource` | "转换嵌入资源" | **只处理 `image/*` 类型**，`text/markdown`、`application/json`、`application/pdf` 等全部返回 null 被过滤，**静默丢失** |
| M-L5 | `MCPTool.timeoutSeconds` | "工具执行超时时间" | 构造器接收但 `execute()` **从不读取**——真实超时由协议层 30 秒兜底，`MCPTool` 自己的 20 秒默认值形同虚设 |
| M-L6 | `MCPTool.convertInputSchema` 失败 | "降级使用反射 schema" | 返回 null 时 `ToolRegistry` 回退到反射生成，但 `MCPTool` 参数类型是 `Map<String, Object>`——**反射生成空参数定义**，大模型调用必失败，但只 `log.warn` 不报错 |
| M-L7 | `MCPToolProvider.createTools` 的 `tempRegistry` | "用于加载工具" | `tempRegistry = new ToolRegistry(objectMapper)` 传给 `loadFromFile`，里面 `toolRegistry.register(mcpTool)` 注册进去——但**方法返回后 tempRegistry 被抛弃**，真实注册由上层 `ToolRegistryFactory` 用 `createTools` 返回值完成。`tempRegistry` 是**无意义的副作用容器** |
| M-L8 | `MCPToolProvider.setMcpConfigFiles` | "设置本次会话的 MCP 配置" | `MCPToolProvider` 是 Spring 单例，字段 `mcpConfigFiles` 在多次 `createEngine` 之间**共享且会被覆盖**——嵌入式场景并发创建多个 engine 时存在竞态 |
| M-L9 | `scripts/test-mcp-*.sh` 调用 `--mcp-server` | "把 Jimi 作为 MCP Server" | **Java 代码中根本没有 `--mcp-server` CLI 参数**——脚本指向一个从未实装的功能 |

### 7.2 命名分歧

| # | 位置 | 文档/直觉名称 | 源码真实名称 |
|---|---|---|---|
| M-S1 | CLI 参数 | `--mcp-config` / `--mcp-configs` | `--mcp-config-file`（单数+file 后缀） |
| M-S2 | Builder 方法 | `.mcpConfigFiles(...)` | `.mcpConfigs(List<Path>)`（复数没 file 后缀） |
| M-S3 | 工具类名 | `McpTool` / `MCPClientTool` | `MCPTool`（无 `Client` 前缀，强调是"工具"而非"客户端"） |
| M-S4 | 协议层命名 | `MCPClient` | `JsonRpcClient`（强调传输协议，弱化 MCP 语义） |
| M-S5 | Content 类型常量 | `ContentType.TEXT` 枚举 | 裸字符串 `"text"` / `"image"` / `"resource"`（`TextContent.type = "text"` 字段默认值） |

### 7.3 预留未实装

| # | 位置 | 声明 | 实际未实装 |
|---|---|---|---|
| M-P1 | `scripts/test-mcp-server.sh` + `test-mcp-full.sh` 中的 `--mcp-server` | "Jimi 作为 MCP Server 暴露 `jimi_execute` 工具" | Java 代码（`CliApplication`、任何 `@Option`、任何 Bean）**全无相关实现**——脚本是一个从未兑现的 roadmap 残留 |
| M-P2 | `pom.xml#51-56` 引入 `mcp-bom:0.12.1` 到 `dependencyManagement` | "使用官方 MCP SDK" | `MCPSchema.java#14-16` JavaDoc 明写"替代 `io.modelcontextprotocol.sdk` 中的 `McpSchema`，提供轻量级本地实现"；`MCPTool.java#19` JavaDoc 明写"不依赖 `io.modelcontextprotocol.sdk`"。全仓库 `import io.modelcontextprotocol` **0 次命中**——`mcp-bom` BOM 导入后**没有任何 `<dependency>` 真实依赖它引入的 artifact**，Jimi 自己重写了 Schema/JsonRpcMessage/Client 全套。该 BOM 是**未使用的依赖占位**，移除不会影响任何代码 |
| M-P3 | `MCPToolLoader.loadFromConfig` 注册工具 | "支持多 MCP Server 的工具" | **不加 serverName 前缀**——两个 Server 提供同名工具时后者覆盖前者，没有冲突检测也没有命名空间 |
| M-P4 | `MCPToolLoader` 作为 Spring 单例 | "动态管理 MCP 连接" | **不支持运行时动态加载/卸载**——CLI 启动时一次性读取 `--mcp-config-file`，会话内没有 `/mcp add` 之类的命令 |
| M-P5 | `MCPToolProvider.getOrder() = 60` | "在 SubAgentTool 之后加载" | **同名工具冲突时 MCP 覆盖内置**——例如配置了提供 `Bash` 工具的 MCP Server 会替换掉 Jimi 自带的 `BashTool`，但没有任何警告 |
| M-P6 | `HttpJsonRpcClient.close()` | "关闭 HTTP 客户端" | 实现只有 `log.info("HTTP JSON-RPC client closed")` 一行——**不关闭 WebClient / 不释放连接池**（依赖 Spring ApplicationContext 关闭兜底） |
| M-P7 | `MCPSchema.ResourceContents` 字段 | 有 `uri / mimeType / blob` | **没有 `text` 字段**——MCP 规范允许 `ResourceContents` 含 `text`（非二进制资源），Jimi Schema 缺失该字段，server 返回文本资源会丢失 |

### 7.4 硬编码简化

| # | 位置 | 硬编码值 / 行为 | 影响 |
|---|---|---|---|
| M-H1 | `StdIoJsonRpcClient.sendRequest` | `Thread.sleep(100)` 轮询 | 每个 STDIO 调用最少 100 ms 延迟抖动（简单工具也要 100ms+） |
| M-H2 | `StdIoJsonRpcClient.sendRequest` + `HttpJsonRpcClient.REQUEST_TIMEOUT_SECONDS` | 30 秒硬编码 | 配置文件无 timeout 字段，超长工具（如大模型推理类 MCP）不可用 |
| M-H3 | `StdIoJsonRpcClient.sendRequest` | `synchronized` 方法 | **同一 client 并发调用全串行化**——即使是互不干扰的工具也要排队 |
| M-H4 | `StdIoJsonRpcClient` 构造器 | `pb.redirectError(INHERIT)` | 子进程 stderr 打到 Jimi 自己的 stderr，污染 CLI 输出、可能撞破 TUI 界面 |
| M-H5 | `AbstractJsonRpcClient.initialize/listTools/callTool` | `throw new RuntimeException(...)` | 把 JSON-RPC `Error.code / data` 全丢弃，只保留 `message` 字符串 |
| M-H6 | `StdIoJsonRpcClient.close` | `process.waitFor()` 无上限 | 子进程拒绝 SIGTERM 时 Jimi 退出会卡住；无 `destroyForcibly()` 兜底 |
| M-H7 | `MCPResultConverter.convertImageContent` | `mimeType` 为空时默认 `"image/png"` | base64 实际是 jpeg/webp 时 Data URL 会失效 |
| M-H8 | `JsonRpcMessage.Error` 类 | 未标 `@JsonInclude(NON_NULL)` | 序列化 error 时 `data` 即使 null 也会写 `"data":null`——轻微违反 JSON-RPC 规范（允许省略） |
| M-H9 | `MCPTool.execute` | `Mono.fromCallable` 无 `subscribeOn` | 协议层阻塞调用发生在 `AgentExecutor` 的 reactor 线程里，**阻塞住整个响应式链路** 30 秒 |
| M-H10 | `MCPToolLoader.activeClients` | Spring 单例 + 无清理 | 多次 `createEngine` 的 MCP 连接累加，直到进程退出才统一释放 |
| M-H11 | `AbstractJsonRpcClient.initialize` 的 `clientInfo` | `{"name":"jimi","version":"0.1.0"}` 硬编码 | 无法从 CLI/配置覆盖——与项目实际版本可能漂移（若 `pom.xml` 版本升级但该字符串未同步） |
| M-H12 | `AbstractJsonRpcClient.initialize` 的 `protocolVersion` | `"2024-11-05"` 硬编码 | 新版 MCP 规范（如 2025-03 引入的新能力）完全不可用 |
| M-H13 | `StdIoJsonRpcClient.requestIdCounter` | 从 `1` 开始自增 `AtomicInteger` | 长时间运行可能溢出（21 亿次调用），但也可能因 process 重启而复位——**不是强健的 correlation ID 方案** |

### 7.5 与 MCP 规范的对标差距

| # | 位置 | MCP 规范要求 | Jimi 实际 |
|---|---|---|---|
| M-G1 | 初始化握手 | `initialize request` → `initialize response` → **`notifications/initialized`** | **Jimi 完全不发 `notifications/initialized`**——严格 server 可能拒绝后续请求 |
| M-G2 | HTTP 传输 | 规范要求支持 SSE（`text/event-stream`）用于 server-to-client 通知/采样 | `HttpJsonRpcClient` 只做 `post → response`——**无 SSE 流式** |
| M-G3 | `initialize.capabilities` 声明 | 客户端应声明支持的能力（`roots` / `sampling`） | `capabilities = Map.of()`（空 Map）——所有扩展能力对 server 不可见 |
| M-G4 | `InitializeResult.capabilities/serverInfo` | 客户端应解析 server 能力并差异化处理 | Jimi 以 `Map<String, Object>` 接收但**完全不读**——即使 server 声明支持 `prompts/resources/sampling/logging`，Jimi 也只用 `tools/*` 接口 |
| M-G5 | `notifications/*` 方向 | server 可主动推送 `notifications/tools/list_changed` / `notifications/resources/updated` 等 | Jimi 的 `StdIoJsonRpcClient.readLoop` 只处理**带 id 的 response**（`if (r.getId() != null) responseCache.put`），**id 为 null 的 notification 被丢弃** |
| M-G6 | `tools/call` 错误语义 | `isError: true` 的 content 应保留具体错误信息供客户端展示 | `MCPResultConverter` 直接 `ToolResult.error("MCP tool returned error", "...")`——丢掉 content 里的诊断信息（见 M-L1） |

---

## 8 · 小结

Jimi 的 MCP 集成是一个**只做 Client 方向、只用 `tools/*` 子集、协议版本硬编码 `2024-11-05`** 的极简实现：协议层 `JsonRpcClient` 接口用同步 `throws Exception` 描述 `initialize/listTools/callTool` 三个方法，`AbstractJsonRpcClient` 模板封装这三个方法的通用逻辑（但**缺失 `notifications/initialized` 握手步骤**），`StdIoJsonRpcClient` 用**子进程 + 100ms 轮询 + `synchronized sendRequest`** 实现 STDIO 传输（30 秒超时硬编码，stderr 继承到 Jimi 自己），`HttpJsonRpcClient` 用 WebClient 但 `block()` 退化为同步（无 SSE）；Schema 层以 `MCPSchema` 本地重造（未复用 `mcp-bom:0.12.1` 依赖），`MCPResultConverter` 把服务端返回的文本/图片/嵌入资源合并成单个 `ToolResult` 字符串（图片 URL 和文本用 `\n` 拼接，非图片资源直接丢弃，`isError` 分支丢失服务端错误详情）；桥接层 `MCPTool` 靠 `getCustomParametersSchema` 透传服务端 `inputSchema` 给大模型（否则 `Map<String, Object>` 反射生成不出任何参数信息）、`MCPToolLoader` 作为 Spring 单例统一管理 `activeClients` 并通过 `@PreDestroy` 兜底关闭、`MCPToolProvider` 以 SPI `getOrder() = 60` 在 SubAgentTool 之后注册（同名工具会覆盖内置）。CLI 参数 `--mcp-config-file`（单数）可重复传，沿 `CliApplication → EngineBuilder.mcpConfigs → ToolRegistryFactory.create → MCPToolProvider.setMcpConfigFiles` 链路下发；**`scripts/test-mcp-*.sh` 提到的 `--mcp-server` 和 `jimi_execute` 工具名在 Java 代码中完全不存在**，是典型的未兑现 roadmap。全部偏离：**9 处语义落差 + 5 处命名分歧 + 7 处预留未实装 + 13 处硬编码简化 + 6 处与 MCP 规范对标差距 = 40 条偏离**，意味着任何需要 MCP 高级能力（SSE 流式、多 Server 命名空间、动态加载、notifications 处理、capabilities 协商）的场景都**必须在协议层做增强改造**。
