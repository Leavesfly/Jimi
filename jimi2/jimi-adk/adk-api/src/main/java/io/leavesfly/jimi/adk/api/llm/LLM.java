package io.leavesfly.jimi.adk.api.llm;

import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.tool.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 包装接口
 * 为不同的 LLM 提供商提供统一接口
 */
public interface LLM {
    
    /**
     * 获取模型名称
     *
     * @return 模型名称
     */
    String getModel();
    
    /**
     * 获取提供商名称
     *
     * @return 提供商名称
     */
    String getProvider();
    
    /**
     * 获取聊天提供者（用于流式响应）
     *
     * @return 聊天提供者
     */
    ChatProvider getChatProvider();
    
    /**
     * 生成流式响应
     *
     * @param systemPrompt 系统提示词
     * @param messages 对话历史
     * @param tools 工具 Schema 列表
     * @return 流式响应块
     */
    default Flux<ChatCompletionChunk> generateStream(
            String systemPrompt,
            List<Message> messages,
            List<ToolSchema> tools) {
        return getChatProvider().generateStream(systemPrompt, messages, tools);
    }
}
