package io.leavesfly.jimi.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jimi.config.LLMProviderConfig;
import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.ChatProvider;
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
 * OpenAI 兼容 Chat Provider
 * 支持 DeepSeek、Qwen、Ollama 等兼容 OpenAI API 的服务
 */
@Slf4j
public class OpenAICompatibleChatProvider implements ChatProvider {

    private final String modelName;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String providerName;

    public OpenAICompatibleChatProvider(
            String modelName,
            LLMProviderConfig providerConfig,
            ObjectMapper objectMapper,
            String providerName
    ) {
        this.modelName = modelName;
        this.objectMapper = objectMapper;
        this.providerName = providerName;

        // 构建 WebClient
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(providerConfig.getBaseUrl())
                .defaultHeader("Content-Type", "application/json");

        // 添加 API Key（如果有）
        if (providerConfig.getApiKey() != null && !providerConfig.getApiKey().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + providerConfig.getApiKey());
        }

        // 添加自定义请求头
        if (providerConfig.getCustomHeaders() != null) {
            providerConfig.getCustomHeaders().forEach(builder::defaultHeader);
        }

        this.webClient = builder.build();

        log.info("Created {} ChatProvider: model={}, baseUrl={}",
                providerName, modelName, providerConfig.getBaseUrl());
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
                ObjectNode requestBody = buildRequestBody(systemPrompt, history, tools, false);

