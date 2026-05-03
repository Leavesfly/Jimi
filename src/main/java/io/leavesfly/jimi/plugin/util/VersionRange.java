package io.leavesfly.jimi.plugin.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量的语义化版本范围工具
 *
 * <p>用于 {@code plugin.yaml} 里的 {@code compatibility.jimi_version}、
 * {@code dependencies.version} 等版本约束字段的解析与匹配。
 *
 * <p>支持的语法（与 npm/maven 常见写法对齐的子集）：
 * <ul>
 *   <li>{@code "1.2.3"}：等值匹配</li>
 *   <li>{@code ">=1.0.0"} / {@code "&gt;=1.0.0"}：大于等于</li>
 *   <li>{@code "<=2.0.0"}：小于等于</li>
 *   <li>{@code ">1.0.0"} / {@code "<2.0.0"}：严格大于/小于</li>
 *   <li>{@code "=1.0.0"}：等于</li>
 *   <li>{@code ">=1.0.0,<2.0.0"}：逗号分隔的多约束（AND 语义）</li>
 * </ul>
 *
 * <p>版本号只比较 {@code major.minor.patch} 三段数字，忽略 {@code -SNAPSHOT} 等后缀。
 * 不符合三段格式时会尽量补 0（{@code "17"} → {@code "17.0.0"}）。
 *
 * <p>不支持 {@code ^} / {@code ~} 等缩写，有需要时再扩展。
 */
public final class VersionRange {

    private VersionRange() {
    }

    /**
     * 判断给定的实际版本是否满足 range 表达式。
     *
     * @param range          版本范围表达式，{@code null} 或空串视为无限制
     * @param actualVersion  实际版本号（如 {@code "1.0.0"}）
     * @return 是否满足
     */
    public static boolean matches(String range, String actualVersion) {
        if (range == null || range.trim().isEmpty()) {
            return true;
        }
        if (actualVersion == null || actualVersion.trim().isEmpty()) {
            return false;
        }

        int[] actual = parseVersion(actualVersion);
        if (actual == null) {
            return false;
        }

        for (String clause : range.split(",")) {
            String trimmed = clause.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!matchesClause(trimmed, actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesClause(String clause, int[] actual) {
        String operator;
        String versionStr;

        if (clause.startsWith(">=")) {
            operator = ">=";
            versionStr = clause.substring(2).trim();
        } else if (clause.startsWith("<=")) {
            operator = "<=";
            versionStr = clause.substring(2).trim();
        } else if (clause.startsWith(">")) {
            operator = ">";
            versionStr = clause.substring(1).trim();
        } else if (clause.startsWith("<")) {
            operator = "<";
            versionStr = clause.substring(1).trim();
        } else if (clause.startsWith("=")) {
            operator = "=";
            versionStr = clause.substring(1).trim();
        } else {
            operator = "=";
            versionStr = clause;
        }

        int[] expected = parseVersion(versionStr);
        if (expected == null) {
            return false;
        }

        int cmp = compare(actual, expected);
        return switch (operator) {
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case "=" -> cmp == 0;
            default -> false;
        };
    }

    /**
     * 解析版本号字符串为三段整数数组。
     *
     * <p>{@code "1.2.3-SNAPSHOT"} → {@code [1, 2, 3]}；
     * {@code "17"} → {@code [17, 0, 0]}；
     * 无法解析则返回 {@code null}。
     */
    private static int[] parseVersion(String versionStr) {
        if (versionStr == null) {
            return null;
        }
        String cleaned = versionStr.trim();
        int dashIdx = cleaned.indexOf('-');
        if (dashIdx >= 0) {
            cleaned = cleaned.substring(0, dashIdx);
        }
        int plusIdx = cleaned.indexOf('+');
        if (plusIdx >= 0) {
            cleaned = cleaned.substring(0, plusIdx);
        }
        if (cleaned.isEmpty()) {
            return null;
        }

        String[] parts = cleaned.split("\\.");
        List<Integer> segments = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            if (i < parts.length) {
                try {
                    segments.add(Integer.parseInt(parts[i].trim()));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                segments.add(0);
            }
        }
        return new int[]{segments.get(0), segments.get(1), segments.get(2)};
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int diff = Integer.compare(a[i], b[i]);
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }
}
