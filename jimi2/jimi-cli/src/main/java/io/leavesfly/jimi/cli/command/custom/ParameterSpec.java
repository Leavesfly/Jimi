package io.leavesfly.jimi.cli.command.custom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命令参数规范
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterSpec {
    
    /** 参数名称 (必需) */
    private String name;
    
    /** 参数类型: string, boolean, integer, path */
    @Builder.Default
    private String type = "string";
    
    /** 参数描述 */
    private String description;
    
    /** 默认值 */
    private String defaultValue;
    
    /** 是否必需 */
    @Builder.Default
    private boolean required = false;
    
    public void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Parameter name is required");
        }
        if (!isValidType(type)) {
            throw new IllegalArgumentException(
                "Invalid parameter type '" + type + "' for parameter: " + name + 
                ". Supported types: string, boolean, integer, path"
            );
        }
    }
    
    private boolean isValidType(String type) {
        return "string".equals(type) || "boolean".equals(type) || 
               "integer".equals(type) || "path".equals(type);
    }
}
