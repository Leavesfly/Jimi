# IntelliJ 插件集成方案

## 目标
让 IntelliJ 插件实现与 Jimi CLI 一样的完整交互能力,包括元命令、流式输出、审批机制和会话管理。

## 当前架构问题

### 现状
```
IntelliJ Plugin → MCP Client → Jimi MCP Server → jimi_execute 工具
```

**存在的问题:**
1. 只能执行任务,无法使用元命令(`/help`、`/status`等)
2. 输出是一次性返回,没有实时流式反馈
3. 需要审批的操作无法交互
4. 每次调用都创建新会话,无法保持上下文

### CLI 的完整能力

CLI 通过以下组件实现丰富交互:

1. **ShellUI + InputProcessor** - 输入处理管道
   - `MetaCommandProcessor`: 处理 `/help`、`/status` 等元命令
   - `ShellShortcutProcessor`: 处理 `! command` Shell快捷方式
   - `AgentCommandProcessor`: 处理普通用户查询

2. **Wire 消息总线** - 实时事件流
   - `StepBegin`: 步骤开始
   - `ContentPartMessage`: 流式文本输出
   - `ToolCallMessage`: 工具调用
   - `ApprovalRequest`: 审批请求

3. **CommandRegistry** - 元命令系统
   - 17+ 个命令处理器(HelpCommand, StatusCommand, etc.)

4. **JimiEngine + AgentExecutor** - 核心执行引擎
   - 支持多轮对话
   - 上下文管理
   - LLM 集成

## 方案对比

### 方案一:增强 MCP 集成(推荐)

**核心思路:** 扩展 MCP 协议,让插件通过 MCP 访问完整的 CLI 能力

**架构:**
```
IntelliJ Plugin
    ↓
McpClient (双向通信)
    ↓
Jimi MCP Server (扩展版)
    ├── Meta Commands (/help, /status, etc.)
    ├── Session Management (会话持久化)
    ├── Stream Output (实时输出)
    └── Approval Interaction (审批交互)
```

**实现步骤:**

#### 1. 扩展 MCP Server 添加新工具

在 `McpToolRegistry.java` 中添加:

```java
// 1. 执行元命令
tools.add(MCPSchema.Tool.builder()
    .name("jimi_command")
    .description("Execute Jimi meta commands like /help, /status, /config")
    .inputSchema(Map.of(
        "type", "object",
        "properties", Map.of(
            "command", Map.of("type", "string", "description", "Meta command name (help/status/config/tools/reset/compact)"),
            "args", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Command arguments")
        ),
        "required", List.of("command")
    ))
    .build()
);

// 2. 会话管理
tools.add(MCPSchema.Tool.builder()
    .name("jimi_session")
    .description("Manage Jimi sessions")
    .inputSchema(Map.of(
        "type", "object",
        "properties", Map.of(
            "action", Map.of("type", "string", "enum", List.of("create", "continue", "list")),
            "sessionId", Map.of("type", "string", "description", "Session ID")
        ),
        "required", List.of("action")
    ))
    .build()
);

// 3. 流式执行(带通知)
tools.add(MCPSchema.Tool.builder()
    .name("jimi_execute_stream")
    .description("Execute Jimi task with streaming output")
    .inputSchema(Map.of(
        "type", "object",
        "properties", Map.of(
            "input", Map.of("type", "string"),
            "sessionId", Map.of("type", "string"),
            "workDir", Map.of("type", "string")
        ),
        "required", List.of("input")
    ))
    .build()
);
```

#### 2. 实现元命令执行

```java
private MCPSchema.CallToolResult executeMetaCommand(Map<String, Object> arguments) {
    String commandName = (String) arguments.get("command");
    List<String> args = (List<String>) arguments.getOrDefault("args", List.of());
    
    // 构建 CommandContext
    CommandContext context = CommandContext.builder()
        .soul(currentEngine)  // 使用持久化的Engine
        .commandName(commandName)
        .args(args.toArray(new String[0]))
        .outputFormatter(new StringOutputFormatter())
        .build();
    
    // 执行命令
    CommandHandler handler = commandRegistry.getHandler(commandName);
    if (handler == null) {
        return errorResult("Unknown command: " + commandName);
    }
    
    handler.execute(context);
    
    return successResult(context.getOutputFormatter().toString());
}
```

#### 3. 会话持久化

