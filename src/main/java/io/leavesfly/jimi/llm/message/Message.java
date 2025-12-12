package io.leavesfly.jimi.llm.message;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息实体
 * 表示对话中的一条消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {
    
    /**
     * 消息角色
     */
    @JsonProperty("role")
    private MessageRole role;

    /**
     * 消息内容部分列表
     * 可以是字符串或 ContentPart 列表
     */
    @JsonProperty("reasoning_content")
    private Object reasoning;

    /**
     * 消息内容部分列表
     * 可以是字符串或 ContentPart 列表
     */
    @JsonProperty("content")
    private Object content;
    
    /**
     * 工具调用列表（仅 assistant 角色）
     */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;
    
    /**
     * 工具调用 ID（仅 tool 角色）
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;
    
    /**
     * 消息名称（可选）
     */
    @JsonProperty("name")
    private String name;
    
    /**
     * 创建用户消息
     */
    public static Message user(String content) {
        return Message.builder()
                     .role(MessageRole.USER)
                     .content(content)
                     .build();
    }
    
    /**
     * 创建用户消息（多部分内容）
     */
    public static Message user(List<ContentPart> content) {
        return Message.builder()
                     .role(MessageRole.USER)
                     .content(content)
                     .build();
    }
    
    /**
     * 创建助手消息
     */
    public static Message assistant(String content) {
        return Message.builder()
                     .role(MessageRole.ASSISTANT)
                     .content(content)
                     .build();
    }

    /**
     * 创建助手消息
     */
    public static Message assistant(String content, String reasoning) {
        return Message.builder()
                .role(MessageRole.ASSISTANT)
                .content(content)
                .reasoning(reasoning)
                .build();
    }

    /**
     * 创建助手消息（带工具调用）
     */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return Message.builder()
                     .role(MessageRole.ASSISTANT)
                     .content(content)
                     .toolCalls(toolCalls)
                     .build();
    }

    /**
     * 创建助手消息（带工具调用）
     */
    public static Message assistant(String content, String reasoning, List<ToolCall> toolCalls) {
        return Message.builder()
                .role(MessageRole.ASSISTANT)
                .content(content)
                .reasoning(reasoning)
                .toolCalls(toolCalls)
                .build();
    }

    /**
     * 创建系统消息
     */
    public static Message system(String content) {
        return Message.builder()
                     .role(MessageRole.SYSTEM)
                     .content(content)
                     .build();
    }
    
    /**
     * 创建工具消息
     */
    public static Message tool(String toolCallId, String content) {
        return Message.builder()
                     .role(MessageRole.TOOL)
                     .toolCallId(toolCallId)
                     .content(content)
                     .build();
    }
    
    /**
     * 获取文本内容
     */
    @JsonIgnore
    public String getTextContent() {
        if (content instanceof String) {
            return (String) content;
        } else if (content instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> parts = (List<Object>) content;
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof TextPart) {
                    sb.append(((TextPart) part).getText());
                } else if (part instanceof String) {
                    sb.append(part);
                }
            }
            return sb.toString();
        }
        return "";
    }
    
    /**
     * 设置内容部分列表
     */
    public void setContentParts(List<ContentPart> parts) {
        this.content = parts;
    }
    
    /**
     * 获取内容部分列表
     */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<ContentPart> getContentParts() {
        if (content instanceof List) {
            List<Object> list = (List<Object>) content;
            List<ContentPart> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof ContentPart) {
                    parts.add((ContentPart) item);
                }
            }
            return parts;
        }
        return new ArrayList<>();
    }
}
