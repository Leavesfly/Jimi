package io.leavesfly.jimi.tool.meta;

import io.leavesfly.jimi.tool.ToolRegistry;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 代码执行上下文
 * 
 * 封装代码执行所需的所有信息
 */
@Data
@Builder
public class CodeExecutionContext {
    
    /**
     * 要执行的 Java 代码
     */
    private String code;
    
    /**
     * 执行超时时间（秒）
     */
    private int timeout;
    
    /**
     * 允许调用的工具列表（null 表示允许所有工具）
     */
    private List<String> allowedTools;
    
    /**
     * 工具注册表引用
     * 用于在代码执行过程中调用工具
     */
    private ToolRegistry toolRegistry;
    
    /**
     * 是否记录执行详情
     */
    private boolean logExecutionDetails;
}
