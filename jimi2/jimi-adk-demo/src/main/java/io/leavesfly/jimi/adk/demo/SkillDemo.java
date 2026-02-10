package io.leavesfly.jimi.adk.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.skill.*;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * Skill 系统演示示例
 * <p>
 * 演示 Skill 系统的核心功能，包括 Skill 定义、加载、匹配、注入和依赖管理。
 * </p>
 */
@Slf4j
public class SkillDemo {

    public static void main(String[] args) {
        log.info("=== Skill 系统演示示例 ===");

        // 创建 LLM
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("✗ 请设置环境变量 OPENAI_API_KEY");
            System.exit(1);
        }

        LLMConfig llmConfig = LLMConfig.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .apiKey(apiKey)
                .build();

        LLMFactory llmFactory = new LLMFactory();
        LLM llm = llmFactory.create(llmConfig);

        // 示例 1: 创建和注册 Skill
        log.info("\n--- 示例 1: 创建和注册 Skill ---");
        SkillRegistry registry = createSkillRegistry();
        registerSampleSkills(registry);

        // 示例 2: Skill 匹配
        log.info("\n--- 示例 2: Skill 匹配 ---");
        demoSkillMatching(registry);

        // 示例 3: Skill 注入
        log.info("\n--- 示例 3: Skill 注入 ---");
        demoSkillInjection(registry, llm);

        // 示例 4: Skill 依赖
        log.info("\n--- 示例 4: Skill 依赖 ---");
        demoSkillDependencies(registry);

        // 示例 5: 组合 Skill
        log.info("\n--- 示例 5: 组合 Skill ---");
        demoCompositeSkill(registry, llm);

