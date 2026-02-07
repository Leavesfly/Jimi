package io.leavesfly.jimi.adk.api.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Message model representing a conversation message.
 * Supports multiple content types (text, images, tool calls).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    
    /**
     * Message role
     */
    private Role role;
    
    /**
     * Message content (for simple text messages)
     */
    private String content;
    
    /**
     * Message content parts (for multi-modal messages)
     */
    private List<ContentPart> contentParts;
    
    /**
     * Tool calls made by the assistant
     */
    private List<ToolCall> toolCalls;
    
    /**
     * Tool call ID (for tool result messages)
     */
    private String toolCallId;
    
    /**
     * Create a user message
     */
    public static Message user(String content) {
        return Message.builder()
                .role(Role.USER)
                .content(content)
                .build();
    }
    
    /**
     * Create an assistant message
     */
    public static Message assistant(String content) {
        return Message.builder()
                .role(Role.ASSISTANT)
                .content(content)
                .build();
    }
    
    /**
     * Create a system message
     */
    public static Message system(String content) {
        return Message.builder()
                .role(Role.SYSTEM)
                .content(content)
                .build();
    }
    
    /**
     * Create a tool result message
     */
    public static Message toolResult(String toolCallId, String content) {
        return Message.builder()
                .role(Role.TOOL)
                .toolCallId(toolCallId)
                .content(content)
                .build();
    }
    
    /**
     * Check if message has tool calls
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
