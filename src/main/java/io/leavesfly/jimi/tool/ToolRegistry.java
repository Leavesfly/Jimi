package io.leavesfly.jimi.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jimi.engine.toolcall.ArgumentsNormalizer;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 工具注册表
 * 管理所有可用工具的注册、查找和调用
 * <p>
 * 注意：ToolRegistry 不是 Spring Bean，每个 JimiEngine 实例都有自己的 ToolRegistry
 * 因为不同的 Engine 可能有不同的工具配置和运行时参数
 */
@Slf4j
public class ToolRegistry {

    private final Map<String, Tool<?>> tools;
    private final ObjectMapper objectMapper;

    public ToolRegistry(ObjectMapper objectMapper) {
        this.tools = new HashMap<>();
        this.objectMapper = objectMapper;
    }

    /**
     * 注册工具
     */
    public void register(Tool<?> tool) {
        tools.put(tool.getName(), tool);
        log.debug("Registered tool: {}", tool.getName());
    }

    /**
     * 批量注册工具
     */
    public void registerAll(Collection<Tool<?>> toolList) {
        toolList.forEach(this::register);
    }

    /**
     * 获取工具
     */
    public Optional<Tool<?>> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 获取所有工具名称
     */
    public Set<String> getToolNames() {
        return tools.keySet();
    }

    /**
     * 获取所有工具
     */
    public Collection<Tool<?>> getAllTools() {
        return tools.values();
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 执行工具调用
     *
     * @param toolName  工具名称
     * @param arguments 参数（JSON格式字符串）
     * @return 工具执行结果
     */
    public Mono<ToolResult> execute(String toolName, String arguments) {

        return Mono.defer(() -> {
            Optional<Tool<?>> toolOpt = getTool(toolName);
            if (toolOpt.isEmpty()) {
                return Mono.just(ToolResult.error(
                        String.format("Tool not found: %s", toolName),
                        "Tool not found"
                ));
            }

            Tool<?> tool = toolOpt.get();

            String effectiveArguments = "";
            try {

                log.debug("Parsed parameters before for {}: {}", toolName, arguments);

                // 将参数转成标准的json格式
                effectiveArguments = ArgumentsNormalizer.normalizeToValidJson(arguments, objectMapper);

                log.debug("Parsed parameters after for {}: {}", toolName, effectiveArguments);

                Object params = objectMapper.readValue(effectiveArguments, tool.getParamsType());

//                // 记录解析成功的参数对象
//                log.debug("Parsed parameters for {}: {}", toolName, params);

                // 执行工具（使用原始类型）
                return executeToolUnchecked(tool, params);

            } catch (JsonProcessingException e) {
                // JSON解析错误 - 提供更详细的错误信息
                log.error("JSON parsing failed for tool {}: {}. Arguments: '{}'",
                        toolName, e.getMessage(), effectiveArguments);
                return Mono.just(ToolResult.error(
                        String.format("Invalid JSON arguments. Error: %s\nArguments received: %s",
                                e.getMessage(), arguments),
                        "JSON parsing failed"
                ));
            } catch (Exception e) {
                log.error("Failed to execute tool: {}. Arguments: {}", toolName, arguments, e);
                return Mono.just(ToolResult.error(
                        String.format("Failed to execute tool. Error: %s\nArguments: %s",
                                e.getMessage(), arguments),
                        "Execution failed"
                ));
            }
        });
    }


    /**
     * 执行工具（原始类型调用）
     */
    @SuppressWarnings("unchecked")
    private <P> Mono<ToolResult> executeToolUnchecked(Tool<?> tool, Object params) {
        Tool<P> typedTool = (Tool<P>) tool;
        P typedParams = (P) params;
        return typedTool.execute(typedParams);
    }

    /**
     * 生成工具的 JSON Schema 定义列表
     * 用于传递给 LLM
     *
     * @param includeTools 要包含的工具名称列表（null表示全部）
     */
    public List<JsonNode> getToolSchemas(List<String> includeTools) {
        List<JsonNode> schemas = new ArrayList<>();

        Collection<Tool<?>> toolsToInclude;
        if (includeTools == null) {
            toolsToInclude = tools.values();
        } else {
            toolsToInclude = new ArrayList<>();
            for (String toolName : includeTools) {
                Tool<?> tool = tools.get(toolName);
                if (tool != null) {
                    toolsToInclude.add(tool);
                } else {
                    log.warn("Tool not found in registry: {}", toolName);
                }
            }
        }

        for (Tool<?> tool : toolsToInclude) {
            schemas.add(generateToolSchema(tool));
        }

        return schemas;
    }

    /**
     * 生成单个工具的 JSON Schema
     */
    private JsonNode generateToolSchema(Tool<?> tool) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "function");

        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", tool.getName());
        function.put("description", tool.getDescription());

        // 生成参数 schema
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        // 使用反射从参数类生成详细的 schema
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        Class<?> paramsType = tool.getParamsType();
        if (paramsType != null) {
            for (Field field : paramsType.getDeclaredFields()) {
                // 跳过静态字段和合成字段
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                String propName = field.getName();
                JsonProperty jp = field.getAnnotation(JsonProperty.class);
                if (jp != null && !jp.value().isEmpty()) {
                    propName = jp.value();
                }

                ObjectNode propSchema = objectMapper.createObjectNode();
                
                // 生成字段的 schema（支持泛型类型）
                generateFieldSchema(field, propSchema);

                // 提取参数描述信息
                String description = extractFieldDescription(field);
                if (description != null && !description.isEmpty()) {
                    propSchema.put("description", description);
                }

                properties.set(propName, propSchema);

                // 只有非 @Builder.Default 的字段才是必需的
                // 简化处理：如果是基本类型且没有默认值注解，则为必需
                if (!field.isAnnotationPresent(lombok.Builder.Default.class)) {
                    required.add(propName);
                }
            }
        }

