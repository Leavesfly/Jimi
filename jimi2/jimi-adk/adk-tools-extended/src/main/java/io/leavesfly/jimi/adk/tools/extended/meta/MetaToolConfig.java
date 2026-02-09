package io.leavesfly.jimi.adk.tools.extended.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MetaTool 配置类
 * 
 * 配置编程式工具调用（Programmatic Tool Calling）功能
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaToolConfig {
    
    /**
     * 是否启用 MetaTool 功能
     */
    @Builder.Default
    private boolean enabled = true;
    
    /**
     * 最大执行时间（秒）
     * 防止代码无限循环
     */
    @Builder.Default
    private int maxExecutionTime = 30;
    
    /**
     * 最大代码长度（字符）
     * 防止 LLM 生成过长代码
     */
    @Builder.Default
    private int maxCodeLength = 10000;
    
    /**
     * 是否记录执行详情
     * 用于调试和监控
     */
    @Builder.Default
    private boolean logExecutionDetails = true;
}
