package io.leavesfly.jimi.adk.llm;

import io.leavesfly.jimi.adk.api.llm.ChatProvider;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import lombok.Getter;

/**
 * LLM 默认实现
 */
@Getter
public class DefaultLLM implements LLM {
    
    /**
     * 模型名称
     */
    private final String model;
    
    /**
     * 提供商名称
     */
    private final String provider;
    
    /**
     * 聊天提供者
     */
    private final ChatProvider chatProvider;
    
    public DefaultLLM(LLMConfig config, ChatProvider chatProvider) {
        this.model = config.getModel();
        this.provider = config.getProvider();
        this.chatProvider = chatProvider;
    }
}
