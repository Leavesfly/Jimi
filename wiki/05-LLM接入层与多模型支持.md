# 05 · LLM 接入层与多模型支持

> LLM 是 ReAct 循环的"大脑"。Jimi 用 `ChatProvider` 抽象接入了 **9 种 provider 类型**（OpenAI 兼容 + Kimi/Moonshot + Cursor CLI），用 `LLMFactory` + Caffeine 缓存做实例复用，用 `RateLimiter` + `TokenCounter` + `ThinkTagParser` 补齐生产级 LLM 客户端需要的全部能力。

---

## 1. LLM 层结构总览

源码位于 `io.leavesfly.jimi.llm.*`：

```
llm/
├── LLM.java                   // 薄包装：ChatProvider + maxContextSize + getModelName()
├── ChatProvider.java          // SPI 接口：generate / generateStream
├── ChatCompletionResult.java  // 非流式结果：Message + Usage(prompt/completion/total)
├── ChatCompletionChunk.java   // 流式块：CONTENT / REASONING / TOOL_CALL / DONE
├── LLMFactory.java            // @Service：Caffeine 缓存 + 配置校验 + 环境变量覆盖
├── RateLimiter.java           // 滑动窗口限流（synchronized，线程安全）
├── TokenCounter.java          // 混合策略 Token 估算（CJK + word + number + 标点）
├── message/                   // OpenAI 兼容的消息模型
│   ├── Message.java           // role + content + reasoning_content + tool_calls + tool_call_id
│   ├── MessageRole.java       // SYSTEM / USER / ASSISTANT / TOOL
│   ├── ContentPart.java       // 多模态内容基类
│   ├── TextPart.java / ImagePart.java
│   ├── ToolCall.java          // id + type + function
│   └── FunctionCall.java      // name + arguments(JSON 字符串)
└── provider/                  // 3 种 ChatProvider 实现
    ├── OpenAICompatibleChatProvider.java  // 通用实现，覆盖 DEEPSEEK/QWEN/OLLAMA/OPENAI/CLAUDE/GLM/MINIMAX
    ├── KimiChatProvider.java              // Moonshot（Kimi）专用
    ├── CursorChatProvider.java            // 走本地 cursor-agent CLI 子进程
    ├── CursorProcessExecutor.java         // Cursor CLI 进程执行器
    ├── StreamResponseProcessor.java       // SSE 流式解析器
    └── ThinkTagParser.java                // <think></think> 标签状态机
```

**设计分层**：

| 层 | 职责 |
|----|------|
| `LLM`（数据对象）| 对外暴露给 `JimiRuntime` / `AgentExecutor` 的唯一 LLM 句柄；只带 `chatProvider` + `maxContextSize` |
| `ChatProvider`（接口）| 3 个方法：`getModelName` / `generate`（同步一次返回）/ `generateStream`（流式 Flux） |
| `LLMFactory`（服务）| `@Service`，负责按 `modelName` 解析 `LLMProviderConfig`，选择具体 provider 实现，并用 Caffeine 缓存 `LLM` 实例 |
| Provider（实现）| 真正和远端 API / 本地 CLI 打交道 |
| 辅助模块 | `RateLimiter`、`StreamResponseProcessor`、`ThinkTagParser`、`TokenCounter` |

---

## 2. `ChatProvider` 接口契约

```java
public interface ChatProvider {
    String getModelName();

    Mono<ChatCompletionResult> generate(
        String systemPrompt,
        List<Message> history,
        List<Object> tools              // JSON Schema 列表（由 ToolRegistry.getToolSchemas 产生）
    );

    Flux<ChatCompletionChunk> generateStream(
        String systemPrompt,
        List<Message> history,
        List<Object> tools
    );
}
```

### 2.1 `ChatCompletionResult`

非流式结果：

| 字段 | 类型 | 说明 |
|------|------|------|
| `message` | `Message` | assistant 返回的完整消息，可能含 `tool_calls` |
| `usage` | `Usage` | `{ promptTokens, completionTokens, totalTokens }` |

### 2.2 `ChatCompletionChunk`

流式块。枚举 `ChunkType` **只有 4 种**：

