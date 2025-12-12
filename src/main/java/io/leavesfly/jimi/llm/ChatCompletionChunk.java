package io.leavesfly.jimi.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chat完成流式块
 * 用于流式响应的增量数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatCompletionChunk {
    
    /**
     * 块类型
     */
    private ChunkType type;
    
    /**
     * 内容增量（对于content类型）
     */
    private String contentDelta;
    
    /**
     * 是否为推理内容（思考过程）
     */
    private boolean isReasoning;
    
    /**
     * 工具调用ID（对于tool_call类型）
     */
    private String toolCallId;
    
    /**
     * 函数名（对于tool_call类型）
     */
    private String functionName;
    
    /**
     * 函数参数增量（对于tool_call类型）
     */
    private String argumentsDelta;
    
    /**
     * Token使用统计（仅在done块中）
     */
    private ChatCompletionResult.Usage usage;
    
    /**
     * 块类型枚举
     */
    public enum ChunkType {
        /**
         * 内容增量
         */
        CONTENT,

        /**
         * 推理内容
         */
        REASONING,
        
        /**
         * 工具调用
         */
        TOOL_CALL,
        
        /**
         * 完成
         */
        DONE
    }
}
