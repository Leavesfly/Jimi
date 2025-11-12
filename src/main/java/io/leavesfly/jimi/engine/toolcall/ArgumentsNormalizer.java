package io.leavesfly.jimi.engine.toolcall;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jimi.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具调用参数标准化器
 * <p>
 * 职责：
 * - 标准化工具调用参数格式
 * - 处理双重转义的JSON
 * - 转换逗号分隔的参数为JSON格式
 * - 修复常见的参数格式问题
 * - 提供通用容错函数处理各种异常格式
 */
@Slf4j
public class ArgumentsNormalizer {


    /**
     * 通用容错函数：将 LLM 工具调用中的 arguments 参数转换为标准 JSON 格式
     * <p>
     * 标准化流程：
     * 0. 校验 arguments 是否已经是合法的 JSON，如果是则直接返回
     * 1. 移除前后多余的 null
     * 2. 处理双重转义
     * 3. 修复字符串值中未转义的引号
     * 4. 修复缺失的引号
     * 5. 修复不匹配的括号
     * 6. 修复非法转义字符
     * 7. 处理逗号分隔的参数
     *
     * @param arguments    原始参数字符串
     * @param objectMapper Jackson ObjectMapper 实例
     * @return 标准化后的 JSON 字符串
     * //     * @throws ArgumentsNormalizationException 当无法修复参数格式时抛出
     */
    public static String normalizeToValidJson(String arguments, ObjectMapper objectMapper) {

        if (arguments == null || arguments.trim().isEmpty()) {
            return "{}";
        }

        String normalized = arguments.trim();
        
        // 步骤 0: 先校验是否已经是合法的 JSON，是的话直接返回
        try {
            objectMapper.readTree(normalized);
            // 如果能够成功解析，说明已经是合法的 JSON，直接返回
//            log.debug("Arguments is already valid JSON, skip normalization: {}", normalized);
            return normalized;
        } catch (Exception e) {
            // 不是合法的 JSON，继续执行标准化流程
            log.debug("Arguments is not valid JSON, proceeding with normalization: {}", e.getMessage());
        }
        
        String original = normalized; // 保存原始值用于错误信息

        // 步骤 1: 移除前后多余的 null
        normalized = removeNullPrefix(normalized);
        normalized = removeNullSuffix(normalized);

        // 步骤 2: 处理双重转义
        normalized = unescapeDoubleEscapedJsonSafe(normalized);

        // 步骤 3: 修复字符串值中未转义的引号(必须在修复缺失引号之前)
        normalized = escapeUnescapedQuotesInValues(normalized);

        // 步骤 4: 修复缺失的引号
        normalized = fixMissingQuotes(normalized);

        // 步骤 5: 修复不匹配的括号
        normalized = fixUnbalancedBrackets(normalized);

        // 步骤 6: 修复非法转义字符
        normalized = fixIllegalEscapes(normalized);

        // 步骤 7: 处理逗号分隔的参数
        normalized = convertCommaDelimitedToJsonSafe(normalized);

        return normalized;

    }

    /**
     * 标准化json参数（兼容旧版本）
     *
     * @param arguments 原始参数字符串
     * @param toolName  工具名称
     * @return 标准化后的参数字符串
     */
    public static String normalize(String arguments, String toolName, Tool<?> tool, ObjectMapper objectMapper) {
        if (arguments == null || arguments.trim().isEmpty()) {
            log.warn("Empty or null arguments for tool: {}, using empty object", toolName);
            return "{}";
        }

        //去空格
        String normalize = arguments.trim();

        //去null
        normalize = removeNull(normalize, arguments, toolName);

        //去json转义
        normalize = unescapeDoubleEscapedJson(normalize, arguments, toolName);

        // 转换逗号分隔的参数为JSON数组格式
        normalize = convertCommaDelimitedToJson(normalize, arguments, toolName);

        //JSON数组格式转成json对象格式
        return convertArrayToObject(normalize, tool, toolName, objectMapper);

    }

    /**
     * 移除开头的 null
     */
    private static String removeNullPrefix(String input) {
        String trimmed = input.trim();
        if (trimmed.startsWith("null")) {
            String afterNull = trimmed.substring(4).trim();
            // 验证后面是否为有效的JSON结构
            if (afterNull.startsWith("{") || afterNull.startsWith("[")) {
                log.warn("Removed 'null' prefix from arguments: {}", input);
                return afterNull;
            }
        }
        return trimmed;
    }

