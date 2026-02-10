package io.leavesfly.jimi.adk.llm.provider;

import io.leavesfly.jimi.adk.api.llm.ChatCompletionChunk;
import io.leavesfly.jimi.adk.api.llm.ChatProvider;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.message.Role;
import io.leavesfly.jimi.adk.api.tool.ToolSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * OpenAI 兼容的聊天提供者
 * 支持所有 OpenAI API 兼容的 LLM 服务
 */
@Slf4j
public class OpenAICompatibleProvider implements ChatProvider {
    
    private final LLMConfig config;
    private final String baseUrl;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    public OpenAICompatibleProvider(LLMConfig config, String baseUrl) {
        this.config = config;
        this.baseUrl = baseUrl;
        this.objectMapper = new ObjectMapper();
        
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
    
    @Override
    public Flux<ChatCompletionChunk> generateStream(String systemPrompt,
                                                     List<Message> history,
                                                     List<ToolSchema> tools) {
        try {
            String requestBody = buildRequestBody(systemPrompt, history, tools);
            
            log.debug("发送请求到 {}/chat/completions", baseUrl);
            
            return webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(ServerSentEvent.class)
                    .timeout(Duration.ofSeconds(config.getReadTimeout()))
                    .filter(sse -> sse.data() != null && !"[DONE]".equals(sse.data()))
                    .map(sse -> parseChunk((String) sse.data()))
                    .filter(chunk -> chunk != null)
                    .doOnError(e -> log.error("LLM 请求失败", e));
                    
        } catch (Exception e) {
            log.error("构建请求失败", e);
            return Flux.error(e);
        }
    }
    
    @Override
    public String getName() {
        return "OpenAI Compatible - " + config.getProvider();
    }
    
    /**
     * 构建请求体
     */
    private String buildRequestBody(String systemPrompt, List<Message> history, List<ToolSchema> tools) 
            throws JsonProcessingException {
        ObjectNode body = objectMapper.createObjectNode();
        
        // 模型
        body.put("model", config.getModel());
        body.put("stream", true);
        body.put("temperature", config.getTemperature());
        
        if (config.getMaxTokens() > 0) {
            body.put("max_tokens", config.getMaxTokens());
        }
        
        // 消息
        ArrayNode messages = objectMapper.createArrayNode();
        
        // 系统消息
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode sysMsg = objectMapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
        }
        
        // 历史消息
        for (Message msg : history) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("role", msg.getRole().toLowercase());
            
            if (msg.getContent() != null) {
                msgNode.put("content", msg.getContent());
            }
            
            // 工具调用
            if (msg.hasToolCalls()) {
                ArrayNode toolCallsNode = objectMapper.createArrayNode();
                msg.getToolCalls().forEach(tc -> {
                    ObjectNode tcNode = objectMapper.createObjectNode();
                    tcNode.put("id", tc.getId());
                    tcNode.put("type", "function");
                    ObjectNode funcNode = objectMapper.createObjectNode();
                    funcNode.put("name", tc.getFunction().getName());
                    funcNode.put("arguments", tc.getFunction().getArguments());
                    tcNode.set("function", funcNode);
                    toolCallsNode.add(tcNode);
                });
                msgNode.set("tool_calls", toolCallsNode);
            }
            
            // 工具结果
            if (msg.getRole() == Role.TOOL && msg.getToolCallId() != null) {
                msgNode.put("tool_call_id", msg.getToolCallId());
            }
            
            messages.add(msgNode);
        }
        
        body.set("messages", messages);
        
        // 工具
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = objectMapper.valueToTree(tools);
            body.set("tools", toolsNode);
        }
        
        return objectMapper.writeValueAsString(body);
    }
    
    /**
     * 解析响应块
     */
    private ChatCompletionChunk parseChunk(String data) {
        try {
            return objectMapper.readValue(data, ChatCompletionChunk.class);
        } catch (JsonProcessingException e) {
            log.debug("解析响应块失败: {}", data);
            return null;
        }
    }
}
