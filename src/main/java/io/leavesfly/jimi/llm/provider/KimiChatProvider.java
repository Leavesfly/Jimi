package io.leavesfly.jimi.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jimi.config.LLMProviderConfig;
import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.ChatProvider;
import io.leavesfly.jimi.llm.RateLimiter;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.ToolCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Kimi Chat Provider 实现
 * 调用 Moonshot API 进行聊天补全
 */
@Slf4j
public class KimiChatProvider implements ChatProvider {

    private final String modelName;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;  // 限流器

    public KimiChatProvider(
            String modelName,
            LLMProviderConfig providerConfig,
            ObjectMapper objectMapper
    ) {
        this.modelName = modelName;
        this.objectMapper = objectMapper;

        // 初始化限流器（如果配置了）
        if (providerConfig.getRateLimit() != null) {
            this.rateLimiter = new RateLimiter(providerConfig.getRateLimit());
            log.info("Kimi ChatProvider rate limiting enabled");
        } else {
            this.rateLimiter = null;
        }

        // 构建 WebClient
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(providerConfig.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + providerConfig.getApiKey())
                .defaultHeader("Content-Type", "application/json");

        // 添加自定义请求头
        if (providerConfig.getCustomHeaders() != null) {
            providerConfig.getCustomHeaders().forEach(builder::defaultHeader);
        }
        this.webClient = builder.build();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Mono<ChatCompletionResult> generate(
            String systemPrompt,
            List<Message> history,
            List<Object> tools
    ) {
        return Mono.defer(() -> {
            try {
                // 应用限流
                applyRateLimit();
                ObjectNode requestBody = buildRequestBody(systemPrompt, history, tools, false);
                return webClient.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .map(this::parseResponse);
            } catch (Exception e) {
                log.error("Failed to generate chat completion", e);
                return Mono.error(new RuntimeException("Failed to generate chat completion", e));
            }
        });
    }

    @Override
    public Flux<ChatCompletionChunk> generateStream(
            String systemPrompt,
            List<Message> history,
            List<Object> tools
    ) {
        return Flux.defer(() -> {
            try {
                // 应用限流
                applyRateLimit();
                ObjectNode requestBody = buildRequestBody(systemPrompt, history, tools, true);
                return webClient.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToFlux(String.class)
                        .filter(line -> line.contains("delta"))
                        .map(line -> line.startsWith("data: ") ? line.substring(6) : line) // 移除 "data: " 前缀
                        .filter(data -> !"[DONE]".equals(data))
                        .map(this::parseStreamChunk);
            } catch (Exception e) {
                log.error("Failed to generate streaming chat completion", e);
                return Flux.error(new RuntimeException("Failed to generate streaming chat completion", e));
            }
        });
    }

    /**
     * 构建请求体
     */
    private ObjectNode buildRequestBody(
            String systemPrompt,
            List<Message> history,
            List<Object> tools,
            boolean stream
    ) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelName);
        body.put("stream", stream);

        // 构建消息列表
        ArrayNode messages = objectMapper.createArrayNode();

