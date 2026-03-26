package io.leavesfly.jimi.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 流式响应处理器
 * 负责解析 OpenAI 兼容 API 的流式响应（SSE）
 */
@Slf4j
public class StreamResponseProcessor {

    private final ObjectMapper objectMapper;
    private final String providerName;
    private final ThinkTagParser thinkTagParser;

    // API错误标志位，用于立即终止流
    private volatile boolean apiErrorOccurred = false;

    public StreamResponseProcessor(ObjectMapper objectMapper, String providerName) {
        this.objectMapper = objectMapper;
        this.providerName = providerName;
        this.thinkTagParser = new ThinkTagParser();
    }

    /**
     * 重置处理器状态
     * 每次新请求前调用
     */
    public void reset() {
        thinkTagParser.reset();
        apiErrorOccurred = false;
    }

    /**
     * 检查是否已发生API错误
     */
    public boolean hasApiError() {
        return apiErrorOccurred;
    }

    /**
     * 解析流式响应块
     *
     * @param data SSE数据（JSON字符串）
     * @return 解析后的 ChatCompletionChunk
     */
    public ChatCompletionChunk parseChunk(String data) {
        try {
            JsonNode chunk = objectMapper.readTree(data);

            // 1. 检查是否为错误响应
            ChatCompletionChunk errorChunk = parseErrorResponse(chunk);
            if (errorChunk != null) {
                return errorChunk;
            }

            // 2. 检查 choices 是否存在且非空
            if (!hasValidChoices(chunk, data)) {
                return createEmptyContentChunk();
            }

            JsonNode choice = chunk.get("choices").get(0);
            JsonNode delta = choice.get("delta");

            // 3. 检查是否完成
            ChatCompletionChunk doneChunk = parseFinishReason(choice, chunk);
            if (doneChunk != null) {
                return doneChunk;
            }

            // 4. 检查 delta 是否有效
            if (delta == null || delta.isNull()) {
                return createEmptyContentChunk();
            }

            // 5. 处理推理内容
            ChatCompletionChunk reasoningChunk = parseReasoningContent(delta);
            if (reasoningChunk != null) {
                return reasoningChunk;
            }

            // 6. 处理普通内容
            ChatCompletionChunk contentChunk = parseContentDelta(delta);
            if (contentChunk != null) {
                return contentChunk;
            }

            // 7. 处理工具调用
            ChatCompletionChunk toolCallChunk = parseToolCalls(delta);
            if (toolCallChunk != null) {
                return toolCallChunk;
            }

            // 8. 默认返回空内容块
            return createEmptyContentChunk();

        } catch (Exception e) {
            // 静默处理解析错误，返回空内容继续流程
            return createEmptyContentChunk();
        }
    }

    /**
     * 检查并解析错误响应
     */
    private ChatCompletionChunk parseErrorResponse(JsonNode chunk) {
        if (chunk.has("type") && "error".equals(chunk.get("type").asText())) {
            handleApiError(chunk);
            apiErrorOccurred = true;
            return ChatCompletionChunk.builder()
                    .type(ChatCompletionChunk.ChunkType.DONE)
                    .build();
        }
        return null;
    }

    /**
     * 检查 choices 是否有效
     */
    private boolean hasValidChoices(JsonNode chunk, String data) {
        if (!chunk.has("choices") || chunk.get("choices").isNull() || chunk.get("choices").isEmpty()) {
            log.warn("{} stream chunk missing choices: {}", providerName, data);
            return false;
        }
        return true;
    }

    /**
     * 解析 finish_reason 并创建 DONE chunk
     */
    private ChatCompletionChunk parseFinishReason(JsonNode choice, JsonNode chunk) {
        if (!choice.has("finish_reason") || choice.get("finish_reason").isNull()) {
            return null;
        }

        ChatCompletionChunk.ChatCompletionChunkBuilder builder = ChatCompletionChunk.builder()
                .type(ChatCompletionChunk.ChunkType.DONE);

        // 解析使用统计
        if (chunk.has("usage") && !chunk.get("usage").isNull()) {
            JsonNode usageNode = chunk.get("usage");
            if (usageNode.has("prompt_tokens") && usageNode.has("completion_tokens") 
                    && usageNode.has("total_tokens")) {
                builder.usage(ChatCompletionResult.Usage.builder()
                        .promptTokens(usageNode.get("prompt_tokens").asInt())
                        .completionTokens(usageNode.get("completion_tokens").asInt())
                        .totalTokens(usageNode.get("total_tokens").asInt())
                        .build());
            }
        }

        return builder.build();
    }