| `ChunkType` | 载荷字段 | 触发条件 |
|-------------|---------|---------|
| `CONTENT` | `contentDelta` | 正常回答的 token 增量；`<think>` 外的内容 |
| `REASONING` | `contentDelta`（和 `isReasoning=true` 共存） | `<think>` 标签内的推理流，或 provider 返回 `reasoning_content` 字段 |
| `TOOL_CALL` | `toolCallId` / `functionName` / `argumentsDelta` | 工具调用增量（tool call 的 JSON 参数是分片到达的） |
| `DONE` | `usage`（可选） | 流结束；可能附带 provider 返回的 token 统计 |

> **注意**：源码里 `CONTENT` 块内部还有一个 `isReasoning` 布尔位——由 `ThinkTagParser` 解析 `<think>` 标签后设置（见 §6）。因此 **推理流在实现上有两条路径**：`ChunkType.REASONING`（provider 直接吐 `reasoning_content` 字段）或 `ChunkType.CONTENT` + `isReasoning=true`（从 `<think>...</think>` 包裹文本里提取）。

### 2.3 `LLM` 薄包装

```java
@Data @Builder
public class LLM {
    private ChatProvider chatProvider;
    private int maxContextSize;       // 来自 LLMModelConfig.max_context_size（YAML 配置）

    public String getModelName() { return chatProvider.getModelName(); }

    public Mono<String> complete(String prompt) { ... } // 便捷方法：一次性纯文本补全
}
```

`maxContextSize` 被 **02 篇 §6** 的 `ContextManager.checkAndCompact` 用来判断是否触发历史压缩（阈值 = `maxContextSize - EngineConstants.RESERVED_TOKENS`，其中 `RESERVED_TOKENS=20_000`）。

---

## 3. `LLMFactory`：Caffeine 缓存 + 环境变量覆盖

```
@Service
LLMFactory
├── Cache<String, LLM> llmCache          // Caffeine，maxSize=10，expireAfterAccess=30min，recordStats
├── validateConfiguration()              // 启动时 fail-fast 检查 defaultModel
├── getOrCreateLLM(modelName)            // 主入口
├── createLLM(modelName)                 // 缓存未命中时走这里
├── createChatProvider(type, model, cfg) // switch(ProviderType) 选择实现
└── resolveApiKey(providerConfig)        // 环境变量 {PROVIDER_TYPE}_API_KEY 优先
```

### 3.1 缓存策略

```java
this.llmCache = Caffeine.newBuilder()
    .maximumSize(10)
    .expireAfterAccess(30, TimeUnit.MINUTES)
    .recordStats()
    .removalListener((key, value, cause) -> log.debug(...))
    .build();
```

- **容量上限 10 个模型实例**——多 Agent 切不同模型时自动复用
- **30 分钟未访问即过期**——释放长期不用的 WebClient / HttpClient 连接池
- `recordStats()` 启用 `stats.hitRate()` / `missCount` / `evictionCount` 统计，由 `getCacheStats()` 对外暴露
- `clearCache()` 提供热重载能力（用于配置改动后清空缓存）

### 3.2 `createLLM` 流程

```
1. modelName → LLMModelConfig（配置文件 models 映射）
     ↓ 找不到 → ConfigException
2. modelConfig.provider → LLMProviderConfig（配置文件 providers 映射）
     ↓ 找不到 → ConfigException
3. resolveApiKey(providerConfig)
     - 环境变量 "{TYPE_UPPER}_API_KEY"（如 DEEPSEEK_API_KEY）优先
     - 否则用 providerConfig.apiKey，但若是占位符 "xxxx" 视为空
4. 仅 QWEN 强制要求 apiKey 非空（其他 provider 允许为空，如 OLLAMA 本地不需要）
     ↓ QWEN 无 key → ConfigException
5. 重建一个带 envApiKey 的 LLMProviderConfig（不污染原始配置对象）
6. createChatProvider(type, model, effectiveCfg)
7. LLM.builder().chatProvider(...).maxContextSize(modelConfig.maxContextSize).build()
```

### 3.3 `createChatProvider` 的三分支 switch

```java
switch (type) {
    case KIMI:    return new KimiChatProvider(model, config, objectMapper);
    case CURSOR:  return new CursorChatProvider(model, config, objectMapper);
    default:      return new OpenAICompatibleChatProvider(
                          model, config, objectMapper, formatProviderLabel(type));
}
```