```java
// 在 McpToolRegistry 中维护会话
private final Map<String, JimiEngine> sessions = new ConcurrentHashMap<>();

private MCPSchema.CallToolResult manageSession(Map<String, Object> arguments) {
    String action = (String) arguments.get("action");
    String sessionId = (String) arguments.get("sessionId");
    
    switch (action) {
        case "create":
            sessionId = UUID.randomUUID().toString();
            JimiEngine engine = createEngine(sessionId);
            sessions.put(sessionId, engine);
            return successResult("Created session: " + sessionId);
            
        case "continue":
            if (!sessions.containsKey(sessionId)) {
                return errorResult("Session not found: " + sessionId);
            }
            return successResult("Using session: " + sessionId);
            
        case "list":
            return successResult("Active sessions: " + String.join(", ", sessions.keySet()));
    }
}
```

#### 4. 流式输出(使用 Server-Sent Events)

由于 MCP 的 StdIO 模式是请求-响应,流式输出需要特殊处理:

**选项 A: 轮询方式**
```java
// 客户端定期调用获取最新输出
tools.add(MCPSchema.Tool.builder()
    .name("jimi_get_output")
    .description("Get latest output from running task")
    .inputSchema(Map.of(
        "type", "object",
        "properties", Map.of(
            "sessionId", Map.of("type", "string"),
            "since", Map.of("type", "integer", "description", "Get output since this index")
        )
    ))
    .build()
);
```

**选项 B: 完整输出缓存**
```java
// Wire 事件收集到缓冲区
StringBuilder outputBuffer = new StringBuilder();
engine.getWire().asFlux()
    .filter(msg -> msg instanceof ContentPartMessage)
    .subscribe(msg -> {
        ContentPartMessage cpm = (ContentPartMessage) msg;
        if (cpm.getContentPart() instanceof TextPart) {
            String text = ((TextPart) cpm.getContentPart()).getText();
            outputBuffer.append(text);
        }
    });
```

#### 5. 审批机制交互

```java
tools.add(MCPSchema.Tool.builder()
    .name("jimi_approval")
    .description("Handle approval requests")
    .inputSchema(Map.of(
        "type", "object",
        "properties", Map.of(
            "sessionId", Map.of("type", "string"),
            "action", Map.of("type", "string", "enum", List.of("approve", "reject", "list"))
        )
    ))
    .build()
);

// 实现
private MCPSchema.CallToolResult handleApproval(Map<String, Object> arguments) {
    String sessionId = (String) arguments.get("sessionId");
    String action = (String) arguments.get("action");
    
    JimiEngine engine = sessions.get(sessionId);
    Approval approval = engine.getRuntime().getApproval();
    
    switch (action) {
        case "list":
            // 返回待审批项目列表
            List<ApprovalRequest> pending = approval.getPendingRequests();
            return successResult(formatApprovalList(pending));
            
        case "approve":
            approval.approve();
            return successResult("Approved");
            
        case "reject":
            approval.reject();
            return successResult("Rejected");
    }
}
```

#### 6. IntelliJ 插件端改造

**新增交互式面板:**

```kotlin
class JimiInteractivePanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val outputArea = JBTextArea()
    private val inputField = JTextField()
    private val sessionId = AtomicReference<String>()
    
    init {
        // 启动时创建会话
        val service = JimiPluginService.getInstance(project)
        val result = service.createSession()
        sessionId.set(result.sessionId)
        
        appendOutput("Session created: ${sessionId.get()}\n")
        appendOutput("Type /help for commands\n")
    }
    
    private fun executeInput(input: String) {
        val service = JimiPluginService.getInstance(project)
        
        // 检查是否是元命令
        if (input.startsWith("/")) {
            executeMetaCommand(input, service)
        } else {
            executeTask(input, service)
        }
    }
    
    private fun executeMetaCommand(input: String, service: JimiPluginService) {
        val parts = input.substring(1).split(" ", limit = 2)
        val command = parts[0]
        val args = if (parts.size > 1) parts[1].split(" ") else emptyList()
        
        val result = service.executeCommand(sessionId.get(), command, args)
        appendOutput("\n$result\n")
    }
    
    private fun executeTask(input: String, service: JimiPluginService) {
        appendOutput("\n> $input\n")
        
        // 后台执行
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = service.executeTask(sessionId.get(), input)
            
            SwingUtilities.invokeLater {
                appendOutput("\n$result\n")
            }
        }
    }
}
```

**服务层扩展:**