    /**
     * 移除末尾的 null
     */
    private static String removeNullSuffix(String input) {
        String trimmed = input.trim();
        if (trimmed.endsWith("null")) {
            String beforeNull = trimmed.substring(0, trimmed.length() - 4).trim();
            // 验证前面是否为完整的JSON结构
            if ((beforeNull.startsWith("{") && beforeNull.endsWith("}")) ||
                    (beforeNull.startsWith("[") && beforeNull.endsWith("]"))) {
                log.warn("Removed 'null' suffix from arguments: {}", input);
                return beforeNull;
            }
        }
        return trimmed;
    }

    /**
     * 安全的双重转义处理
     */
    private static String unescapeDoubleEscapedJsonSafe(String input) {
        // 处理整体被双引号包裹的双重转义 JSON
        if (input.startsWith("\"") && input.endsWith("\"") && input.length() > 2) {
            try {
                String unescaped = input.substring(1, input.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");

                // 验证是否为有效的 JSON 结构
                if ((unescaped.startsWith("{") && unescaped.endsWith("}")) ||
                        (unescaped.startsWith("[") && unescaped.endsWith("]"))) {
                    log.warn("Unescaped double-escaped JSON: {}", input);
                    return unescaped;
                }
            } catch (Exception e) {
                log.debug("Failed to unescape double-escaped JSON, using as-is", e);
            }
        }

        // 处理包含转义引号但不是整体包裹的情况
        if (input.contains("\\\"") && !input.startsWith("\"")) {
            String unescaped = input.replace("\\\"", "\"");
            if ((unescaped.startsWith("{") && unescaped.endsWith("}")) ||
                    (unescaped.startsWith("[") && unescaped.endsWith("]"))) {
                log.warn("Unescaped quotes in JSON: {}", input);
                return unescaped;
            }
        }

        return input;
    }

    /**
     * 修复字符串值中未转义的引号和特殊字符
     * 处理场景: {"content": "text with "unescaped" quotes and\nnewlines"}
     * 这种情况通常出现在 content 字段包含 XML、HTML、Markdown 等文本时
     */
    private static String escapeUnescapedQuotesInValues(String input) {
        // 只处理对象和数组格式
        if ((!input.startsWith("{") || !input.endsWith("}")) && 
            (!input.startsWith("[") || !input.endsWith("]"))) {
            return input;
        }

        try {
            StringBuilder result = new StringBuilder();
            boolean inString = false;
            boolean afterColon = false;  // 标记是否在冒号之后(值位置)
            int braceDepth = 0;  // 花括号深度
            int bracketDepth = 0;  // 方括号深度
            
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                char prev = i > 0 ? input.charAt(i - 1) : '\0';
                
                // 检测是否是转义字符
                boolean isEscaped = (prev == '\\');
                
                // 处理引号
                if (c == '"' && !isEscaped) {
                    if (!inString) {
                        // 开始字符串
                        inString = true;
                        result.append(c);
                    } else {
                        // 可能是字符串结束,也可能是值内部未转义的引号
                        // 检查后续字符来判断
                        int nextNonSpace = findNextNonSpaceChar(input, i + 1);
                        
                        if (afterColon && nextNonSpace != -1) {
                            char nextChar = input.charAt(nextNonSpace);
                            // 如果后面不是逗号、右括号、右花括号,说明是值内部的引号需要转义
                            if (nextChar != ',' && nextChar != '}' && nextChar != ']') {
                                result.append("\\\"");  // 转义引号
                                continue;
                            }
                        }
                        
                        // 正常的字符串结束
                        inString = false;
                        afterColon = false;
                        result.append(c);
                    }
                } else if (!inString) {
                    // JSON 结构字符
                    if (c == ':') {
                        afterColon = true;
                    } else if (c == ',' || c == '{' || c == '[') {
                        afterColon = false;
                    }
                    
                    if (c == '{') braceDepth++;
                    else if (c == '}') braceDepth--;
                    else if (c == '[') bracketDepth++;
                    else if (c == ']') bracketDepth--;
                    
                    result.append(c);
                } else {
                    // 字符串内部的字符 - 需要转义特殊字符
                    if (!isEscaped && afterColon) {
                        switch (c) {
                            case '\n':
                                result.append("\\n");
                                break;
                            case '\r':
                                result.append("\\r");
                                break;
                            case '\t':
                                result.append("\\t");
                                break;
                            case '\\':
                                result.append("\\\\");
                                break;
                            default:
                                result.append(c);
                        }
                    } else {
                        result.append(c);
                    }
                }
            }
            
            String resultStr = result.toString();
            if (!resultStr.equals(input)) {
                log.warn("Escaped unescaped quotes and special chars in JSON values. Original length: {}, Fixed length: {}", 
                    input.length(), resultStr.length());
                return resultStr;
            }
        } catch (Exception e) {
            log.debug("Failed to escape unescaped quotes, using as-is", e);
        }
        
        return input;
    }
    
