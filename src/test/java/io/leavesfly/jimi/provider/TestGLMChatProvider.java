package io.leavesfly.jimi.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.ConfigLoader;
import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.provider.OpenAICompatibleChatProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GLM ChatProvider 测试
 * 验证GLM模型的基础调用功能
 */
@Slf4j
public class TestGLMChatProvider {
    
    @Test
    public void testStreamingResponse() {
        ObjectMapper objectMapper = new ObjectMapper();
        JimiConfig jimiConfig = new ConfigLoader(objectMapper).loadConfig(null);
        
        // 检查GLM配置是否存在
        if (!jimiConfig.getProviders().containsKey("glm")) {
            log.warn("GLM provider not configured, skipping test");
            return;
        }
        
        OpenAICompatibleChatProvider provider = new OpenAICompatibleChatProvider(
                "glm-4.7",
                jimiConfig.getProviders().get("glm"),
                objectMapper,
                "GLM"
        );

        log.info("Testing GLM streaming response...");
        
        AtomicBoolean reasoning = new AtomicBoolean(false);
        provider.generateStream(
                        "你是一个智能AI助手",
                        Collections.singletonList(Message.user("你好,请用一句话介绍你自己")),
                        null
                )
                .doOnEach(chunk -> {
                    ChatCompletionChunk rt = chunk.get();
                    if (rt != null && rt.getType() == ChatCompletionChunk.ChunkType.CONTENT) {
                        if (rt.isReasoning() && reasoning.compareAndSet(false, true)) {
                            System.out.println("<think>");
                        }
                        if (!rt.isReasoning() && reasoning.compareAndSet(true, false)) {
                            System.out.printf("%n</think>%n");
                        }
                        System.out.print(Objects.requireNonNull(rt).getContentDelta());
                    }
                })
                .collectList()
                .block();

        System.out.println();
        log.info("GLM streaming test completed");
    }
    
    @Test
    public void testNonStreamingResponse() {
        ObjectMapper objectMapper = new ObjectMapper();
        JimiConfig jimiConfig = new ConfigLoader(objectMapper).loadConfig(null);
        
        // 检查GLM配置是否存在
        if (!jimiConfig.getProviders().containsKey("glm")) {
            log.warn("GLM provider not configured, skipping test");
            return;
        }
        
        OpenAICompatibleChatProvider provider = new OpenAICompatibleChatProvider(
                "glm-4.7",
                jimiConfig.getProviders().get("glm"),
                objectMapper,
                "GLM"
        );

        log.info("Testing GLM non-streaming response...");
        
        ChatCompletionResult result = provider.generate(
                "你是一个智能AI助手",
                Collections.singletonList(Message.user("你好,1+1等于多少?")),
                null
        ).block();
        
        System.out.println("Response: " + result.getMessage().getTextContent());
        System.out.println("Usage: " + result.getUsage());
        
        log.info("GLM non-streaming test completed");
    }
    
    @Test
    public void testCodeGeneration() {
        ObjectMapper objectMapper = new ObjectMapper();
        JimiConfig jimiConfig = new ConfigLoader(objectMapper).loadConfig(null);
        
        // 检查GLM配置是否存在
        if (!jimiConfig.getProviders().containsKey("glm")) {
            log.warn("GLM provider not configured, skipping test");
            return;
        }
        
        OpenAICompatibleChatProvider provider = new OpenAICompatibleChatProvider(
                "glm-4.7",
                jimiConfig.getProviders().get("glm"),
                objectMapper,
                "GLM"
        );

        log.info("Testing GLM code generation...");
        
        ChatCompletionResult result = provider.generate(
                "你是一个专业的Java开发工程师",
                Collections.singletonList(Message.user("写一个Java单例模式的实现示例")),
                null
        ).block();
        
        System.out.println("Generated code:");
        System.out.println(result.getMessage().getTextContent());
        
        log.info("GLM code generation test completed");
    }
}