> `ProviderType` 枚举有 9 个值（KIMI/DEEPSEEK/QWEN/OLLAMA/OPENAI/CLAUDE/CURSOR/GLM/MINIMAX），但只有 3 个实现类——DEEPSEEK/QWEN/OLLAMA/OPENAI/CLAUDE/GLM/MINIMAX **共享 `OpenAICompatibleChatProvider`**，差异通过不同 `baseUrl` / `apiKey` / `customHeaders` / `formatProviderLabel` 体现。

### 3.4 `formatProviderLabel`（友好日志名）

`DEEPSEEK → "Deepseek"`、`OPENAI → "OpenAI"`、`MINIMAX → "MiniMax"`、`CLAUDE → "Claude"`……只是用来让日志更好看，不影响行为。

---

## 4. Provider 实现 1：`OpenAICompatibleChatProvider`

覆盖 7 种 provider（DEEPSEEK/QWEN/OLLAMA/OPENAI/CLAUDE/GLM/MINIMAX）。

### 4.1 WebClient 配置

```java
HttpClient httpClient = HttpClient.create()
    .resolver(DefaultAddressResolverGroup.INSTANCE);   // 关键点：用 JVM 原生 DNS

WebClient.Builder builder = WebClient.builder()
    .clientConnector(new ReactorClientHttpConnector(httpClient))
    .baseUrl(providerConfig.getBaseUrl())
    .defaultHeader("Content-Type", "application/json");

if (apiKey 非空) builder.defaultHeader("Authorization", "Bearer " + apiKey);
providerConfig.getCustomHeaders().forEach(builder::defaultHeader);
```

> **为什么要强制 JVM 原生 DNS？** 源码注释写明：Netty 自带 DNS 解析器在某些公司内网（如阿里内网）会解析失败，强制用 `DefaultAddressResolverGroup.INSTANCE` 走 JVM 的 `InetAddress` 解析避坑。

### 4.2 非流式 `generate`

```
applyRateLimit()    // RateLimiter（可选）
  ↓
buildRequestBody(systemPrompt, history, tools, stream=false)
  ↓
POST /chat/completions
  ↓
JsonNode → parseResponse() → ChatCompletionResult
  ↓
onErrorResume: WebClientResponseException 打印状态码+响应体
```

### 4.3 流式 `generateStream`

核心流水线（`Flux` 链式操作）：

```
applyRateLimit()
  ↓
streamProcessor.reset()                  // 清空 ThinkTagParser 状态、apiErrorOccurred
  ↓
webClient.post()..bodyToFlux(String.class)
  ↓
.filter(line -> {                        // 过滤空行和 [DONE] 哨兵
    if (line.trim().isEmpty()) return false;
    if (line.equals("[DONE]")) return false;
    if (line.equals("data: [DONE]")) return false;
    return true;
})
  ↓
.map(line -> line.startsWith("data: ")   // 兼容两种格式：有/无 "data: " 前缀
    ? line.substring(6).trim()
    : line)
  ↓
.flatMap(data -> {
    if (streamProcessor.hasApiError()) return Mono.empty();   // API 错误熔断
    ChatCompletionChunk chunk = streamProcessor.parseChunk(data);
    return Mono.just(chunk);
})
  ↓
.takeUntil(chunk -> chunk.getType() == DONE)   // 关键：遇到 DONE 立即终止（含这条 DONE）
  ↓
.onErrorResume(e -> Flux.just(DONE))           // 静默兜底：任何异常转成 DONE 正常结束
```

### 4.4 请求体字段

`buildRequestBody` 拼装的字段**只有 4 个**（源码核实，没有 `stream_options` 等额外字段）：

| 字段 | 值 | 条件 |
|------|-----|------|
| `model` | `modelName` | 必有 |
| `stream` | `true` / `false` | 必有 |
| `messages` | `[systemMsg?, ...history convertMessage 后]` | 必有 |
| `tools` | `[...JSON Schema...]` | 仅当 `tools` 非空且 `supportsTools()` 为 `true` |

> `supportsTools()` 当前实现**总是返回 `true`**——源码保留了注释掉的 Ollama 分支（`if ("Ollama".equals(providerName)) return false`），未来按需启用。也就是说：**当前版本 Ollama 也会收到 tools 字段**，如果底层模型不支持，provider 会抛错。

