package io.leavesfly.jimi.adk.api.llm;

import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.tool.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天提供者接口
 * 定义与 LLM 进行流式对话的能力
 */
public interface ChatProvider {
    
    /**
     * 生成流式响应
     *
     * @param systemPrompt 系统提示词
     * @param history 对话历史
     * @param tools 工具 Schema 列表（用于函数调用）
     * @return 流式响应块的 Flux
     */
    Flux<ChatCompletionChunk> generateStream(
            String systemPrompt,
            List<Message> history,
            List<ToolSchema> tools
    );
    
    /**
     * 获取提供者名称
     *
     * @return 提供者名称
     */
    String getName();
}
