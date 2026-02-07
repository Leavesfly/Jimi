package io.leavesfly.jimi.adk.api.llm;

import io.leavesfly.jimi.adk.api.message.FunctionCall;
import io.leavesfly.jimi.adk.api.message.ToolCall;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天完成块 - LLM 流式响应的单个块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatCompletionChunk {
    
    /**
     * 块 ID
     */
    private String id;
    
    /**
     * 对象类型
     */
    private String object;
    
    /**
     * 创建时间戳
     */
    private long created;
    
    /**
     * 模型名称
     */
    private String model;
    
    /**
     * 选择列表
     */
    private List<Choice> choices;
    
    /**
     * Token 使用统计
     */
    private Usage usage;
    
    /**
     * 选择项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        /**
         * 选择索引
         */
        private int index;
        
        /**
         * 增量内容
         */
        private Delta delta;
        
        /**
         * 完成原因
         */
        private String finishReason;
    }
    
    /**
     * 增量内容
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Delta {
        /**
         * 角色
         */
        private String role;
        
        /**
         * 文本内容
         */
        private String content;
        
        /**
         * 推理内容（部分模型支持）
         */
        private String reasoningContent;
        
        /**
         * 工具调用列表
         */
        private List<ToolCall> toolCalls;
    }
    
    /**
     * Token 使用统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        /**
         * 输入 Token 数
         */
        private int promptTokens;
        
        /**
         * 输出 Token 数
         */
        private int completionTokens;
        
        /**
         * 总 Token 数
         */
        private int totalTokens;
    }
}