        log.info("\n=== 示例完成 ===");
    }

    /**
     * 创建 Skill 注册表
     */
    private static SkillRegistry createSkillRegistry() {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        SkillLoader loader = new SkillLoader(yamlMapper);
        return new SkillRegistry(loader);
    }

    /**
     * 注册示例 Skills
     */
    private static void registerSampleSkills(SkillRegistry registry) {
        // 代码审查 Skill
        SkillSpec codeReview = SkillSpec.builder()
                .name("code-review")
                .description("代码审查最佳实践")
                .version("1.0.0")
                .category("development")
                .triggers(List.of("review", "code review", "审查代码"))
                .content("""
                        # 代码审查指南
                        
                        进行代码审查时，请关注以下方面：
                        1. 代码可读性和命名规范
                        2. 潜在的性能问题
                        3. 安全漏洞
                        4. 测试覆盖率
                        5. 设计模式应用
                        """)
                .scope(SkillScope.GLOBAL)
                .build();

        // 单元测试 Skill
        SkillSpec unitTesting = SkillSpec.builder()
                .name("unit-testing")
                .description("单元测试编写指南")
                .version("1.0.0")
                .category("testing")
                .triggers(List.of("test", "unit test", "测试"))
                .content("""
                        # 单元测试指南
                        
                        编写单元测试时，请遵循 AAA 模式：
                        - Arrange: 准备测试数据
                        - Act: 执行被测代码
                        - Assert: 验证结果
                        
                        确保测试覆盖边界条件和异常情况。
                        """)
                .scope(SkillScope.GLOBAL)
                .build();

        // 重构 Skill（依赖代码审查）
        SkillSpec refactoring = SkillSpec.builder()
                .name("refactoring")
                .description("代码重构最佳实践")
                .version("1.0.0")
                .category("development")
                .triggers(List.of("refactor", "重构"))
                .dependencies(List.of("code-review"))  // 声明依赖
                .content("""
                        # 代码重构指南
                        
                        重构前先进行代码审查，识别需要改进的地方。
                        
                        常见重构手法：
                        - 提取方法
                        - 内联变量
                        - 移动方法
                        - 重命名
                        """)
                .scope(SkillScope.GLOBAL)
                .build();

        // 注册 Skills
        registry.register(codeReview);
        registry.register(unitTesting);
        registry.register(refactoring);

        log.info("✓ 已注册 {} 个 Skills", registry.getAllSkills().size());
        log.info("  - code-review: {}", codeReview.getDescription());
        log.info("  - unit-testing: {}", unitTesting.getDescription());
        log.info("  - refactoring: {} (依赖: {})", 
                refactoring.getDescription(), refactoring.getDependencies());
    }

    /**
     * Skill 匹配演示
     */
    private static void demoSkillMatching(SkillRegistry registry) {
        SkillMatcher matcher = new SkillMatcher(registry);

        String[] testInputs = {
                "帮我 review 这段代码",
                "怎么写单元测试？",
                "需要重构这个类"
        };

        for (String input : testInputs) {
            log.info("[匹配] 输入: '{}'", input);
            
            List<SkillMatchResult> matches = matcher.match(input, 3);
            
            if (matches.isEmpty()) {
                log.info("  → 未匹配到 Skill");
            } else {
                matches.forEach(match -> 
                    log.info("  → 匹配: {} (得分: {})", 
                            match.getSkill().getName(), match.getScore())
                );
            }
        }
    }

    /**
     * Skill 注入演示
     */
    private static void demoSkillInjection(SkillRegistry registry, LLM llm) {
        SkillInjector injector = new SkillInjector(registry);

        // 匹配并注入代码审查 Skill
        SkillSpec codeReview = registry.findByName("code-review").orElseThrow();
        String injectedContent = injector.formatSkillsForInjection(List.of(codeReview));

        log.info("[注入] 已注入 Skill: {}", codeReview.getName());
        log.info("[注入] 激活的 Skills 数量: {}", injector.getActiveSkills().size());
        log.info("[注入] 注入内容长度: {} 字符", 
                injectedContent != null ? injectedContent.length() : 0);

        // 创建 Agent 并运行
        Agent agent = Agent.builder()
                .name("skill-agent")
                .description("带 Skill 的 Agent")
                .systemPrompt("你是一个专业开发者，根据激活的 Skill 提供指导。\n\n" + 
                        (injectedContent != null ? injectedContent : ""))
                .build();

        JimiRuntime runtime = JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(Paths.get(System.getProperty("user.dir"))
                        .resolve(".skill-demo"))
                .build();

        // 执行对话
        runtime.getEngine().run("请帮我审查这段代码: public void test() { }")
                .doOnNext(result -> log.info("[注入] Agent: {}...", 
                        result.getResponse().substring(0, 
                                Math.min(50, result.getResponse().length()))))
                .block();

        // 清理激活状态
        injector.clearActiveSkills();
        log.info("[注入] 已清理所有 Skills");
    }

    /**
     * Skill 依赖演示
     */
    private static void demoSkillDependencies(SkillRegistry registry) {
        SkillSpec refactoring = registry.findByName("refactoring").orElseThrow();
        
        log.info("[依赖] Skill: {}", refactoring.getName());
        log.info("[依赖] 声明的依赖: {}", refactoring.getDependencies());

        // 检查依赖是否满足
        List<String> deps = refactoring.getDependencies();
        boolean allDepsSatisfied = deps.stream()
                .allMatch(dep -> registry.findByName(dep).isPresent());

        log.info("[依赖] 依赖满足: {}", allDepsSatisfied ? "是" : "否");

        // 获取依赖解析顺序（拓扑排序）
        if (allDepsSatisfied) {
            log.info("[依赖] 解析顺序:");
            for (String dep : deps) {
                SkillSpec depSkill = registry.findByName(dep).orElseThrow();
                log.info("  1. {} - {}", depSkill.getName(), depSkill.getDescription());
            }
            log.info("  2. {} - {}", refactoring.getName(), refactoring.getDescription());
        }
    }

    /**
     * 组合 Skill 演示
     */
    private static void demoCompositeSkill(SkillRegistry registry, LLM llm) {
        // 创建组合 Skill（同时依赖多个 Skill）
        SkillSpec fullStackDev = SkillSpec.builder()
                .name("fullstack-dev")
                .description("全栈开发工作流")
                .version("1.0.0")
                .category("workflow")
                .triggers(List.of("fullstack", "开发流程"))
                .dependencies(List.of("code-review", "unit-testing"))
                .content("""
                        # 全栈开发工作流
                        
                        1. 编写代码
                        2. 进行代码审查（code-review Skill）
                        3. 编写单元测试（unit-testing Skill）
                        4. 重构优化
                        5. 集成测试
                        """)
                .scope(SkillScope.GLOBAL)
                .build();

        registry.register(fullStackDev);
        log.info("[组合] 注册组合 Skill: {}", fullStackDev.getName());
        log.info("[组合] 依赖: {}", fullStackDev.getDependencies());

        // 激活组合 Skill（自动注入依赖）
        SkillInjector injector = new SkillInjector(registry);
        String compositeContent = injector.formatSkillsForInjection(List.of(fullStackDev));

        log.info("[组合] 激活的 Skills:");
        injector.getActiveSkills().forEach(skill -> 
            log.info("  - {}", skill.getName())
        );
        log.info("[组合] 注入内容长度: {} 字符", 
                compositeContent != null ? compositeContent.length() : 0);

        // 执行对话
        Agent agent = Agent.builder()
                .name("fullstack-agent")
                .description("全栈开发 Agent")
                .systemPrompt("你是一个全栈开发者，遵循完整开发流程。\n\n" +
                        (compositeContent != null ? compositeContent : ""))
                .build();

        JimiRuntime runtime = JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(Paths.get(System.getProperty("user.dir"))
                        .resolve(".skill-demo-fullstack"))
                .build();

        runtime.getEngine().run("我要开发一个新功能，应该遵循什么流程？")
                .doOnNext(result -> log.info("[组合] Agent: {}...", 
                        result.getResponse().substring(0, 
                                Math.min(80, result.getResponse().length()))))
                .block();
    }

    /**
     * Skill 匹配结果（简化版）
     */
    private static class SkillMatchResult {
        private final SkillSpec skill;
        private final int score;

        public SkillMatchResult(SkillSpec skill, int score) {
            this.skill = skill;
            this.score = score;
        }

        public SkillSpec getSkill() {
            return skill;
        }

        public int getScore() {
            return score;
        }
    }

    /**
     * Skill 匹配器（简化版）
     */
    private static class SkillMatcher {
        private final SkillRegistry registry;

        public SkillMatcher(SkillRegistry registry) {
            this.registry = registry;
        }

        public List<SkillMatchResult> match(String input, int topK) {
            String lowerInput = input.toLowerCase();
            
            return registry.getAllSkills().stream()
                    .map(skill -> {
                        int score = calculateScore(skill, lowerInput);
                        return new SkillMatchResult(skill, score);
                    })
                    .filter(result -> result.getScore() > 0)
                    .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                    .limit(topK)
                    .toList();
        }

        private int calculateScore(SkillSpec skill, String input) {
            int score = 0;
            
            // 触发词匹配
            for (String trigger : skill.getTriggers()) {
                if (input.contains(trigger.toLowerCase())) {
                    score += 50;
                }
            }
            
            // 名称匹配
            if (input.contains(skill.getName().toLowerCase())) {
                score += 30;
            }
            
            // 分类匹配
            if (skill.getCategory() != null && 
                input.contains(skill.getCategory().toLowerCase())) {
                score += 20;
            }
            
            return Math.min(score, 100);
        }
    }
}