### 4.5 `convertMessage`：防 400 错误的消息消毒

OpenAI 兼容 API 对 `tool_calls` 字段校验严格，`convertMessage` 做了两层过滤：

1. **过滤无效 tool_calls**：跳过 `id` / `function.name` 空、或 `arguments` 不是合法 JSON 的调用
2. **防空 assistant 消息**：若 assistant 消息既无 `content` 又无有效 `tool_calls`，自动补 `content=""`（OpenAI 兼容）或 `content="-"`（Kimi），避免 `"content field is required"` 错误

---

## 5. Provider 实现 2：`KimiChatProvider`（Moonshot）

`KimiChatProvider` 是**独立实现**，并没有复用 `OpenAICompatibleChatProvider`。虽然入参签名完全遵循 `ChatProvider` 接口，但内部细节与通用实现有几处明确差异：

### 5.1 和通用实现的真实差异（源码核实）

| 维度 | `OpenAICompatibleChatProvider` | `KimiChatProvider` |
|------|-------------------------------|--------------------|
| 流式行过滤 | `.filter(line -> !empty && !"[DONE]" && !"data: [DONE]")` | `.filter(line -> line.contains("delta"))` |
| SSE 前缀处理 | `substring(6).trim()` 并容忍无前缀 | `startsWith("data: ") ? substring(6) : line` |
| 流式解析器 | 专用 `StreamResponseProcessor` + `ThinkTagParser` | 内嵌私有方法 `parseStreamChunk(String data)` |
| DONE 终止 | `.takeUntil(DONE)` + `.onErrorResume` 返回 DONE | 直接 `.filter(!"[DONE]")` |
| HttpClient DNS | 强制 JVM 原生 DNS | 未定制 `HttpClient` |
| 空 assistant 消息补齐 | `content = ""` | `content = "-"` |
| `tool_calls` 消毒 | 有（同上） | 有（同上） |

> 换句话说：**"Kimi SSE 格式不同"是真的，但 Jimi 的处理方式是完全独立解析——没有共享 ThinkTagParser**，因此 Kimi 流式输出里的 `<think>` 标签不会被自动剥离（需依赖 Kimi API 返回 `reasoning_content` 字段）。

### 5.2 关键事实

- **BaseURL**：由 `providers.<name>.base_url` 配置（官方 `https://api.moonshot.cn/v1`）
- **鉴权**：构造器里无条件写 `Authorization: Bearer ${apiKey}`——**若 apiKey 为 null 会变成 `"Bearer null"`**，实际调用必然失败，因此对 Kimi 请确保 `KIMI_API_KEY` 环境变量或配置文件已设置
- **`reasoning_content` 字段解析**：`parseMessage()` 显式读取 `reasoning_content`，产出 `Message` 时会通过 `Message.assistant(content, reasoning)` 的四参重载保留推理内容

---

## 6. Provider 实现 3：`CursorChatProvider`（本地 CLI 桥接）

**Cursor 不是 HTTP API**，而是通过子进程调用本地 `cursor-agent` CLI 可执行文件。

### 6.1 模型名映射

`CursorChatProvider` 维护了一份硬编码 `MODEL_MAPPING`，把 Jimi 配置里的模型名映射到 Cursor CLI 的模型 ID：

| Jimi 配置名 | Cursor CLI 模型 ID |
|------------|-------------------|
| `auto` | `auto` |
| `gpt-5` | `gpt-5` |
| `gpt-5-codex` | `gpt-5-codex` |
| `sonnet` / `sonnet-4.5` | `sonnet-4.5` |
| `opus` / `opus-4.1` | `opus-4.1` |
| `opus-4.5` | `opus-4.5` |
| `o1-preview` | `sonnet-4.5-thinking` |
| `composer` | `composer-1` |

### 6.2 执行方式

- **CLI 命令**：从 `customHeaders.cursor_cli_path` 读取，默认 `cursor-agent`
- **默认超时**：`DEFAULT_TIMEOUT = 1_800_000ms`（30 分钟）——被 `generate` 和 `generateStream` 传入 `processExecutor.execute(...)` 作为子进程超时
- **依赖检测**：`CursorProcessExecutor.isCliInstalled(cliCommand)`——构造器里检测，未安装只打 `WARN` 不 `fail-fast`
- **工作目录注入**：通过 **Reactor Context**（`ctx.getOrDefault("workDir", user.dir)`）拿到当前 Jimi 的工作目录并传给 `cursor-agent`，这样 Cursor 在同一 workspace 下运行
- **流式输出**：子进程按行（JSONL）吐出，每行交给私有 `parseStreamJson(line)` → 返回 `ParsedLine { content, chunk, usage }` 三元组

