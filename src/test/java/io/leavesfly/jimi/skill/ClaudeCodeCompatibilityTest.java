package io.leavesfly.jimi.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Claude Code Skill 兼容性测试
 */
class ClaudeCodeCompatibilityTest {
    
    private SkillLoader skillLoader;
    
    @BeforeEach
    void setUp() {
        skillLoader = new SkillLoader();
    }
    
    /**
     * 测试从 name 生成 triggers
     */
    @Test
    void testGenerateTriggersFromName() throws Exception {
        // 准备测试数据
        SkillSpec skill = SkillSpec.builder()
                .name("api-design")
                .description("RESTful API design guide")
                .build();
        
        // 使用反射调用私有方法
        Method method = SkillLoader.class.getDeclaredMethod("generateAutoTriggers", SkillSpec.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) method.invoke(skillLoader, skill);
        
        // 验证结果
        assertNotNull(triggers);
        assertFalse(triggers.isEmpty());
        
        // 应该包含完整名称和拆分后的单词
        assertTrue(triggers.contains("api design"), "应包含完整名称 'api design'");
        assertTrue(triggers.contains("api"), "应包含拆分词 'api'");
        assertTrue(triggers.contains("design"), "应包含拆分词 'design'");
        
        System.out.println("Generated triggers: " + triggers);
    }
    
    /**
     * 测试从 description 提取关键词
     */
    @Test
    void testGenerateTriggersFromDescription() throws Exception {
        SkillSpec skill = SkillSpec.builder()
                .name("code-review")
                .description("Code review best practices and conventions guide")
                .build();
        
        Method method = SkillLoader.class.getDeclaredMethod("generateAutoTriggers", SkillSpec.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) method.invoke(skillLoader, skill);
        
        assertNotNull(triggers);
        assertFalse(triggers.isEmpty());
        
        // 应该包含有意义的关键词
        assertTrue(triggers.contains("code review") || triggers.contains("code"));
        assertTrue(triggers.stream().anyMatch(t -> t.contains("review")));
        
        System.out.println("Generated triggers: " + triggers);
    }
    
    /**
     * 测试中文 description
     */
    @Test
    void testGenerateTriggersFromChineseDescription() throws Exception {
        SkillSpec skill = SkillSpec.builder()
                .name("unit-testing")
                .description("单元测试最佳实践指南")
                .build();
        
        Method method = SkillLoader.class.getDeclaredMethod("generateAutoTriggers", SkillSpec.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) method.invoke(skillLoader, skill);
        
        assertNotNull(triggers);
        assertFalse(triggers.isEmpty());
        
        // 应该包含中文关键词
        assertTrue(triggers.stream().anyMatch(t -> t.contains("单元测试") || t.contains("测试")));
        
        System.out.println("Generated triggers: " + triggers);
    }
    
    /**
     * 测试空 triggers 的兜底机制
     */
    @Test
    void testFallbackToName() throws Exception {
        SkillSpec skill = SkillSpec.builder()
                .name("my-skill")
                .description("")  // 空 description
                .build();
        
        Method method = SkillLoader.class.getDeclaredMethod("generateAutoTriggers", SkillSpec.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> triggers = (List<String>) method.invoke(skillLoader, skill);
        
        assertNotNull(triggers);
        assertFalse(triggers.isEmpty());
        
        // 至少应该包含 name
        assertTrue(triggers.contains("my skill") || triggers.contains("my-skill"));
        
        System.out.println("Generated triggers: " + triggers);
    }
}
