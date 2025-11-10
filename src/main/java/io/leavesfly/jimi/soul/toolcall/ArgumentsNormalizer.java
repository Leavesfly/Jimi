package io.leavesfly.jimi.soul.toolcall;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用参数标准化器
 * <p>
 * 职责：
 * - 标准化工具调用参数格式
 * - 处理双重转义的JSON
 * - 转换逗号分隔的参数为JSON格式
 * - 修复常见的参数格式问题
 */
@Slf4j
public class ArgumentsNormalizer {

    /**
     * 标准化参数
     *
     * @param arguments 原始参数字符串
     * @param toolName  工具名称
     * @return 标准化后的参数字符串
     */
    public static String normalize(String arguments, String toolName) {
        if (arguments == null || arguments.trim().isEmpty()) {
            log.warn("Empty or null arguments for tool: {}, using empty object", toolName);
            return "{}";
        }

        String trimmed = arguments.trim();

        trimmed = removeNull(trimmed, arguments, toolName);

        trimmed = unescapeDoubleEscapedJson(trimmed, arguments, toolName);

        // 转换逗号分隔的参数为JSON数组格式
        return convertCommaDelimitedToJson(trimmed, arguments, toolName);

    }

    /**
     * 移除字符串中所有的null
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
     * 例如: "/Users/yefei.yf/Jimi/java/README.md", 1, 100 -> ["/Users/yefei.yf/Jimi/java/README.md", 1, 100]
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

}