```kotlin
class JimiPluginService(private val project: Project) {
    
    fun createSession(): SessionResult {
        val result = mcpClient!!.callTool(
            "jimi_session",
            mapOf("action" to "create")
        )
        return SessionResult(extractSessionId(result))
    }
    
    fun executeCommand(sessionId: String, command: String, args: List<String>): String {
        val result = mcpClient!!.callTool(
            "jimi_command",
            mapOf(
                "command" to command,
                "args" to args,
                "sessionId" to sessionId
            )
        )
        return result.content.firstOrNull()?.text ?: ""
    }
    
    fun executeTask(sessionId: String, input: String): String {
        val result = mcpClient!!.callTool(
            "jimi_execute",
            mapOf(
                "input" to input,
                "sessionId" to sessionId,
                "workDir" to projectPath
            )
        )
        return result.content.firstOrNull()?.text ?: ""
    }
    
    fun handleApproval(sessionId: String, action: String): String {
        val result = mcpClient!!.callTool(
            "jimi_approval",
            mapOf(
                "sessionId" to sessionId,
                "action" to action
            )
        )
        return result.content.firstOrNull()?.text ?: ""
    }
}
```

**优点:**
- ✅ 复用现有 MCP 架构
- ✅ Server 端改动集中在 McpToolRegistry
- ✅ 支持所有元命令
- ✅ 会话持久化
- ✅ 相对简单

**缺点:**
- ❌ 流式输出需要轮询或缓存
- ❌ 审批交互不够实时
- ❌ MCP 协议本身的请求-响应限制

---

### 方案二:直接集成 Jimi Core(更彻底)

**核心思路:** 在插件中直接依赖 Jimi 的核心库,不通过 MCP

**架构:**
```
IntelliJ Plugin
    ↓ (直接依赖)
Jimi Core JAR
    ├── JimiEngine
    ├── AgentExecutor
    ├── ToolRegistry
    ├── CommandRegistry
    └── Wire (事件总线)
```

**实现步骤:**

#### 1. Gradle 依赖配置

```kotlin
// intellij-plugin/build.gradle.kts
dependencies {
    // 依赖 Jimi Core
    implementation(files("../target/jimi-0.1.0.jar"))
    
    // 或者使用本地 Maven
    implementation("io.leavesfly:jimi-core:0.1.0")
    
    // 传递依赖
    implementation("org.springframework.boot:spring-boot-starter-webflux:3.2.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.2")
    // ... 其他依赖
}
```

#### 2. 创建 Jimi 管理服务

```kotlin
@Service(Service.Level.PROJECT)
class JimiEngineService(private val project: Project) {
    
    private var engine: JimiEngine? = null
    private var wireSubscription: Disposable? = null
    private val outputCallbacks = mutableListOf<(String) -> Unit>()
    
    fun initialize(): Boolean {
        try {
            val workDir = Paths.get(project.basePath ?: ".")
            
            // 创建 JimiFactory
            val config = loadConfig()
            val jimiFactory = JimiFactory(config, ...)
            
            // 创建会话
            val session = Session.builder()
                .id(UUID.randomUUID().toString())
                .workDir(workDir)
                .historyFile(workDir.resolve(".jimi/history.jsonl"))
                .build()
            
            // 创建 Engine
            engine = jimiFactory.createEngine()
                .session(session)
                .build()
                .block()
            
            // 订阅 Wire 事件
            wireSubscription = engine.wire.asFlux()
                .subscribe { message ->
                    handleWireMessage(message)
                }
            
            return true
            
        } catch (e: Exception) {
            println("Failed to initialize Jimi: ${e.message}")
            return false
        }
    }
    
    private fun handleWireMessage(message: WireMessage) {
        when (message) {
            is ContentPartMessage -> {
                val content = message.contentPart
                if (content is TextPart) {
                    notifyOutput(content.text)
                }
            }
            is ToolCallMessage -> {
                notifyOutput("\n[Tool: ${message.toolCall.function.name}]\n")
            }
            is ApprovalRequest -> {
                handleApprovalRequest(message)
            }
            // ... 其他消息类型
        }
    }
    
    fun executeCommand(command: String, args: List<String>) {
        val handler = commandRegistry.getHandler(command)
        if (handler != null) {
            val context = buildCommandContext(command, args)
            handler.execute(context)
        }
    }
    
    fun executeTask(input: String): Mono<Void> {
        return engine?.run(input) ?: Mono.error(Exception("Engine not initialized"))
    }
    
    fun registerOutputCallback(callback: (String) -> Unit) {
        outputCallbacks.add(callback)
    }
    
    private fun notifyOutput(text: String) {
        outputCallbacks.forEach { it(text) }
    }
}
```

