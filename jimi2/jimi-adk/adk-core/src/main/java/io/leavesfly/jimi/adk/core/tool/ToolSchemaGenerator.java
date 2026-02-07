package io.leavesfly.jimi.adk.core.tool;

import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolSchema;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 工具 Schema 生成器
 * 根据工具参数类生成 JSON Schema
 */
@Slf4j
public class ToolSchemaGenerator {
    
    private final ObjectMapper objectMapper;
    
    public ToolSchemaGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * 生成工具 Schema
     */
    public ToolSchema generateSchema(Tool<?> tool) {
        Map<String, ToolSchema.PropertySchema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        
        Class<?> paramsType = tool.getParamsType();
        if (paramsType != null) {
            for (Field field : paramsType.getDeclaredFields()) {
                // 跳过静态字段
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                
                String fieldName = getFieldName(field);
                ToolSchema.PropertySchema propSchema = generatePropertySchema(field);
                properties.put(fieldName, propSchema);
                
                // 检查是否必填
                if (!hasDefaultValue(field)) {
                    required.add(fieldName);
                }
            }
        }
        
        return ToolSchema.builder()
                .function(ToolSchema.FunctionSchema.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .parameters(ToolSchema.ParametersSchema.builder()
                                .properties(properties)
                                .required(required)
                                .build())
                        .build())
                .build();
    }
    
    /**
     * 获取字段名
     */
    private String getFieldName(Field field) {
        JsonProperty jp = field.getAnnotation(JsonProperty.class);
        if (jp != null && !jp.value().isEmpty()) {
            return jp.value();
        }
        return field.getName();
    }
    
    /**
     * 生成属性 Schema
     */
    private ToolSchema.PropertySchema generatePropertySchema(Field field) {
        ToolSchema.PropertySchema.PropertySchemaBuilder builder = ToolSchema.PropertySchema.builder();
        
        Class<?> fieldType = field.getType();
        
        // 设置类型
        builder.type(getJsonType(fieldType));
        
        // 设置描述
        JsonPropertyDescription desc = field.getAnnotation(JsonPropertyDescription.class);
        if (desc != null && !desc.value().isEmpty()) {
            builder.description(desc.value());
        }
        
        // 处理枚举
        if (fieldType.isEnum()) {
            List<String> enumValues = new ArrayList<>();
            for (Object constant : fieldType.getEnumConstants()) {
                enumValues.add(constant.toString());
            }
            builder.enumValues(enumValues);
        }
        
        // 处理数组
        if (List.class.isAssignableFrom(fieldType)) {
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType) {
                ParameterizedType paramType = (ParameterizedType) genericType;
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    Class<?> itemType = (Class<?>) typeArgs[0];
                    builder.items(ToolSchema.PropertySchema.builder()
                            .type(getJsonType(itemType))
                            .build());
                }
            }
        }
        
        return builder.build();
    }
    
    /**
     * 获取 JSON Schema 类型
     */
    private String getJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        } else if (type == Integer.class || type == int.class ||
                   type == Long.class || type == long.class) {
            return "integer";
        } else if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        } else if (type == Double.class || type == double.class ||
                   type == Float.class || type == float.class) {
            return "number";
        } else if (List.class.isAssignableFrom(type)) {
            return "array";
        } else if (type.isEnum()) {
            return "string";
        } else {
            return "object";
        }
    }
    
    /**
     * 检查字段是否有默认值
     */
    @SuppressWarnings("unchecked")
    private boolean hasDefaultValue(Field field) {
        // 检查 Lombok 的 @Builder.Default 注解
        try {
            Class<? extends java.lang.annotation.Annotation> builderDefault = 
                    (Class<? extends java.lang.annotation.Annotation>) Class.forName("lombok.Builder$Default");
            return field.isAnnotationPresent(builderDefault);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