    /**
     * 查找下一个非空格字符的位置
     */
    private static int findNextNonSpaceChar(String str, int startIndex) {
        for (int i = startIndex; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 修复缺失的引号
     * 例如: {key: value} -> {"key": "value"}
     */
    private static String fixMissingQuotes(String input) {
        // 只处理对象格式
        if (!input.startsWith("{") || !input.endsWith("}")) {
            return input;
        }

        try {
            // 尝试使用正则表达式修复常见的缺失引号问题
            String fixed = input
                    // 修复键没有引号的情况: {key: -> {"key":
                    .replaceAll("\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", "{\"$1\":")
                    .replaceAll(",\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", ",\"$1\":");

            if (!fixed.equals(input)) {
                log.warn("Fixed missing quotes in JSON keys: {} -> {}", input, fixed);
                return fixed;
            }
        } catch (Exception e) {
            log.debug("Failed to fix missing quotes, using as-is", e);
        }

        return input;
    }

    /**
     * 修复不匹配的括号
     */
    private static String fixUnbalancedBrackets(String input) {
        int openCurly = 0, closeCurly = 0;
        int openSquare = 0, closeSquare = 0;
        boolean inString = false;
        char stringChar = '\0';

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // 处理字符串内部
            if ((c == '"' || c == '\'') && (i == 0 || input.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    stringChar = c;
                } else if (c == stringChar) {
                    inString = false;
                }
                continue;
            }

            if (inString) continue;

            // 统计括号
            switch (c) {
                case '{':
                    openCurly++;
                    break;
                case '}':
                    closeCurly++;
                    break;
                case '[':
                    openSquare++;
                    break;
                case ']':
                    closeSquare++;
                    break;
            }
        }

        StringBuilder result = new StringBuilder(input);

        // 补全缺失的右括号
        for (int i = 0; i < openCurly - closeCurly; i++) {
            result.append('}');
        }
        for (int i = 0; i < openSquare - closeSquare; i++) {
            result.append(']');
        }

        // 移除多余的左括号（从开头）
        String resultStr = result.toString();
        for (int i = 0; i < closeCurly - openCurly; i++) {
            int index = resultStr.indexOf('{');
            if (index >= 0) {
                resultStr = resultStr.substring(0, index) + resultStr.substring(index + 1);
            }
        }
        for (int i = 0; i < closeSquare - openSquare; i++) {
            int index = resultStr.indexOf('[');
            if (index >= 0) {
                resultStr = resultStr.substring(0, index) + resultStr.substring(index + 1);
            }
        }

        if (!resultStr.equals(input)) {
            log.warn("Fixed unbalanced brackets: {} -> {}", input, resultStr);
            return resultStr;
        }

        return input;
    }

    /**
     * 修复非法转义字符
     */
    private static String fixIllegalEscapes(String input) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        char stringChar = '\0';

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // 检测字符串边界
            if ((c == '"' || c == '\'') && (i == 0 || input.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    stringChar = c;
                } else if (c == stringChar) {
                    inString = false;
                }
                result.append(c);
                continue;
            }

            // 处理转义字符
            if (c == '\\' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);

                // 合法的转义字符
                if (inString && (next == '"' || next == '\'' || next == '\\' ||
                        next == '/' || next == 'b' || next == 'f' ||
                        next == 'n' || next == 'r' || next == 't' || next == 'u')) {
                    result.append(c);
                } else if (!inString) {
                    // 字符串外部的反斜杠，保留
                    result.append(c);
                } else {
                    // 非法转义，移除反斜杠
                    log.debug("Removed illegal escape: \\{}", next);
                    // 不添加反斜杠，下次循环会添加 next
                }
            } else {
                result.append(c);
            }
        }

        String resultStr = result.toString();
        if (!resultStr.equals(input)) {
            log.warn("Fixed illegal escapes: {} -> {}", input, resultStr);
        }

        return resultStr;
    }

    /**
     * 安全的逗号分隔参数转换
     */
    private static String convertCommaDelimitedToJsonSafe(String input) {
        // 已经是有效的 JSON 格式
        if (input.startsWith("{") || input.startsWith("[")) {
            return input;
        }

        // 不包含逗号，单个参数
        if (!input.contains(",")) {
            return "[" + input + "]";
        }

        // 包含逗号，尝试解析
        try {
            List<String> parts = parseCommaDelimitedArguments(input);
            if (!parts.isEmpty()) {
                String jsonArray = "[" + String.join(", ", parts) + "]";
                log.info("Converted comma-delimited arguments to JSON array: {} -> {}", input, jsonArray);
                return jsonArray;
            }
        } catch (Exception e) {
            log.debug("Failed to parse as comma-delimited, using as-is: {}", e.getMessage());
        }

        return input;
    }

    /**
     * 移除字符串中所有的null（兼容旧版本）
     */
    private static String removeNull(String trimmed, String original, String toolName) {
        if (trimmed.contains("null")) {
            String fixed = trimmed.replace("null", "").trim();
            if (!fixed.equals(trimmed)) {
                log.warn("Detected arguments with 'null' for tool {}. Original: {}, Fixed: {}",
                        toolName, original, fixed);
                return fixed;
            }
        }
        return trimmed;
    }

    /**
     * 将逗号分隔的参数转换为JSON数组格式
     * 例如: "/Users/yefei.yf/Jimi/java/SKILLS_README.md", 1, 100 -> ["/Users/yefei.yf/Jimi/java/SKILLS_README.md", 1, 100]
     */
    private static String convertCommaDelimitedToJson(String trimmed, String original, String toolName) {
        // 如果已经是有效的JSON格式（以{或[开头），直接返回
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }

        // 检查是否包含逗号（可能是逗号分隔的参数）
        if (!trimmed.contains(",")) {
            // 单个参数，尝试判断类型并包装为数组
            return "[" + trimmed + "]";
        }

        // 包含逗号，可能是逗号分隔的多个参数
        try {
            List<String> parts = parseCommaDelimitedArguments(trimmed);
            if (!parts.isEmpty()) {
                String jsonArray = "[" + String.join(", ", parts) + "]";
                log.info("Converted comma-delimited arguments to JSON array for tool {}. Original: {}, Converted: {}",
                        toolName, original, jsonArray);
                return jsonArray;
            }
        } catch (Exception e) {
            log.debug("Failed to parse as comma-delimited arguments, using as-is: {}", e.getMessage());
        }

        return trimmed;
    }

    /**
     * 解析逗号分隔的参数
     * 支持:
     * - 字符串 (带引号)
     * - 数字
     * - 布尔值
     * - null
     */
    private static List<String> parseCommaDelimitedArguments(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = '\0';
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                current.append(c);
                escaped = true;
                continue;
            }

            if ((c == '"' || c == '\'') && !escaped) {
                if (!inQuotes) {
                    inQuotes = true;
                    quoteChar = c;
                    current.append(c);
                } else if (c == quoteChar) {
                    inQuotes = false;
                    current.append(c);
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == ',' && !inQuotes) {
                String part = current.toString().trim();
                if (!part.isEmpty()) {
                    result.add(part);
                }
                current = new StringBuilder();
                continue;
            }

            current.append(c);
        }

        // 添加最后一个参数
        String part = current.toString().trim();
        if (!part.isEmpty()) {
            result.add(part);
        }

        return result;
    }

    /**
     * 修复双重转义的JSON和异常格式
     */
    private static String unescapeDoubleEscapedJson(String trimmed, String original, String toolName) {
        // 处理双重转义的JSON: "{\"key\": \"value\"}"
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 2) {
            try {
                String unescaped = trimmed.substring(1, trimmed.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
                log.warn("Detected double-escaped JSON arguments for tool {}. Original: {}, Unescaped: {}",
                        toolName, original, unescaped);
                return unescaped;
            } catch (Exception e) {
                log.debug("Failed to unescape arguments, using as-is", e);
            }
        }

        // 处理包含转义引号的JSON字符串: {\"key\": \"value\"}
        if (trimmed.contains("\\\"")) {
            try {
                String unescaped = trimmed.replace("\\\"", "\"");
                // 验证是否是有效的JSON格式
                if ((unescaped.startsWith("{") && unescaped.endsWith("}")) ||
                        (unescaped.startsWith("[") && unescaped.endsWith("]"))) {
                    log.warn("Detected escaped quotes in JSON arguments for tool {}. Original: {}, Unescaped: {}",
                            toolName, original, unescaped);
                    return unescaped;
                }
            } catch (Exception e) {
                log.debug("Failed to unescape quotes in arguments, using as-is", e);
            }
        }

        return trimmed;
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
    private static String convertArrayToObject(String arguments, Tool<?> tool, String toolName, ObjectMapper objectMapper) {
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
    private static List<Field> getOrderedFields(Class<?> paramsType) {
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

}