#### 3. UI 面板(完全交互式)

```kotlin
class JimiInteractivePanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val engineService = JimiEngineService.getInstance(project)
    private val outputArea = JBTextArea()
    private val inputField = JTextField()
    
    init {
        // 初始化 Engine
        if (!engineService.initialize()) {
            outputArea.append("Failed to initialize Jimi\n")
            return
        }
        
        // 注册输出回调
        engineService.registerOutputCallback { text ->
            SwingUtilities.invokeLater {
                outputArea.append(text)
                outputArea.caretPosition = outputArea.document.length
            }
        }
        
        outputArea.append("Jimi initialized. Type /help for commands.\n")
        
        // 布局
        add(JBScrollPane(outputArea), BorderLayout.CENTER)
        
        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(inputField, BorderLayout.CENTER)
        val sendButton = JButton("Send")
        sendButton.addActionListener { executeInput() }
        inputPanel.add(sendButton, BorderLayout.EAST)
        add(inputPanel, BorderLayout.SOUTH)
        
        inputField.addActionListener { executeInput() }
    }
    
    private fun executeInput() {
        val input = inputField.text.trim()
        if (input.isEmpty()) return
        
        inputField.text = ""
        outputArea.append("\n> $input\n")
        
        // 后台执行
        ApplicationManager.getApplication().executeOnPooledThread {
            if (input.startsWith("/")) {
                // 元命令
                val parts = input.substring(1).split(" ", limit = 2)
                val command = parts[0]
                val args = if (parts.size > 1) parts[1].split(" ") else emptyList()
                engineService.executeCommand(command, args)
            } else {
                // 普通任务
                engineService.executeTask(input).block()
            }
        }
    }
}
```

**优点:**
- ✅ **完全访问** Jimi 的所有功能
- ✅ 真正的流式输出(通过 Wire)
- ✅ 实时审批交互
- ✅ 性能更好(无 IPC 开销)
- ✅ 调试更容易

**缺点:**
- ❌ 依赖复杂(需要引入 Spring Boot 等)
- ❌ 插件体积大
- ❌ 可能与 IntelliJ 的类加载器冲突
- ❌ 需要处理 Java 17 兼容性

---

## 推荐方案

综合考虑,**推荐方案一(增强 MCP 集成)**:

1. **快速实现** - 基于现有架构,只需扩展 MCP Server
2. **隔离性好** - Jimi 作为独立进程,不影响 IDE 稳定性
3. **可维护** - 改动集中,易于测试
4. **渐进式** - 可以先实现元命令,再逐步添加流式输出

## 实施计划

### 第一阶段:元命令支持(1-2天)
- [ ] 扩展 McpToolRegistry 添加 `jimi_command` 工具
- [ ] 实现元命令执行逻辑
- [ ] 插件端添加命令输入识别

### 第二阶段:会话管理(1天)
- [ ] 添加 `jimi_session` 工具
- [ ] Server 端维护会话映射
- [ ] 插件端会话生命周期管理

### 第三阶段:流式输出(2-3天)
- [ ] 实现输出缓存机制
- [ ] 添加 `jimi_get_output` 工具
- [ ] 插件端轮询显示

### 第四阶段:审批交互(1-2天)
- [ ] 添加 `jimi_approval` 工具
- [ ] 实现审批队列
- [ ] 插件端审批对话框

## 示例效果

实现后,用户可以在 IntelliJ 中:

```
> /help
┌────────────────────────────────────────┐
│          Jimi CLI Help                 │
└────────────────────────────────────────┘

✓ 基本命令:
  exit, quit      - 退出 Jimi
  ! <command>     - 直接运行 Shell 命令

✓ 元命令 (Meta Commands):
  /help, /h, /?   - 显示帮助信息
  /status         - 显示当前状态
  ...

> /status
✓ 系统状态:
  状态: ✅ ready
  Agent: Default Agent
  可用工具数: 15
  上下文消息数: 42

> 帮我重构这个方法
[Jimi thinking...]
[Tool: ReadFile]
好的,我分析了代码...
```

这样就实现了与 CLI 一致的交互体验!
