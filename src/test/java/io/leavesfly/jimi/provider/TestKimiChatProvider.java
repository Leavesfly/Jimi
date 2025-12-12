package io.leavesfly.jimi.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.ConfigLoader;
import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.provider.KimiChatProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TestKimiChatProvider {
    @Test
    public void test() {
        ObjectMapper objectMapper = new ObjectMapper();
        JimiConfig jimiConfig = new ConfigLoader(objectMapper).loadConfig(null);
        KimiChatProvider provider = new KimiChatProvider(
                "kimi-k2-thinking-turbo",
                jimiConfig.getProviders().get("kimi"),
                objectMapper
        );

        AtomicBoolean reasoning = new AtomicBoolean(false);
        provider.generateStream(
                        "你是 Kimi，由 Moonshot AI 提供的人工智能助手",
                        Collections.singletonList(Message.user("你好，1+2等于多少？")),
                        null
                )
                .doOnEach(chunk -> {
                    ChatCompletionChunk rt = chunk.get();
                    if (rt != null && rt.getType() == ChatCompletionChunk.ChunkType.REASONING) {
                        if (reasoning.compareAndSet(false, true)) {
                            System.out.println("<think>");
                        }
                        System.out.print(Objects.requireNonNull(rt).getContentDelta());
                    }
                    if (rt != null && rt.getType() == ChatCompletionChunk.ChunkType.CONTENT) {
                        if (reasoning.compareAndSet(true, false)) {
                            System.out.printf("%n</think>%n");
                        }
                        System.out.print(Objects.requireNonNull(rt).getContentDelta());
                    }
                })
                .collectList()
                .block();

        System.out.println();
        ChatCompletionResult result = provider.generate(
                "你是 Kimi，由 Moonshot AI 提供的人工智能助手",
                Collections.singletonList(Message.user("你好，1+2等于多少？")),
                null
        ).block();
        System.out.println(result);
    }
}
