package io.leavesfly.jimi.work.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式输出块 - UI 展示单元
 * 将 Wire 消息转换为统一的 UI 展示模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamChunk {

    /**
     * 块类型
     */
    public enum Type {
        TEXT,            // 文本内容
        REASONING,       // 推理内容
        TOOL_CALL,       // 工具调用
        TOOL_RESULT,     // 工具结果
        APPROVAL,        // 审批请求
        STEP_BEGIN,      // 步骤开始
        STEP_END,        // 步骤结束
        TODO_UPDATE,     // Todo 更新
        ERROR,           // 错误
        DONE             // 完成标记
    }

    private Type type;
    private String content;
    private String toolName;
    private String toolCallId;
    private int stepNumber;
    private ApprovalInfo approval;
    private TodoInfo.TodoList todoList;

    // ==================== 工厂方法 ====================

    public static StreamChunk text(String text) {
        return StreamChunk.builder().type(Type.TEXT).content(text).build();
    }

    public static StreamChunk reasoning(String text) {
        return StreamChunk.builder().type(Type.REASONING).content(text).build();
    }

    public static StreamChunk toolCall(String toolName, String toolCallId) {
        return StreamChunk.builder().type(Type.TOOL_CALL).toolName(toolName).toolCallId(toolCallId).build();
    }

    public static StreamChunk toolResult(String toolName, String content) {
        return StreamChunk.builder().type(Type.TOOL_RESULT).toolName(toolName).content(content).build();
    }

    public static StreamChunk stepBegin(int stepNumber) {
        return StreamChunk.builder().type(Type.STEP_BEGIN).stepNumber(stepNumber).build();
    }

    public static StreamChunk stepEnd(int stepNumber) {
        return StreamChunk.builder().type(Type.STEP_END).stepNumber(stepNumber).build();
    }

    public static StreamChunk approval(ApprovalInfo info) {
        return StreamChunk.builder().type(Type.APPROVAL).approval(info).build();
    }

    public static StreamChunk todoUpdate(TodoInfo.TodoList todoList) {
        return StreamChunk.builder().type(Type.TODO_UPDATE).todoList(todoList).build();
    }

    public static StreamChunk error(String message) {
        return StreamChunk.builder().type(Type.ERROR).content(message).build();
    }

    public static StreamChunk done() {
        return StreamChunk.builder().type(Type.DONE).build();
    }
}