### 6.3 Prompt 拼接（扁平化 Markdown）

Cursor CLI 接收的是一段**扁平的 Markdown 文本**（`buildPrompt`），而不是 OpenAI 风格的 messages 数组：

```
# System

<systemPrompt>

# User

<user msg>

# Assistant

<assistant msg>

# User

<user msg>
...
```

**关键取舍**：
- 只保留 `user` / `assistant` 两种 role；**`tool` 角色消息直接丢弃**（`if ("tool".equals(role)) continue`）
- 多模态消息只取 `TextPart` 文本部分，图片等被忽略

### 6.4 ⚠️ 重大限制：不支持工具调用

```java
if (tools != null && !tools.isEmpty()) {
    log.warn("Cursor does not support tool calls, tools will be ignored");
}
```

`generate` 和 `generateStream` 开头都会**静默丢弃 tools 参数**，只打 WARN。这意味着：

- 配成 Cursor 的 Agent **不能使用 Jimi 的任何工具**（ReadFile/WriteFile/Grep/BashTool 全部失效）
- 真实的工具调用发生在 Cursor CLI 内部（由 Cursor 自己的工具完成），但 Jimi 的 Hook/Approval/Session 等机制**管不到那一层**
- 因此 Cursor provider 更适合**纯文本推理**场景（如 brainstorm、review），而不是代码编辑主循环

这也是 Jimi 能"借用" Cursor 订阅额度调用 GPT-5 / Claude Opus 等模型的原因——本地 CLI 本身已经替你完成了鉴权，代价是失去对工具循环的控制权。

---

## 7. `StreamResponseProcessor`：SSE 流解析

`OpenAICompatibleChatProvider` / `KimiChatProvider` 都将每个 SSE `data: {...}` 行交给 `StreamResponseProcessor.parseChunk(data)`。

### 7.1 解析顺序（源码 1→8 有严格优先级）

```
1. 是否为 error 响应（chunk.type == "error"）？
     → 标 apiErrorOccurred=true，返回 DONE 块
2. choices 非空？
     → 否则返回空 CONTENT 块
3. choice.finish_reason 存在？
     → 返回 DONE 块（携带 usage，若有）
4. delta 有效？
     → 否则返回空 CONTENT 块
5. delta.reasoning_content 非空？
     → 返回 REASONING 块
6. delta.content 非空？
     → 交给 ThinkTagParser.parse()，返回 CONTENT 块（可能带 isReasoning=true）
7. delta.tool_calls 非空？
     → 返回 TOOL_CALL 块（包含 toolCallId / functionName / argumentsDelta）
8. 兜底：空 CONTENT 块
```

### 7.2 状态位：`apiErrorOccurred`

一旦识别到 `chunk.type == "error"`，立即：
1. `handleApiError(chunk)` 打印错误明细
2. `apiErrorOccurred = true`
3. 立即返回 `DONE` 终止下游

这保证 **API 侧返回的错误会立刻中断 ReAct 流式消费**，而不是等整个 Flux 超时。

---

## 8. `ThinkTagParser`：`<think>` 标签状态机

DeepSeek-R1、Qwen-QwQ 等推理模型返回纯文本时，会用 `<think>...</think>` 包裹内部思考。Jimi 不希望这些内容被当作最终回答展示给用户，所以必须在流式解析阶段分离。

### 8.1 字段

```java
private volatile boolean insideThinkTag = false;
private volatile StringBuilder thinkTagBuffer = new StringBuilder();
```

### 8.2 核心难点：跨 chunk 的标签边界

SSE 每次可能只给 `<thin` 或者 `k>` 半个标签——状态机的设计要点：
- 累积缓冲区 `thinkTagBuffer`
- 遍历缓冲内容，遇到 `<think>` 翻状态 `insideThinkTag=true`；遇到 `</think>` 翻回 `false`
- **如果缓冲尾部可能是半个标签**（剩余长度 < 8 字节且是 `<think>` / `</think>` 的前缀），暂停处理，保留到下一次 chunk 合并后再解析
- 返回的 `ChatCompletionChunk` 带 `isReasoning = insideThinkTag 的首字符状态`（不是最后一个字符的状态，避免跨标签时错判）

