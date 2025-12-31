package io.leavesfly.jimi.llm;

import io.leavesfly.jimi.llm.message.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

/**
 * LLM包装类
 * 包含ChatProvider和最大上下文大小
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLM {
    
    /**
     * Chat Provider实例
     */
    private ChatProvider chatProvider;
    
    /**
     * 最大上下文大小（Token数）
     */
    private int maxContextSize;
    
    /**
     * 获取模型名称
     */
    public String getModelName() {
        return chatProvider != null ? chatProvider.getModelName() : "unknown";
    }
    
    /**
     * 简单文本补全
     * @param prompt 提示词
     * @return 生成的文本内容
     */
    public Mono<String> complete(String prompt) {
        if (chatProvider == null) {
            return Mono.error(new IllegalStateException("ChatProvider not configured"));
        }
        
        List<Message> messages = Collections.singletonList(Message.user(prompt));
        return chatProvider.generate(null, messages, null)
                .map(result -> {
                    if (result.getMessage() != null && result.getMessage().getContent() != null) {
                        Object content = result.getMessage().getContent();
                        return content instanceof String ? (String) content : content.toString();
                    }
                    return "";
                });
    }
}
