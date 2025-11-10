package io.leavesfly.jimi.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jimi.soul.toolcall.ArgumentsNormalizer;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 工具注册表
 * 管理所有可用工具的注册、查找和调用
 * <p>
 * 注意：ToolRegistry 不是 Spring Bean，每个 JimiSoul 实例都有自己的 ToolRegistry
 * 因为不同的 Soul 可能有不同的工具配置和运行时参数
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

            try {

                log.debug("Parsed parameters before for {}: {}", toolName, arguments);

                String effectiveArguments = ArgumentsNormalizer.normalize(arguments, toolName);

                // 如果参数是 JSON 数组格式，转换为 JSON 对象格式
                effectiveArguments = convertArrayToObject(effectiveArguments, tool, toolName);

                log.debug("Parsed parameters after for {}: {}", toolName, effectiveArguments);

                Object params = objectMapper.readValue(effectiveArguments, tool.getParamsType());

//                // 记录解析成功的参数对象
//                log.debug("Parsed parameters for {}: {}", toolName, params);

                // 执行工具（使用原始类型）
                return executeToolUnchecked(tool, params);

            } catch (JsonProcessingException e) {
                // JSON解析错误 - 提供更详细的错误信息
                log.error("JSON parsing failed for tool {}: {}. Arguments: '{}'",
                        toolName, e.getMessage(), arguments);
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
     * 将 JSON 数组格式的参数转换为 JSON 对象格式
     * 例如: ["/path/file", 1, 100] -> {"path": "/path/file", "lineOffset": 1, "nLines": 100}
     *
     * @param arguments JSON 字符串（可能是数组或对象格式）
     * @param tool      工具实例
     * @param toolName  工具名称
     * @return 转换后的 JSON 对象字符串
     */
    private String convertArrayToObject(String arguments, Tool<?> tool, String toolName) {
        try {
            // 检查是否为 JSON 数组格式
            String trimmed = arguments.trim();
            if (!trimmed.startsWith("[")) {
                // 不是数组格式，直接返回
                return arguments;
            }

            // 解析 JSON 数组
            JsonNode jsonNode = objectMapper.readTree(trimmed);
            if (!jsonNode.isArray()) {
                return arguments;
            }

            ArrayNode arrayNode = (ArrayNode) jsonNode;
            Class<?> paramsType = tool.getParamsType();

            if (paramsType == null) {
                log.warn("Tool {} has null paramsType, cannot convert array to object", toolName);
                return arguments;
            }

            // 获取参数类的所有字段（按声明顺序）
            List<Field> fields = getOrderedFields(paramsType);

            // 检查数组元素数量是否匹配
            if (arrayNode.size() > fields.size()) {
                log.warn("Array has {} elements but tool {} only has {} parameters",
                        arrayNode.size(), toolName, fields.size());
            }

            // 构建 JSON 对象
            ObjectNode objectNode = objectMapper.createObjectNode();
            for (int i = 0; i < Math.min(arrayNode.size(), fields.size()); i++) {
                Field field = fields.get(i);
                JsonNode value = arrayNode.get(i);

                // 获取字段名（考虑 @JsonProperty 注解）
                String fieldName = field.getName();
                JsonProperty jp = field.getAnnotation(JsonProperty.class);
                if (jp != null && !jp.value().isEmpty()) {
                    fieldName = jp.value();
                }

                // 设置值
                objectNode.set(fieldName, value);
            }

            String result = objectMapper.writeValueAsString(objectNode);
            log.info("Converted array arguments to object for tool {}. Original: {}, Converted: {}",
                    toolName, arguments, result);
            return result;

        } catch (Exception e) {
            log.debug("Failed to convert array to object for tool {}, using original arguments: {}",
                    toolName, e.getMessage());
            return arguments;
        }
    }

    /**
     * 获取参数类的有序字段列表
     * 排除静态字段和合成字段
     *
     * @param paramsType 参数类型
     * @return 有序字段列表
     */
    private List<Field> getOrderedFields(Class<?> paramsType) {
        List<Field> fields = new ArrayList<>();
        for (Field field : paramsType.getDeclaredFields()) {
            // 跳过静态字段、合成字段和 $jacocoData 等字段
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                    field.isSynthetic() ||
                    field.getName().startsWith("$")) {
                continue;
            }
            fields.add(field);
        }
        return fields;
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
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                String propName = field.getName();
                JsonProperty jp = field.getAnnotation(JsonProperty.class);
                if (jp != null && !jp.value().isEmpty()) {
                    propName = jp.value();
                }

                ObjectNode propSchema = objectMapper.createObjectNode();
                Class<?> t = field.getType();
                if (t == String.class) {
                    propSchema.put("type", "string");
                } else if (t == Integer.class || t == int.class) {
                    propSchema.put("type", "integer");
                } else if (t == Long.class || t == long.class) {
                    propSchema.put("type", "integer");
                } else if (t == Boolean.class || t == boolean.class) {
                    propSchema.put("type", "boolean");
                } else if (java.util.List.class.isAssignableFrom(t)) {
                    propSchema.put("type", "array");
                    ObjectNode items = objectMapper.createObjectNode();
                    items.put("type", "string");
                    propSchema.set("items", items);
                } else {
                    // 默认按字符串处理
                    propSchema.put("type", "string");
                }

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
        com.fasterxml.jackson.annotation.JsonPropertyDescription desc =
                field.getAnnotation(com.fasterxml.jackson.annotation.JsonPropertyDescription.class);
        if (desc != null && !desc.value().isEmpty()) {
            return desc.value();
        }

        // 未来可以扩展支持其他方式（如自定义注解）
        return null;
    }
}
