package io.leavesfly.jimi.adk.hook;

import io.leavesfly.jimi.adk.api.model.ExecutionSpec;
import io.leavesfly.jimi.adk.core.validation.ExecutionSpecValidator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook 规范
 * 定义一个 Hook 的完整配置
 * 配置文件位置: ~/.jimi/hooks/*.yaml 或 project/.jimi/hooks/*.yaml
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookSpec {
    
    /** Hook 名称 (必需) */
    private String name;
    
    /** Hook 描述 (必需) */
    private String description;
    
    /** 是否启用 (默认 true) */
    @Builder.Default
    private boolean enabled = true;
    
    /** 触发配置 (必需) */
    private HookTrigger trigger;
    
    /** 执行配置 (必需) */
    private ExecutionSpec execution;
    
    /** 执行条件列表 (可选) */
    @Builder.Default
    private List<HookCondition> conditions = new ArrayList<>();
    
    /** 优先级 (默认 0, 数值越大优先级越高) */
    @Builder.Default
    private int priority = 0;
    
    /** 配置文件路径 (运行时设置) */
    private String configFilePath;
    
    public void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Hook name is required");
        }
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("Hook description is required for: " + name);
        }
        if (trigger == null) {
            throw new IllegalArgumentException("Trigger config is required for: " + name);
        }
        if (execution == null) {
            throw new IllegalArgumentException("Execution config is required for: " + name);
        }
        trigger.validate();
        ExecutionSpecValidator.validate(execution);
        if (conditions != null) {
            conditions.forEach(HookCondition::validate);
        }
    }
}
