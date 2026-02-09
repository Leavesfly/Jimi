package io.leavesfly.jimi.adk.core.engine.toolcall;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
     */
    public static String normalizeToValidJson(String arguments, ObjectMapper objectMapper) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return "{}";
        }

        String normalized = arguments.trim();

        // 步骤 0: 先校验是否已经是合法的 JSON
        if (isStrictValidJson(normalized, objectMapper)) {
            return normalized;
        }

        log.debug("Input is not valid JSON, proceeding with normalization: {}", normalized);

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
     * 严格校验是否为合法的 JSON 字符串
     */
    private static boolean isStrictValidJson(String json, ObjectMapper objectMapper) {
        try {
            JsonParser parser = objectMapper.getFactory().createParser(json);
            JsonNode node = objectMapper.readTree(parser);

            // 检查是否还有多余的内容
            if (parser.nextToken() != null) {
                return false;
            }

            // 如果解析结果是一个字符串值，且看起来像 JSON，则还需要继续处理
            if (node.isTextual()) {
                String textValue = node.asText();
                if (textValue.trim().startsWith("{") || textValue.trim().startsWith("[")) {
                    return false;
                }
            }

            // 如果解析结果是一个对象，检查其字段值是否有字符串形式的 JSON
            if (node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                var fields = objectNode.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    JsonNode fieldValue = entry.getValue();
                    if (fieldValue.isTextual()) {
                        String trimmedValue = fieldValue.asText().trim();
                        if ((trimmedValue.startsWith("[") && trimmedValue.endsWith("]")) ||
                            (trimmedValue.startsWith("{") && trimmedValue.endsWith("}"))) {
                            return false;
                        }
                    }
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 移除开头的 null
     */
    private static String removeNullPrefix(String input) {
        String trimmed = input.trim();
        String original = trimmed;

        while (trimmed.startsWith("null")) {
            trimmed = trimmed.substring(4).trim();
        }

        if (!trimmed.equals(original)) {
            if (trimmed.startsWith("{") || trimmed.startsWith("[") ||
                    (trimmed.startsWith("\"") && trimmed.length() > 2)) {
                log.warn("Removed 'null' prefix from arguments: {} -> {}", original, trimmed);
                return trimmed;
            } else {
                return original;
            }
        }

        return trimmed;
    }

    /**
     * 移除末尾的 null
     */
    private static String removeNullSuffix(String input) {
        String trimmed = input.trim();
        String original = trimmed;

        // 循环移除外层所有末尾的 "null"
        while (trimmed.endsWith("null")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4).trim();
        }

        // 如果被引号包裹，检查去掉引号后是否以 null 结尾
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 2) {
            String withoutQuotes = trimmed.substring(1, trimmed.length() - 1);
            if (withoutQuotes.endsWith("null")) {
                trimmed = withoutQuotes;
                while (trimmed.endsWith("null")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 4).trim();
                }
                // 清理残留的末尾引号
                while (trimmed.endsWith("\"")) {
                    String withoutTrailingQuote = trimmed.substring(0, trimmed.length() - 1);
                    if ((withoutTrailingQuote.startsWith("{") && withoutTrailingQuote.endsWith("}")) ||
                            (withoutTrailingQuote.startsWith("[") && withoutTrailingQuote.endsWith("]"))) {
                        trimmed = withoutTrailingQuote;
                    } else {
                        break;
                    }
                }
            }
        }

        if (!trimmed.equals(original)) {
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                    (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
                    (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 2)) {
                log.warn("Removed 'null' suffix from arguments: {} -> {}", original, trimmed);
                return trimmed;
            } else {
                return original;
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
     */
    private static String escapeUnescapedQuotesInValues(String input) {
        if ((!input.startsWith("{") || !input.endsWith("}")) &&
                (!input.startsWith("[") || !input.endsWith("]"))) {
            return input;
        }

        try {
            StringBuilder result = new StringBuilder();
            boolean inString = false;
            boolean afterColon = false;

            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                char prev = i > 0 ? input.charAt(i - 1) : '\0';
                boolean isEscaped = (prev == '\\');

                if (c == '"' && !isEscaped) {
                    if (!inString) {
                        inString = true;
                        result.append(c);
                    } else {
                        int nextNonSpace = findNextNonSpaceChar(input, i + 1);
                        if (afterColon && nextNonSpace != -1) {
                            char nextChar = input.charAt(nextNonSpace);
                            if (nextChar != ',' && nextChar != '}' && nextChar != ']') {
                                result.append("\\\"");
                                continue;
                            }
                        }
                        inString = false;
                        afterColon = false;
                        result.append(c);
                    }
                } else if (!inString) {
                    if (c == ':') {
                        afterColon = true;
                    } else if (c == ',' || c == '{' || c == '[') {
                        afterColon = false;
                    }
                    result.append(c);
                } else {
                    if (!isEscaped && afterColon) {
                        switch (c) {
                            case '\n': result.append("\\n"); break;
                            case '\r': result.append("\\r"); break;
                            case '\t': result.append("\\t"); break;
                            case '\\': result.append("\\\\"); break;
                            default: result.append(c);
                        }
                    } else {
                        result.append(c);
                    }
                }
            }

            String resultStr = result.toString();
            if (!resultStr.equals(input)) {
                log.warn("Escaped unescaped quotes and special chars in JSON values");
                return resultStr;
            }
        } catch (Exception e) {
            log.debug("Failed to escape unescaped quotes, using as-is", e);
        }

        return input;
    }

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
     */
    private static String fixMissingQuotes(String input) {
        if (!input.startsWith("{") || !input.endsWith("}")) {
            return input;
        }
        try {
            String fixed = input
                    .replaceAll("\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", "{\"$1\":")
                    .replaceAll(",\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", ",\"$1\":");
            if (!fixed.equals(input)) {
                log.warn("Fixed missing quotes in JSON keys");
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
            switch (c) {
                case '{': openCurly++; break;
                case '}': closeCurly++; break;
                case '[': openSquare++; break;
                case ']': closeSquare++; break;
            }
        }

        StringBuilder result = new StringBuilder(input);
        for (int i = 0; i < openCurly - closeCurly; i++) {
            result.append('}');
        }
        for (int i = 0; i < openSquare - closeSquare; i++) {
            result.append(']');
        }

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
            log.warn("Fixed unbalanced brackets");
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

            if (c == '\\' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (inString && (next == '"' || next == '\'' || next == '\\' ||
                        next == '/' || next == 'b' || next == 'f' ||
                        next == 'n' || next == 'r' || next == 't' || next == 'u')) {
                    result.append(c);
                } else if (!inString) {
                    result.append(c);
                } else {
                    // 非法转义，移除反斜杠
                    log.debug("Removed illegal escape: \\{}", next);
                }
            } else {
                result.append(c);
            }
        }

        String resultStr = result.toString();
        if (!resultStr.equals(input)) {
            log.warn("Fixed illegal escapes");
        }
        return resultStr;
    }

    /**
     * 安全的逗号分隔参数转换
     */
    private static String convertCommaDelimitedToJsonSafe(String input) {
        if (input.startsWith("{") || input.startsWith("[")) {
            return input;
        }
        if (input.startsWith("\"") && input.endsWith("\"") && input.length() > 2) {
            return input;
        }
        if (!input.contains(",")) {
            return "[" + input + "]";
        }
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
                } else if (c == quoteChar) {
                    inQuotes = false;
                }
                current.append(c);
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

        String part = current.toString().trim();
        if (!part.isEmpty()) {
            result.add(part);
        }
        return result;
    }
}
