package io.leavesfly.jimi.core.hook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook 触发配置
 * 
 * 定义 Hook 在何时何地被触发
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookTrigger {
    
    /**
     * 触发类型 (必需)
     */
    private HookType type;
    
    /**
     * 工具名称列表 (可选)
     * 仅对 PRE_TOOL_CALL 和 POST_TOOL_CALL 类型有效
     * 为空表示匹配所有工具
     */
    @Builder.Default
    private List<String> tools = new ArrayList<>();
    
    /**
     * 文件模式列表 (可选)
     * 使用 glob 模式匹配文件, 如: *.java, src/&#42;&#42;/&#42;.xml
     * 仅对工具操作文件时有效
     */
    @Builder.Default
    private List<String> filePatterns = new ArrayList<>();
    
    /**
     * Agent 名称 (可选)
     * 仅对 PRE_AGENT_SWITCH 和 POST_AGENT_SWITCH 类型有效
     * 为空表示匹配所有 Agent
     */
    private String agentName;
    
    /**
     * 错误类型模式 (可选)
     * 仅对 ON_ERROR 类型有效
     * 支持正则表达式匹配错误消息
     */
    private String errorPattern;
    
    /**
     * 验证配置有效性
     */
    public void validate() {
        if (type == null) {
            throw new IllegalArgumentException("Trigger type is required");
        }
        
        // 验证类型特定配置
        switch (type) {
            case PRE_TOOL_CALL:
            case POST_TOOL_CALL:
                // tools 和 filePatterns 是可选的
                break;
                
            case PRE_AGENT_SWITCH:
            case POST_AGENT_SWITCH:
                // agentName 是可选的
                break;
                
            case ON_ERROR:
                // errorPattern 是可选的
                break;
                
            case PRE_USER_INPUT:
            case POST_USER_INPUT:
            case ON_SESSION_START:
            case ON_SESSION_END:
                // 这些类型不需要额外配置
                break;
                
            default:
                throw new IllegalArgumentException("Unknown trigger type: " + type);
        }
    }
}
