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
 * 
 * <p>这是一个纯数据模型，验证逻辑请使用 {@link ExecutionSpecValidator}。
 * @see ExecutionSpecValidator
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
}
