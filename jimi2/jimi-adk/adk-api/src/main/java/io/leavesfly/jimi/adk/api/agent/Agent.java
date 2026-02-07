package io.leavesfly.jimi.adk.api.agent;

import io.leavesfly.jimi.adk.api.tool.Tool;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 实体 - 表示一个 AI Agent 实例
 * <p>
 * Agent 是 Jimi 系统的核心概念，代表一个具有特定能力的 AI 助手。
 * 每个 Agent 拥有自己的系统提示词、模型配置和可用工具集。
 * </p>
 *
 * @author Jimi2 Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent {
    
    /**
     * Agent 名称（唯一标识）
     */
    private String name;
    
    /**
     * Agent 描述信息
     */
    private String description;
    
    /**
     * Agent 版本号
     */
    @Builder.Default
    private String version = "1.0.0";
    
    /**
     * 系统提示词
     */
    private String systemPrompt;
    
    /**
     * 使用的模型名称
     */
    private String model;
    
    /**
     * 工具名称列表（用于配置）
     */
    private List<String> toolNames;
    
    /**
     * 已加载的工具实例列表
     */
    private List<Tool> tools;
    
    /**
     * 子 Agent 列表
     */
    private List<Agent> subagents;
    
    /**
     * 最大执行步数
     */
    @Builder.Default
    private int maxSteps = 100;
    
    /**
     * 获取文本内容（用于兼容）
     *
     * @return 系统提示词
     */
    public String getTextContent() {
        return systemPrompt;
    }
}
