package io.leavesfly.jimi.adk.hook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook 触发配置
 * 定义 Hook 在何时何地被触发
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookTrigger {
    
    /** 触发类型 (必需) */
    private HookType type;
    
    /** 工具名称列表 (仅 PRE_TOOL_CALL/POST_TOOL_CALL, 空=匹配所有) */
    @Builder.Default
    private List<String> tools = new ArrayList<>();
    
    /** 文件模式列表 (glob 模式) */
    @Builder.Default
    private List<String> filePatterns = new ArrayList<>();
    
    /** Agent 名称 (仅 PRE/POST_AGENT_SWITCH, null=匹配所有) */
    private String agentName;
    
    /** 错误类型模式 (仅 ON_ERROR, 正则) */
    private String errorPattern;
    
    public void validate() {
        if (type == null) {
            throw new IllegalArgumentException("Trigger type is required");
        }
    }
}