        // 添加系统提示词
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
        }

        // 添加历史消息
        for (Message msg : history) {
            messages.add(convertMessage(msg));
        }
        body.set("messages", messages);

        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = objectMapper.valueToTree(tools);
            body.set("tools", toolsArray);
        }
        return body;
    }

    /**
     * 转换消息为 API 格式
     */
    private JsonNode convertMessage(Message msg) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", msg.getRole().getValue());

        boolean hasContent = false;
        boolean hasToolCalls = false;

        // 处理内容
        if (msg.getContent() instanceof String content) {
            if (!content.isEmpty()) {
                node.put("content", content);
                hasContent = true;
            }
        } else if (msg.getContent() instanceof List) {
            @SuppressWarnings("unchecked")
            List<ContentPart> parts = (List<ContentPart>) msg.getContent();
            ArrayNode contentArray = objectMapper.createArrayNode();
            for (ContentPart part : parts) {
                contentArray.add(objectMapper.valueToTree(part));
            }
            node.set("content", contentArray);
            hasContent = true;
        }

        // 处理工具调用
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            // 过滤掉无效的工具调用（id或name为空/null的，或者arguments不是有效JSON的）
            List<ToolCall> validToolCalls = msg.getToolCalls().stream()
                    .filter(tc -> tc != null
                            && tc.getId() != null && !tc.getId().isEmpty()
                            && tc.getFunction() != null
                            && tc.getFunction().getName() != null && !tc.getFunction().getName().isEmpty()
                            && isValidJsonArguments(tc.getFunction().getArguments()))
                    .toList();

            if (!validToolCalls.isEmpty()) {
                node.set("tool_calls", objectMapper.valueToTree(validToolCalls));
                hasToolCalls = true;
            }
        }

        // 对于 assistant 消息，确保至少有 content 或 tool_calls
        // 防止因过滤无效 tool_calls 后导致消息不完整，API 返回 "content field is required" 错误
        if ("assistant".equals(msg.getRole().getValue()) && !hasContent && !hasToolCalls) {
            node.put("content", "-");
            log.warn("Assistant message has neither content nor valid tool_calls, adding empty content");
        }
        // 处理工具调用ID
        if (msg.getToolCallId() != null) {
            node.put("tool_call_id", msg.getToolCallId());
        }
        // 处理名称
        if (msg.getName() != null) {
            node.put("name", msg.getName());
        }
        return node;
    }

    /**
     * 解析非流式响应
     */
    private ChatCompletionResult parseResponse(JsonNode response) {
        JsonNode choice = response.get("choices").get(0);
        JsonNode message = choice.get("message");

        // 解析消息
        Message msg = parseMessage(message);

        // 解析使用统计
        ChatCompletionResult.Usage usage = null;
        if (response.has("usage")) {
            JsonNode usageNode = response.get("usage");
            usage = ChatCompletionResult.Usage.builder()
                    .promptTokens(usageNode.get("prompt_tokens").asInt())
                    .completionTokens(usageNode.get("completion_tokens").asInt())
                    .totalTokens(usageNode.get("total_tokens").asInt())
                    .build();
        }
        return ChatCompletionResult.builder()
                .message(msg)
                .usage(usage)
                .build();
    }

    /**
     * 解析消息
     */
    private Message parseMessage(JsonNode messageNode) {
        String role = messageNode.get("role").asText();
        String content = messageNode.has("content") && !messageNode.get("content").isNull()
                ? messageNode.get("content").asText()
                : null;
        String reasoningContent = messageNode.has("reasoning_content") && !messageNode.get("reasoning_content").isNull()
                ? messageNode.get("reasoning_content").asText()
                : null;

        List<ToolCall> toolCalls = null;
        if (messageNode.has("tool_calls")) {
            toolCalls = new ArrayList<>();
            for (JsonNode tc : messageNode.get("tool_calls")) {
                ToolCall toolCall = ToolCall.builder()
                        .id(tc.get("id").asText())
                        .type(tc.has("type") ? tc.get("type").asText() : "function")
                        .function(FunctionCall.builder()
                                .name(tc.get("function").get("name").asText())
                                .arguments(tc.get("function").get("arguments").asText())
                                .build())
                        .build();
                toolCalls.add(toolCall);
            }
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            return Message.assistant(content, reasoningContent, toolCalls);
        } else {
            return Message.assistant(content, reasoningContent);
        }
    }

    /**
     * 解析流式响应块
     */
    private ChatCompletionChunk parseStreamChunk(String data) {
        try {
            JsonNode chunk = objectMapper.readTree(data);
            JsonNode choice = chunk.get("choices").get(0);
            JsonNode delta = choice.get("delta");

            // 检查是否完成
            if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                ChatCompletionChunk.ChatCompletionChunkBuilder builder = ChatCompletionChunk.builder()
                        .type(ChatCompletionChunk.ChunkType.DONE);
                // 解析使用统计
                if (chunk.has("usage")) {
                    JsonNode usageNode = chunk.get("usage");
                    builder.usage(ChatCompletionResult.Usage.builder()
                            .promptTokens(usageNode.get("prompt_tokens").asInt())
                            .completionTokens(usageNode.get("completion_tokens").asInt())
                            .totalTokens(usageNode.get("total_tokens").asInt())
                            .build());
                }
                return builder.build();
            }
            // 处理内容增量
            if (delta.has("content") && !delta.get("content").isNull()) {
                String contentDelta = delta.get("content").asText();
                if (contentDelta != null && !contentDelta.isEmpty()) {
                    return ChatCompletionChunk.builder()
                            .type(ChatCompletionChunk.ChunkType.CONTENT)
                            .contentDelta(contentDelta)
                            .build();
                }
            }
            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                String contentDelta = delta.get("reasoning_content").asText();
                if (contentDelta != null && !contentDelta.isEmpty()) {
                    return ChatCompletionChunk.builder()
                            .type(ChatCompletionChunk.ChunkType.REASONING)
                            .contentDelta(contentDelta)
                            .build();
                }
            }
            // 处理工具调用
            if (delta.has("tool_calls")) {
                JsonNode toolCall = delta.get("tool_calls").get(0);
                return ChatCompletionChunk.builder()
                        .type(ChatCompletionChunk.ChunkType.TOOL_CALL)
                        .toolCallId(toolCall.has("id") ? toolCall.get("id").asText() : null)
                        .functionName(toolCall.has("function") && toolCall.get("function").has("name")
                                ? toolCall.get("function").get("name").asText()
                                : null)
                        .argumentsDelta(toolCall.has("function") && toolCall.get("function").has("arguments")
                                ? toolCall.get("function").get("arguments").asText()
                                : null)
                        .build();
            }
            // 默认返回空内容块
            return ChatCompletionChunk.builder()
                    .type(ChatCompletionChunk.ChunkType.CONTENT)
                    .contentDelta("")
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse stream chunk: {}", data, e);
            return ChatCompletionChunk.builder()
                    .type(ChatCompletionChunk.ChunkType.CONTENT)
                    .contentDelta("")
                    .build();
        }
    }

    /**
     * 校验 arguments 是否为有效的 JSON 格式
     */
    private boolean isValidJsonArguments(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            log.warn("ToolCall arguments is null or empty");
            return false;
        }

        try {
            objectMapper.readTree(arguments);
            return true;
        } catch (Exception e) {
            log.warn("Invalid JSON format in ToolCall arguments: {}", arguments, e);
            return false;
        }
    }

    /**
     * 应用限流（如果配置了）
     */
    private void applyRateLimit() {
        if (rateLimiter != null) {
            rateLimiter.acquirePermit();
        }
    }
}