                return webClient.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .map(this::parseResponse)
                        .onErrorResume(e -> {
                            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                                org.springframework.web.reactive.function.client.WebClientResponseException webEx = 
                                    (org.springframework.web.reactive.function.client.WebClientResponseException) e;
                                log.error("{} API error: status={}, body={}", 
                                        providerName, webEx.getStatusCode(), webEx.getResponseBodyAsString());
                            } else {
                                log.error("{} API error", providerName, e);
                            }
                            return Mono.error(e);
                        });

            } catch (Exception e) {
                log.error("Failed to generate chat completion with {}", providerName, e);
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
                ObjectNode requestBody = buildRequestBody(systemPrompt, history, tools, true);
                log.debug("Sending streaming request to {}, body: {}", providerName, requestBody);
                
                // 记录完整的请求体以便调试
                if (tools != null && !tools.isEmpty()) {
                    log.info("{} request with {} tools, full body: {}", providerName, tools.size(), requestBody.toPrettyString());
                }

                return webClient.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToFlux(String.class)
                        .doOnNext(line -> log.debug("Received SSE line: {}", line))
                        .filter(line -> {
                            // 支持两种格式：1) data: {json}  2) {json}
                            if (line.trim().isEmpty()) return false;
                            if (line.equals("[DONE]")) return false;
                            if (line.equals("data: [DONE]")) return false;
                            return true;
                        })
                        .map(line -> {
                            // 处理 SSE 格式：如果有 data: 前缀则移除
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                return data.equals("[DONE]") ? null : data;
                            }
                            return line;
                        })
                        .filter(data -> data != null && !data.isEmpty())
                        .flatMap(data -> {
                            // 使用 flatMap 替代 map,以便捕获单个chunk的解析错误而不中断整个流
                            try {
                                ChatCompletionChunk chunk = parseStreamChunk(data);
                                log.debug("Parsed chunk: type={}, contentDelta={}", 
                                        chunk.getType(), chunk.getContentDelta());
                                return Mono.just(chunk);
                            } catch (Exception e) {
                                log.warn("Failed to parse stream chunk, skipping: {}", data, e);
                                // 返回空流,跳过这个错误的chunk,不中断整个流
                                return Mono.empty();
                            }
                        })
                        .onErrorResume(e -> {
                            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                                org.springframework.web.reactive.function.client.WebClientResponseException webEx = 
                                    (org.springframework.web.reactive.function.client.WebClientResponseException) e;
                                log.error("{} streaming API error: status={}, body={}", 
                                        providerName, webEx.getStatusCode(), webEx.getResponseBodyAsString());
                            } else {
                                log.error("{} streaming API error: {}", providerName, e.getMessage());
                                log.debug("Streaming error details", e);
                            }
                            return Flux.error(e);
                        });

            } catch (Exception e) {
                log.error("Failed to generate streaming chat completion with {}", providerName, e);
                return Flux.error(new RuntimeException("Failed to generate streaming chat completion", e));
            }
        });
    }

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

        // 添加工具定义（仅当提供商支持时）
        if (tools != null && !tools.isEmpty() && supportsTools()) {
            ArrayNode toolsArray = objectMapper.valueToTree(tools);
            body.set("tools", toolsArray);
        }

        return body;
    }

    /**
     * 检查提供商是否支持工具调用
     * Ollama 部分模型不支持，需要特殊处理
     */
    private boolean supportsTools() {
        // // Ollama 默认不支持工具调用
        // if ("Ollama".equals(providerName)) {
        //     return false;
        // }
        return true;
    }

    private JsonNode convertMessage(Message msg) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", msg.getRole().getValue());

        // 处理内容
        if (msg.getContent() instanceof String) {
            node.put("content", (String) msg.getContent());
        } else if (msg.getContent() instanceof List) {
            // 直接将 List 转换为 JsonNode，避免类型转换问题
            // Jackson 会自动处理 ContentPart 或 LinkedHashMap
            node.set("content", objectMapper.valueToTree(msg.getContent()));
        }

        // 处理工具调用 - 过滤无效的工具调用
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            // 过滤掉无效的工具调用（id或name为空/null的）
            List<ToolCall> validToolCalls = msg.getToolCalls().stream()
                    .filter(tc -> tc != null 
                            && tc.getId() != null && !tc.getId().isEmpty()
                            && tc.getFunction() != null
                            && tc.getFunction().getName() != null && !tc.getFunction().getName().isEmpty())
                    .toList();
            
            if (!validToolCalls.isEmpty()) {
                node.set("tool_calls", objectMapper.valueToTree(validToolCalls));
            }
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

    private Message parseMessage(JsonNode messageNode) {
        String role = messageNode.get("role").asText();
        String content = messageNode.has("content") && !messageNode.get("content").isNull()
                ? messageNode.get("content").asText()
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
            return Message.assistant(content, toolCalls);
        } else {
            return Message.assistant(content);
        }
    }

    private ChatCompletionChunk parseStreamChunk(String data) {
        try {
            JsonNode chunk = objectMapper.readTree(data);
            
            // 检查 choices 是否存在且非空
            if (!chunk.has("choices") || chunk.get("choices").isNull() || chunk.get("choices").isEmpty()) {
                log.warn("Stream chunk missing choices: {}", data);
                return ChatCompletionChunk.builder()
                        .type(ChatCompletionChunk.ChunkType.CONTENT)
                        .contentDelta("")
                        .build();
            }
            
            JsonNode choice = chunk.get("choices").get(0);
            JsonNode delta = choice.get("delta");

            // 检查是否完成
            if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                ChatCompletionChunk.ChatCompletionChunkBuilder builder = ChatCompletionChunk.builder()
                        .type(ChatCompletionChunk.ChunkType.DONE);

                // 解析使用统计
                if (chunk.has("usage") && !chunk.get("usage").isNull()) {
                    JsonNode usageNode = chunk.get("usage");
                    // 检查每个字段是否存在
                    if (usageNode.has("prompt_tokens") && usageNode.has("completion_tokens") && usageNode.has("total_tokens")) {
                        builder.usage(ChatCompletionResult.Usage.builder()
                                .promptTokens(usageNode.get("prompt_tokens").asInt())
                                .completionTokens(usageNode.get("completion_tokens").asInt())
                                .totalTokens(usageNode.get("total_tokens").asInt())
                                .build());
                    }
                }

                return builder.build();
            }
            
            // 检查 delta 是否存在
            if (delta == null || delta.isNull()) {
                return ChatCompletionChunk.builder()
                        .type(ChatCompletionChunk.ChunkType.CONTENT)
                        .contentDelta("")
                        .build();
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
}