### 8.3 reset 时机

每次新请求前，`StreamResponseProcessor.reset()` 会级联调用 `thinkTagParser.reset()`，防止上一次请求残留的半标签影响下一次。

---

## 9. `RateLimiter`：滑动窗口限流

```java
public class RateLimiter {
    private final long windowMs;           // 窗口毫秒数
    private final int maxRequests;         // 窗口内最多允许几次请求
    private final long sleepMs;            // 超限时休眠多久
    private final Queue<Long> requestTimestamps = new LinkedList<>();  // 所有请求时间戳

    public synchronized void acquirePermit() {
        long now = System.currentTimeMillis();
        // 1. 清理窗口外旧记录
        while (!requestTimestamps.isEmpty() && now - requestTimestamps.peek() >= windowMs) {
            requestTimestamps.poll();
        }
        // 2. 若超限，sleep(sleepMs)，然后再次清理
        if (requestTimestamps.size() >= maxRequests) {
            Thread.sleep(sleepMs);
            // ... 清理窗口外记录 ...
        }
        // 3. 记录本次时间戳
        requestTimestamps.offer(now);
    }
}
```

- 整个方法 `synchronized`——线程安全但存在**可见的线程阻塞**（`Thread.sleep`），对响应式调度不友好，但实现简单够用
- `InterruptedException` 被捕获，只恢复中断标志，不抛出
- 通过 `LLMProviderConfig.rate_limit` 配置启用（不配置就 = 不限流）

### 9.1 典型 YAML 配置

```yaml
providers:
  deepseek:
    type: deepseek
    base_url: https://api.deepseek.com/v1
    api_key: xxxx
    rate_limit:
      window_ms: 60000      # 1 分钟窗口
      max_requests: 30      # 最多 30 次
      sleep_ms: 2000        # 超限时睡 2 秒
```

---

## 10. `TokenCounter`：自研混合估算

不依赖任何 JNI / tiktoken SDK，纯 Java 实现 OpenAI `cl100k_base` 的**近似**估算。

### 10.1 消息级开销

```java
MESSAGE_OVERHEAD_TOKENS = 4          // role 标记、分隔符
TOOL_CALL_OVERHEAD_TOKENS = 8        // function name、id、type 标记
```

### 10.2 文本估算的混合策略（见源码 `estimateTextTokens`）

| 字符类别 | 估算规则 |
|---------|----------|
| CJK（中日韩，`[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff\uac00-\ud7af]`） | 每字 `1.5 tokens` |
| 英文单词（`[a-zA-Z]+`） | `≤4 字符 → 1`，`5-10 字符 → 1.5`，`>10 字符 → ceil(len/5)` |
| 数字（`\d+`） | `ceil(len/3)` tokens |
| 其他标点/特殊字符 | 每字 `0.5` tokens |
| 换行符 | 每个 `0.5` tokens |

### 10.3 JSON 参数

工具调用参数按 `estimateTextTokens(json) * 1.2` 估算（JSON 结构字符会多占约 20%）。

### 10.4 总计

```java
public static int estimateTokens(List<Message> messages) {
    int total = 3;   // priming tokens
    for (Message m : messages) total += estimateTokens(m);
    return total;
}
```

这份估算用于 `ContextManager` 判断上下文压缩阈值、UI 展示大致成本，**不是**用于准确计费。真实 token 数以 provider 返回的 `usage` 为准。

---

## 11. 消息模型：兼容 OpenAI

`io.leavesfly.jimi.llm.message.Message` 完全对齐 OpenAI 规范，字段包括：

| JSON 字段 | Java 字段 | 适用 role |
|----------|-----------|---------|
| `role` | `role: MessageRole` | 所有 |
| `content` | `content: Object`（String / `List<ContentPart>` / Map） | 所有 |
| `reasoning_content` | `reasoning: Object` | assistant（可选） |
| `tool_calls` | `toolCalls: List<ToolCall>` | assistant |
| `tool_call_id` | `toolCallId: String` | tool |
| `name` | `name: String` | 可选 |

