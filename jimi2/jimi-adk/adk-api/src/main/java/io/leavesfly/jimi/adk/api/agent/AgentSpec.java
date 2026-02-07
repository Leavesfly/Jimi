package io.leavesfly.jimi.adk.api.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Agent 规范 - 定义 Agent 的配置信息
 * <p>
 * 从 YAML 配置文件加载，用于创建 Agent 实例
 * </p>
 *
 * @author Jimi2 Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSpec {
    
    /**
     * Agent 名称
     */
    private String name;
    
    /**
     * Agent 描述
     */
    private String description;
    
    /**
     * Agent 版本号
     */
    @Builder.Default
    private String version = "1.0.0";
    
    /**
     * 使用的模型名称
     */
    private String model;
    
    /**
     * 系统提示词（直接配置）
     */
    private String systemPrompt;
    
    /**
     * 系统提示词模板文件路径
     */
    private Path systemPromptPath;
    
    /**
     * 系统提示词渲染参数
     */
    private Map<String, String> systemPromptArgs;
    
    /**
     * 包含的工具名称列表
     */
    private List<String> tools;
    
    /**
     * 排除的工具名称列表
     */
    private List<String> excludeTools;
    
    /**
     * 子 Agent 规范列表
     */
    private List<SubagentSpec> subagents;
    
    /**
     * 最大执行步数
     */
    @Builder.Default
    private int maxSteps = 100;
    
    /**
     * 最大无工具调用的思考步数
     */
    @Builder.Default
    private int maxThinkingSteps = 5;
}
