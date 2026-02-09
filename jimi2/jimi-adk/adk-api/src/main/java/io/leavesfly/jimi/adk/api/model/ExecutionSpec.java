package io.leavesfly.jimi.adk.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令/Hook 执行配置
 * 
 * 定义执行方式:
 * - script: 执行脚本
 * - agent: 委托给 Agent
 * - composite: 组合多个命令/脚本
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionSpec {
    
    /**
     * 执行类型 (必需)
     * 支持: script, agent, composite
     */
    private String type;
    
    /**
     * 脚本内容 (type=script 时必需)
     */
    private String script;
    
    /**
     * 脚本文件路径 (type=script 时可选, 优先于 script 字段)
     */
    private String scriptFile;
    
    /**
     * 工作目录 (可选, 默认为当前工作目录)
     * 支持变量: ${JIMI_WORK_DIR}, ${HOME}, ${PROJECT_ROOT}
     */
    private String workingDir;
    
    /**
     * 超时时间(秒) (可选, 默认 60)
     */
    @Builder.Default
    private int timeout = 60;
    
    /**
     * 环境变量 (可选)
     */
    @Builder.Default
    private Map<String, String> environment = new HashMap<>();
    
    /**
     * Agent 名称 (type=agent 时必需)
     */
    private String agent;
    
    /**
     * 委托任务描述 (type=agent 时必需)
     */
    private String task;
    
    /**
     * 组合执行步骤 (type=composite 时必需)
     */
    @Builder.Default
    private List<CompositeStepSpec> steps = new ArrayList<>();
    
    /**
     * 验证配置有效性
     */
    public void validate() {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Execution type is required");
        }
        
        switch (type) {
            case "script":
                validateScriptExecution();
                break;
            case "agent":
                validateAgentExecution();
                break;
            case "composite":
                validateCompositeExecution();
                break;
            default:
                throw new IllegalArgumentException(
                    "Invalid execution type: " + type + 
                    ". Supported types: script, agent, composite"
                );
        }
        
        if (timeout <= 0) {
            throw new IllegalArgumentException("Timeout must be positive, got: " + timeout);
        }
    }
    
    private void validateScriptExecution() {
        if ((script == null || script.trim().isEmpty()) && 
            (scriptFile == null || scriptFile.trim().isEmpty())) {
            throw new IllegalArgumentException(
                "Either 'script' or 'scriptFile' is required for script execution"
            );
        }
    }
    
    private void validateAgentExecution() {
        if (agent == null || agent.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent name is required for agent execution");
        }
        if (task == null || task.trim().isEmpty()) {
            throw new IllegalArgumentException("Task description is required for agent execution");
        }
    }
    
    private void validateCompositeExecution() {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Steps are required for composite execution");
        }
        steps.forEach(CompositeStepSpec::validate);
    }
}
