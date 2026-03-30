package io.leavesfly.jimi.core.hook;

import io.leavesfly.jimi.command.custom.ExecutionSpec;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook 规范
 * 
 * 定义一个 Hook 的完整配置
 * 配置文件位置: ~/.jimi/hooks/*.yaml 或 <project>/.jimi/hooks/*.yaml
 * 
 * 示例配置:
 * ```yaml
 * name: "auto-format"
 * description: "自动格式化 Java 代码"
 * enabled: true
 * trigger:
 *   type: "post_tool_call"
 *   tools:
 *     - "WriteFile"
 *   file_patterns:
 *     - "*.java"
 * execution:
 *   type: "script"
 *   script: "google-java-format -i ${MODIFIED_FILE}"
 *   async: true
 * ```
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookSpec {
    
    /**
     * Hook 名称 (必需)
     */
    private String name;
    
    /**
     * Hook 描述 (必需)
     */
    private String description;
    
    /**
     * 是否启用 (可选, 默认 true)
     */
    @Builder.Default
    private boolean enabled = true;
    
    /**
     * 触发配置 (必需)
     */
    private HookTrigger trigger;
    
    /**
     * 执行配置 (必需)
     */
    private ExecutionSpec execution;
    
    /**
     * 执行条件列表 (可选)
     */
    @Builder.Default
    private List<HookCondition> conditions = new ArrayList<>();
    
    /**
     * 优先级 (可选, 默认 0)
     * 数值越大优先级越高,同类型 Hook 按优先级排序执行
     */
    @Builder.Default
    private int priority = 0;
    
    /**
     * 配置文件路径 (运行时设置)
     */
    private String configFilePath;
    
    /**
     * 验证配置有效性
     */
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
        execution.validate();
        
        if (conditions != null) {
            conditions.forEach(HookCondition::validate);
        }
    }
}
