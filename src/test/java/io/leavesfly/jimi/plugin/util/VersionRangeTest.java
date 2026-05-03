package io.leavesfly.jimi.plugin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VersionRange} 的单元测试
 */
class VersionRangeTest {

    @Test
    @DisplayName("range 为 null 或空时视为无限制")
    void emptyRangeAlwaysMatches() {
        assertTrue(VersionRange.matches(null, "1.0.0"));
        assertTrue(VersionRange.matches("", "1.0.0"));
        assertTrue(VersionRange.matches("   ", "1.0.0"));
    }

    @Test
    @DisplayName("actualVersion 为 null 或空时视为不匹配")
    void nullActualFails() {
        assertFalse(VersionRange.matches(">=1.0.0", null));
        assertFalse(VersionRange.matches(">=1.0.0", ""));
    }

    @ParameterizedTest(name = "[{index}] range={0}, actual={1}, expected={2}")
    @CsvSource({
            // 等值匹配
            "'1.0.0', '1.0.0', true",
            "'1.0.0', '1.0.1', false",
            "'=1.0.0', '1.0.0', true",

            // 单边约束
            "'>=1.0.0', '1.0.0', true",
            "'>=1.0.0', '1.0.1', true",
            "'>=1.0.0', '0.9.9', false",
            "'<=2.0.0', '2.0.0', true",
            "'<=2.0.0', '2.0.1', false",
            "'>1.0.0',  '1.0.0', false",
            "'>1.0.0',  '1.0.1', true",
            "'<2.0.0',  '1.9.9', true",
            "'<2.0.0',  '2.0.0', false",

            // 范围约束（AND）
            "'>=1.0.0,<2.0.0', '1.2.3', true",
            "'>=1.0.0,<2.0.0', '2.0.0', false",
            "'>=1.0.0,<2.0.0', '0.9.9', false",

            // 短版本自动补 0
            "'>=17',     '17.0.0', true",
            "'>=17',     '17',     true",
            "'>=17',     '11',     false",

            // SNAPSHOT 后缀应被忽略
            "'>=1.0.0', '1.0.0-SNAPSHOT', true"
    })
    void parameterizedMatches(String range, String actual, boolean expected) {
        assertTrue(expected == VersionRange.matches(range, actual),
                () -> "range=" + range + ", actual=" + actual + ", expected=" + expected);
    }

    @Test
    @DisplayName("非法版本号视为不匹配")
    void invalidVersionFails() {
        assertFalse(VersionRange.matches(">=1.0.0", "not-a-version"));
        assertFalse(VersionRange.matches(">=not-a-version", "1.0.0"));
    }
}
