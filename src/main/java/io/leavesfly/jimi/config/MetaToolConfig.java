package io.leavesfly.jimi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MetaTool 配置类
 * 
 * 配置编程式工具调用（Programmatic Tool Calling）功能
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "jimi.meta-tool")
public class MetaToolConfig {
    
    /**
     * 是否启用 MetaTool 功能
     */
    private boolean enabled = true;
    
    /**
     * 最大执行时间（秒）
     * 防止代码无限循环
     */
    private int maxExecutionTime = 30;
    
    /**
     * 最大代码长度（字符）
     * 防止 LLM 生成过长代码
     */
    private int maxCodeLength = 10000;
    
    /**
     * 是否记录执行详情
     * 用于调试和监控
     */
    private boolean logExecutionDetails = true;
}