    /**
     * 解析推理内容（支持多种字段名）
     * 1. reasoning_content - DeepSeek-R1 使用
     * 2. reasoning - Ollama qwen3-thinking 使用
     */
    private ChatCompletionChunk parseReasoningContent(JsonNode delta) {
        String reasoningField = null;
        
        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
            reasoningField = delta.get("reasoning_content").asText();
        } else if (delta.has("reasoning") && !delta.get("reasoning").isNull()) {
            reasoningField = delta.get("reasoning").asText();
        }

        if (reasoningField != null && !reasoningField.isEmpty()) {
            return ChatCompletionChunk.builder()
                    .type(ChatCompletionChunk.ChunkType.CONTENT)
                    .contentDelta(reasoningField)
                    .isReasoning(true)
                    .build();
        }
        return null;
    }

    /**
     * 解析普通内容增量，使用 ThinkTagParser 处理 <think> 标签
     */
    private ChatCompletionChunk parseContentDelta(JsonNode delta) {
        if (!delta.has("content") || delta.get("content").isNull()) {
            return null;
        }
        
        String contentDelta = delta.get("content").asText();
        if (contentDelta == null || contentDelta.isEmpty()) {
            return null;
        }
        
        // 使用 ThinkTagParser 解析 <think> 标签
        return thinkTagParser.parse(contentDelta);
    }

    /**
     * 解析工具调用
     */
    private ChatCompletionChunk parseToolCalls(JsonNode delta) {
        if (!delta.has("tool_calls")) {
            return null;
        }
        
        JsonNode toolCallsArray = delta.get("tool_calls");
        if (!toolCallsArray.isArray() || toolCallsArray.isEmpty()) {
            return null;
        }
        
        JsonNode toolCall = toolCallsArray.get(0);
        String toolCallId = toolCall.has("id") ? toolCall.get("id").asText() : null;
        String functionName = null;
        String argumentsDelta = null;

        if (toolCall.has("function")) {
            JsonNode function = toolCall.get("function");
            if (function.has("name") && !function.get("name").isNull()) {
                functionName = function.get("name").asText();
            }
            if (function.has("arguments") && !function.get("arguments").isNull()) {
                argumentsDelta = function.get("arguments").asText();
            }
        }

        return ChatCompletionChunk.builder()
                .type(ChatCompletionChunk.ChunkType.TOOL_CALL)
                .toolCallId(toolCallId)
                .functionName(functionName)
                .argumentsDelta(argumentsDelta)
                .build();
    }

    /**
     * 创建空内容块
     */
    private ChatCompletionChunk createEmptyContentChunk() {
        return ChatCompletionChunk.builder()
                .type(ChatCompletionChunk.ChunkType.CONTENT)
                .contentDelta("")
                .build();
    }

    /**
     * 处理API错误响应
     * 输出友好的错误提示（不包含堆栈信息）
     */
    private void handleApiError(JsonNode errorResponse) {
        if (!errorResponse.has("error")) {
            log.warn("{} API 返回错误响应", providerName);
            return;
        }

        JsonNode error = errorResponse.get("error");
        String errorType = error.has("type") ? error.get("type").asText() : "unknown";
        String httpCode = error.has("http_code") ? error.get("http_code").asText() : "unknown";

        // 根据错误类型输出友好提示（不包含堆栈）
        switch (errorType) {
            case "insufficient_balance_error":
                log.warn("\n========================================\n" +
                                "{} API Error: 账户余额不足\n" +
                                "解决方法: 请前往 {} 平台充值账户余额\n" +
                                "========================================",
                        providerName, providerName);
                break;
            case "rate_limit_error":
                log.warn("{} API Error: 请求频率超限，请稍后重试", providerName);
                break;
            case "invalid_api_key":
            case "authentication_error":
                log.warn("{} API Error: API密钥无效，请检查配置", providerName);
                break;
            case "model_not_found":
            case "invalid_model":
                log.warn("{} API Error: 模型不存在，请检查配置中的model参数", providerName);
                break;
            case "context_length_exceeded":
                log.warn("{} API Error: 上下文长度超限，请使用 /compress 或 /clear 命令", providerName);
                break;
            case "server_error":
            case "internal_error":
                log.warn("{} API Error: 服务器内部错误，请稍后重试", providerName);
                break;
            default:
                log.warn("{} API Error: {} ({})", providerName, errorType, httpCode);
                break;
        }
    }
}