`@JsonInclude(JsonInclude.Include.NON_NULL)` 保证 null 字段不序列化到请求体——让 Ollama / GLM 等严格校验的 provider 不会因多余字段报错。

### 11.1 静态工厂方法（常用）

```java
Message.user("文本")                                        // 纯用户消息
Message.user(List<ContentPart>)                            // 多模态（含图片）
Message.assistant(content)                                 // 纯文本 assistant
Message.assistant(content, reasoning)                      // 带推理内容
Message.assistant(content, toolCalls)                      // 带工具调用
Message.assistant(content, reasoning, toolCalls)           // 全量
Message.system(content)                                    // 系统消息
Message.tool(toolCallId, content)                          // 工具返回
```

### 11.2 `getTextContent()` 的兼容性处理

`content` 字段允许是 `String` / `List<ContentPart>` / `Map` 三种形态（不同 provider 返回差异大）。`getTextContent()` 内部做兜底：
- `String` → 直接返回
- `Map` → 取 `text` 键或 `content` 键
- `List<TextPart | Map | String>` → 拼接所有文本片段

这保证上层代码（如 `ReactLoop.extractFinalResponse`）不用 care 底层 JSON 形态差异。

---

## 12. YAML 配置示例

结合 **01 篇 §3** 和本篇内容，一份完整的多 provider 配置如下（`~/.jimi/config.yml` 或项目根目录 `config.yml`）：

```yaml
jimi:
  default-model: deepseek-chat

  providers:
    deepseek:
      type: deepseek
      base_url: https://api.deepseek.com/v1
      api_key: xxxx                    # 占位符 "xxxx" 等同空，读环境变量 DEEPSEEK_API_KEY
      rate_limit:
        window_ms: 60000
        max_requests: 30
        sleep_ms: 2000

    qwen:
      type: qwen
      base_url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api_key: xxxx                    # QWEN 若最终为空会 fail-fast

    ollama:
      type: ollama
      base_url: http://localhost:11434/v1
      # 无 api_key 也 OK

    kimi:
      type: kimi
      base_url: https://api.moonshot.cn/v1
      api_key: xxxx

    cursor:
      type: cursor
      custom_headers:
        cursor_cli_path: /usr/local/bin/cursor-agent

  models:
    deepseek-chat:
      provider: deepseek
      model: deepseek-chat
      max_context_size: 128000

    qwen-max:
      provider: qwen
      model: qwen-max
      max_context_size: 32000

    kimi-k2:
      provider: kimi
      model: kimi-k2-0711-preview
      max_context_size: 200000

    gpt-5:
      provider: cursor
      model: gpt-5
      max_context_size: 256000
```

> 环境变量优先级高于配置文件：变量名按 `{PROVIDER_TYPE.toUpperCase()}_API_KEY` 模板生成（如 `DEEPSEEK_API_KEY` / `QWEN_API_KEY` / `KIMI_API_KEY` / `OPENAI_API_KEY` / `GLM_API_KEY` / `MINIMAX_API_KEY` / `CLAUDE_API_KEY`）。注意 Cursor 走本地 CLI 不使用 API key，虽然 `resolveApiKey` 通用逻辑仍会尝试读取 `CURSOR_API_KEY`，但该值不会被 `CursorChatProvider` 使用。

---

## 13. 扩展：如何新增一个 Provider

### 13.1 场景 A · 目标 provider 兼容 OpenAI API

最省事——**不用写代码**。只需：

1. 往 `ProviderType` 枚举加一个常量 + `@JsonProperty` 注解
2. 在 `LLMFactory.createChatProvider` 的 `default` 分支内，现有通用实现会自动处理

步骤 1 示例（往 `LLMProviderConfig.ProviderType` 追加）：

```java
@JsonProperty("zhipu") ZHIPU
```

步骤 2 · `config.yml` 里配：

```yaml
providers:
  zhipu:
    type: zhipu
    base_url: https://open.bigmodel.cn/api/paas/v4
    api_key: ${ZHIPU_API_KEY}
```

重启即可。

### 13.2 场景 B · 目标 provider 有定制 SSE 协议

模仿 `KimiChatProvider`：

