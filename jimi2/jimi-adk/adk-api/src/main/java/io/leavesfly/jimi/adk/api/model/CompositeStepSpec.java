package io.leavesfly.jimi.adk.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 组合执行步骤规范
 * 
 * 定义组合命令中的单个执行步骤。
 * <p>这是一个纯数据模型，验证逻辑请使用 {@link ExecutionSpecValidator}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeStepSpec {
    
    /**
     * 步骤类型 (必需)
     * 支持: command, script
     */
    private String type;
    
    /**
     * 命令名称 (type=command 时必需)
     */
    private String command;
    
    /**
     * 脚本内容 (type=script 时必需)
     */
    private String script;
    
    /**
     * 步骤描述 (可选)
     */
    private String description;
    
    /**
     * 失败时是否继续 (可选, 默认 false)
     */
    @Builder.Default
    private boolean continueOnFailure = false;
}
