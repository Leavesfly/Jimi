package io.leavesfly.jimi.tool.core.ask;

import io.leavesfly.jimi.wire.message.WireMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.MonoSink;

import java.util.List;

/**
 * 人工交互请求
 * 当Agent需要用户输入/确认时发送的Wire消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HumanInputRequest implements WireMessage {
    
    /**
     * 请求唯一标识
     */
    private String requestId;
    
    /**
     * 提问内容（支持Markdown格式）
     */
    private String question;
    
    /**
     * 提问类型
     */
    private InputType inputType;
    
    /**
     * 选项列表（仅CHOICE类型使用）
     */
    private List<String> choices;
    
    /**
     * 默认值（可选）
     */
    private String defaultValue;
    
    /**
     * 响应处理器（内部使用，不序列化）
     */
    private transient MonoSink<HumanInputResponse> responseSink;
    
    /**
     * 输入类型枚举
     */
    public enum InputType {
        /**
         * 确认型：满意/需要修改/拒绝
         */
        CONFIRM,
        
        /**
         * 自由输入
         */
        FREE_INPUT,
        
        /**
         * 多选项选择
         */
        CHOICE
    }
    
    @Override
    public String getMessageType() {
        return "HumanInputRequest";
    }
    
    /**
     * 解析人工输入响应
     * 由UI层调用完成异步等待
     */
    public void resolve(HumanInputResponse response) {
        if (responseSink != null) {
            responseSink.success(response);
        }
    }
    
    /**
     * 创建确认请求
     */
    public static HumanInputRequest confirm(String requestId, String question) {
        return HumanInputRequest.builder()
                .requestId(requestId)
                .question(question)
                .inputType(InputType.CONFIRM)
                .build();
    }
    
    /**
     * 创建自由输入请求
     */
    public static HumanInputRequest freeInput(String requestId, String question, String defaultValue) {
        return HumanInputRequest.builder()
                .requestId(requestId)
                .question(question)
                .inputType(InputType.FREE_INPUT)
                .defaultValue(defaultValue)
                .build();
    }
    
    /**
     * 创建选择请求
     */
    public static HumanInputRequest choice(String requestId, String question, List<String> choices) {
        return HumanInputRequest.builder()
                .requestId(requestId)
                .question(question)
                .inputType(InputType.CHOICE)
                .choices(choices)
                .build();
    }
}