        parameters.set("properties", properties);
        if (required.size() > 0) {
            parameters.set("required", required);
        }

        function.set("parameters", parameters);
        schema.set("function", function);

//        // 记录生成的 schema 以便调试
//        log.debug("Generated schema for tool {}: {}", tool.getName(), schema.toPrettyString());

        return schema;
    }

    /**
     * 提取字段的描述信息
     * 优先级：@JsonPropertyDescription > Javadoc 注释
     */
    private String extractFieldDescription(Field field) {
        // 尝试从 @JsonPropertyDescription 注解获取
       JsonPropertyDescription desc =
                field.getAnnotation(JsonPropertyDescription.class);
        if (desc != null && !desc.value().isEmpty()) {
            return desc.value();
        }

        // 未来可以扩展支持其他方式（如自定义注解）
        return null;
    }
    
    /**
     * 生成字段的 JSON Schema
     * 支持基本类型、List 和嵌套对象
     * 
     * @param field 字段
     * @param propSchema Schema 节点
     */
    private void generateFieldSchema(Field field, ObjectNode propSchema) {
        Class<?> fieldType = field.getType();
        
        // 处理基本类型
        if (fieldType == String.class) {
            propSchema.put("type", "string");
        } else if (fieldType == Integer.class || fieldType == int.class) {
            propSchema.put("type", "integer");
        } else if (fieldType == Long.class || fieldType == long.class) {
            propSchema.put("type", "integer");
        } else if (fieldType == Boolean.class || fieldType == boolean.class) {
            propSchema.put("type", "boolean");
        } else if (fieldType == Double.class || fieldType == double.class) {
            propSchema.put("type", "number");
        } else if (fieldType == Float.class || fieldType == float.class) {
            propSchema.put("type", "number");
        } else if (List.class.isAssignableFrom(fieldType)) {
            // 处理 List 类型，解析泛型参数
            propSchema.put("type", "array");
            
            // 获取泛型参数
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType) {
                ParameterizedType paramType = (ParameterizedType) genericType;
                Type[] typeArgs = paramType.getActualTypeArguments();
                
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    Class<?> itemType = (Class<?>) typeArgs[0];
                    ObjectNode itemsSchema = objectMapper.createObjectNode();
                    
                    // 递归生成 items schema
                    generateTypeSchema(itemType, itemsSchema);
                    
                    propSchema.set("items", itemsSchema);
                } else {
                    // 无法解析泛型参数，默认为 string
                    ObjectNode itemsSchema = objectMapper.createObjectNode();
                    itemsSchema.put("type", "string");
                    propSchema.set("items", itemsSchema);
                }
            } else {
                // 没有泛型信息，默认为 string
                ObjectNode itemsSchema = objectMapper.createObjectNode();
                itemsSchema.put("type", "string");
                propSchema.set("items", itemsSchema);
            }
        } else {
            // 其他复杂类型，尝试生成对象 schema
            generateTypeSchema(fieldType, propSchema);
        }
    }
    
    /**
     * 生成类型的 JSON Schema
     * 处理基本类型和嵌套对象
     * 
     * @param type 类型
     * @param schema Schema 节点
     */
    private void generateTypeSchema(Class<?> type, ObjectNode schema) {
        // 处理基本类型
        if (type == String.class) {
            schema.put("type", "string");
        } else if (type == Integer.class || type == int.class) {
            schema.put("type", "integer");
        } else if (type == Long.class || type == long.class) {
            schema.put("type", "integer");
        } else if (type == Boolean.class || type == boolean.class) {
            schema.put("type", "boolean");
        } else if (type == Double.class || type == double.class) {
            schema.put("type", "number");
        } else if (type == Float.class || type == float.class) {
            schema.put("type", "number");
        } else if (type.isEnum()) {
            // 处理枚举类型
            schema.put("type", "string");
            ArrayNode enumValues = objectMapper.createArrayNode();
            for (Object constant : type.getEnumConstants()) {
                enumValues.add(constant.toString());
            }
            schema.set("enum", enumValues);
        } else {
            // 处理嵌套对象
            schema.put("type", "object");
            
            ObjectNode properties = objectMapper.createObjectNode();
            ArrayNode required = objectMapper.createArrayNode();
            
            // 遍历对象的字段
            for (Field field : type.getDeclaredFields()) {
                // 跳过静态字段、合成字段
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                
                // 获取字段名
                String fieldName = field.getName();
                JsonProperty jp = field.getAnnotation(JsonProperty.class);
                if (jp != null && !jp.value().isEmpty()) {
                    fieldName = jp.value();
                }
                
                // 生成字段 schema
                ObjectNode fieldSchema = objectMapper.createObjectNode();
                generateFieldSchema(field, fieldSchema);
                
                // 添加描述
                String description = extractFieldDescription(field);
                if (description != null && !description.isEmpty()) {
                    fieldSchema.put("description", description);
                }
                
                properties.set(fieldName, fieldSchema);
                
                // 判断是否必填
                if (!field.isAnnotationPresent(lombok.Builder.Default.class)) {
                    required.add(fieldName);
                }
            }
            
            schema.set("properties", properties);
            if (required.size() > 0) {
                schema.set("required", required);
            }
        }
    }
}
