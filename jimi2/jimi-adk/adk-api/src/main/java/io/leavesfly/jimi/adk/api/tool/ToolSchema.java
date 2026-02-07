package io.leavesfly.jimi.adk.api.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Tool schema for LLM function calling.
 * Describes the tool's parameters in JSON Schema format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSchema {
    
    /**
     * Schema type (always "function")
     */
    @Builder.Default
    private String type = "function";
    
    /**
     * Function definition
     */
    private FunctionSchema function;
    
    /**
     * Function schema definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FunctionSchema {
        /**
         * Function name
         */
        private String name;
        
        /**
         * Function description
         */
        private String description;
        
        /**
         * Parameters schema
         */
        private ParametersSchema parameters;
    }
    
    /**
     * Parameters schema definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParametersSchema {
        /**
         * Schema type (always "object")
         */
        @Builder.Default
        private String type = "object";
        
        /**
         * Property definitions
         */
        private Map<String, PropertySchema> properties;
        
        /**
         * Required property names
         */
        private List<String> required;
    }
    
    /**
     * Property schema definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PropertySchema {
        /**
         * Property type (string, integer, boolean, array, object)
         */
        private String type;
        
        /**
         * Property description
         */
        private String description;
        
        /**
         * Enum values (for string properties)
         */
        private List<String> enumValues;
        
        /**
         * Items schema (for array properties)
         */
        private PropertySchema items;
        
        /**
         * Nested properties (for object properties)
         */
        private Map<String, PropertySchema> properties;
    }
    
    /**
     * Create a tool schema from a tool
     */
    public static ToolSchema fromTool(Tool<?> tool, ParametersSchema parametersSchema) {
        return ToolSchema.builder()
                .function(FunctionSchema.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .parameters(parametersSchema)
                        .build())
                .build();
    }
}