1. 实现 `ChatProvider` 接口
2. 在 `ProviderType` 加枚举值
3. 在 `LLMFactory.createChatProvider` 的 switch 新增一条分支

### 13.3 场景 C · 走本地 CLI 或其他 RPC

模仿 `CursorChatProvider`：
1. 写一个 `XxxProcessExecutor` 封装进程 / RPC
2. 实现 `ChatProvider`，把每行 JSONL 转成 `ChatCompletionChunk`
3. 注册到 `LLMFactory`

---

## 14. 故障排查速查

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 启动日志 `No default model configured` | `jimi.default-model` 未设置或配置为空 | 配置 `config.yml` 的 `default-model` |
| 启动日志 `Default model '...' not found in configuration` | `default-model` 指向的 key 不在 `models` 映射里 | 补全 `models.<name>` 段 |
| `ConfigException: Missing API key for provider: QWEN` | Qwen 特殊：必须有 key | 设环境变量 `QWEN_API_KEY` 或填配置 |
| 请求卡住 / DNS 超时 | Netty 默认 DNS 在某些内网失败 | 已通过 `DefaultAddressResolverGroup.INSTANCE` 修复；若仍失败检查 `/etc/resolv.conf` |
| 流式返回里混入 `<think>...</think>` | 推理模型原样吐标签 | 框架会自动通过 `ThinkTagParser` 分离为 `isReasoning=true` 块 |
| 工具调用 JSON 参数被截断 | SSE 按 chunk 分片，arguments 需累积 | `ReactLoop` 内部已做 `ToolCall` 合并；ToolCall 参数是 incremental delta |
| Cursor provider 的 Agent 调工具无反应 | `CursorChatProvider` 会静默丢弃 tools 参数 | 改用 OpenAI 兼容 / Kimi provider；或 Agent 不用 Cursor 做主循环 |
| `Cursor CLI not found` | 环境里没装 cursor-agent | 从 `https://cursor.com/cli` 安装；或改用其他 provider |
| Kimi 推理过程丢失 | `KimiChatProvider` 未集成 `ThinkTagParser`，`<think>` 标签不被剥离 | 依赖 Kimi 自身 `reasoning_content` 字段；若模型不返回该字段则 `<think>` 会进入 content 原样显示 |
| 400 `content field is required` | assistant 消息既无 content 又无合法 tool_calls | 已通过 `convertMessage` 自动补占位符防范，若仍报错检查 tool_calls 的 arguments 是否合法 JSON |
| 缓存统计怎么看 | `LLMFactory.getCacheStats()` | 可暴露到 `/debug` 端点查看命中率 |

---

## 15. 关键文件速查

| 文件 | 作用 |
|------|------|
| `llm/LLM.java` | LLM 句柄（含 maxContextSize） |
| `llm/ChatProvider.java` | SPI 接口（3 方法） |
| `llm/LLMFactory.java` | Caffeine 缓存 + 工厂 + 环境变量 |
| `llm/RateLimiter.java` | 滑动窗口限流（synchronized） |
| `llm/TokenCounter.java` | 混合策略 Token 估算 |
| `llm/ChatCompletionResult.java` | 非流式结果（含 Usage） |
| `llm/ChatCompletionChunk.java` | 流式块（4 种 ChunkType） |
| `llm/provider/OpenAICompatibleChatProvider.java` | 通用实现（覆盖 7 种 type） |
| `llm/provider/KimiChatProvider.java` | Kimi / Moonshot 专用 |
| `llm/provider/CursorChatProvider.java` | Cursor 本地 CLI 桥接 |
| `llm/provider/CursorProcessExecutor.java` | Cursor 子进程管理 |
| `llm/provider/StreamResponseProcessor.java` | SSE 解析状态机 |
| `llm/provider/ThinkTagParser.java` | `<think>` 标签状态机 |
| `llm/message/Message.java` | OpenAI 兼容消息模型 |
| `config/info/LLMProviderConfig.java` | Provider 配置 + ProviderType 枚举 |
| `config/info/LLMModelConfig.java` | 模型配置（provider/model/max_context_size） |

---

**[⬅ 上一篇：04 · 工具系统与 ToolRegistry](04-工具系统与ToolRegistry.md)** | **[回到首页](Home.md)** | **[下一篇：06 · Skills 技能包系统 ➡](06-Skills技能包系统.md)**
