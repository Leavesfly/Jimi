package io.leavesfly.jimi.command.wiki;

import io.leavesfly.jimi.core.engine.Engine;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Wiki 文档生成引擎
 * <p>
 * 核心设计理念：将整个 Wiki 生成任务完全交给 Agent 引擎自主完成。
 * <p>
 * WikiGenerator 只负责：
 * 1. 构建一个高质量的提示词，描述清楚任务目标和约束
 * 2. 将提示词交给 Agent 引擎执行
 * 3. 检查生成结果并返回统计信息
 * <p>
 * Agent 引擎会自主利用工具链（ReadFile、Grep、WriteFile 等）完成：
 * - 分析项目代码结构和架构
 * - 规划需要生成哪些文档
 * - 深入阅读代码理解实现细节
 * - 生成高质量的 Markdown 文档并写入文件
 */
@Slf4j
@Component
public class WikiGenerator {

    // 文档缓存（用于避免短时间内重复生成）
    private final Map<String, CachedDocument> documentCache = new ConcurrentHashMap<>();
    
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    
    /**
     * 生成完整 Wiki 文档系统
     * <p>
     * 构建提示词后完全交给 Agent 引擎自主完成。Agent 会利用工具链
     * 分析项目代码、规划文档结构、生成文档内容并写入文件。
     *
     * @param wikiPath Wiki 目录路径
     * @param workDir  工作目录
     * @param engine   Agent 引擎实例
     * @return 生成任务的 Future
     */
    public CompletableFuture<GenerationResult> generateWiki(Path wikiPath, String workDir, Engine engine) {
        return CompletableFuture.supplyAsync(() -> {
            GenerationResult result = new GenerationResult();
            result.startTime = System.currentTimeMillis();
            
            try {
                log.info("Starting wiki generation via Agent engine: {}", wikiPath);
                
                // 确保 Wiki 目录存在
                Files.createDirectories(wikiPath);
                
                // 构建提示词，完全交给 Agent 引擎自主完成
                String prompt = buildWikiGenerationPrompt(wikiPath, workDir);
                
                // 执行：Agent 引擎会自主分析代码、规划文档、生成并写入文件
                engine.run(prompt).block();
                
                // 统计生成结果
                countGeneratedDocuments(wikiPath, result);
                
                result.endTime = System.currentTimeMillis();
                result.success = true;
                
                log.info("Wiki generation completed in {} ms, {} documents generated",
                        result.getDuration(), result.generatedDocs);
                
            } catch (Exception e) {
                log.error("Wiki generation failed", e);
                result.success = false;
                result.errorMessage = e.getMessage();
                result.endTime = System.currentTimeMillis();
            }
            
            return result;
            
        }, executor);
    }
    
    /**
     * 构建 Wiki 生成的完整提示词
     * <p>
     * 这是整个 WikiGenerator 的核心：一个精心设计的提示词，
     * 让 Agent 引擎能够自主完成从代码分析到文档生成的全部工作。
     * <p>
     * 执行策略：广度扫描 → 深度阅读 → TodoList 规划 → 逐篇写入，
     * 质量要求：真实性、完整性、设计意图深度、源码可溯源性。
     */
    private String buildWikiGenerationPrompt(Path wikiPath, String workDir) {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        String wikiAbsPath = wikiPath.toAbsolutePath().toString();
        StringBuilder prompt = new StringBuilder();

        // ── 任务目标
        prompt.append("# Wiki 文档生成任务\n\n");
        prompt.append("为以下代码项目生成一套**完整、高质量**的 Wiki 知识库。\n");
        prompt.append("所有文档必须基于真实源代码，通过工具逐文件阅读后撰写，严禁凭空编造。\n\n");
        prompt.append("## 基本信息\n\n");
        prompt.append(String.format("- **项目目录**: `%s`\n", workDir));
        prompt.append(String.format("- **Wiki 输出目录**: `%s`\n", wikiAbsPath));
        prompt.append(String.format("- **生成时间**: %s\n\n", dateStr));
        prompt.append("---\n\n");

        // ── Step 1：广度扫描
        prompt.append("## Step 1：项目整体扫描（广度优先）\n\n");
        prompt.append("使用工具快速建立项目全局认知，依次阅读：\n\n");
        prompt.append("1. `README.md` — 项目简介、核心功能、快速入门\n");
        prompt.append("2. `pom.xml` / `build.gradle` / `package.json` — 技术栈、关键依赖、构建方式\n");
        prompt.append("3. 顶层目录结构 — 识别模块划分（src、resources、docs、scripts 等）\n");
        prompt.append("4. 入口类 / 主启动文件 — 了解程序启动方式和整体骨架\n\n");
        
        // ── Step 2：深度阅读
        prompt.append("## Step 2：核心代码深度阅读（深度优先）\n\n");
        prompt.append("针对每个关键模块，逐一深入阅读以下内容：\n\n");
        prompt.append("- **接口 / 抽象层**：理解契约设计和扩展点\n");
        prompt.append("- **核心实现类**：理解算法、业务逻辑和关键设计决策\n");
        prompt.append("- **配置与工厂类**：理解组件装配方式和生命周期管理\n");
        prompt.append("- **关键调用链路**：用 Grep 追踪跨模块的方法调用，还原完整执行流程\n\n");
        prompt.append("分析时重点识别：\n\n");
        prompt.append("- 继承 / 实现关系（`extends` / `implements`）\n");
        prompt.append("- 依赖注入（`@Autowired` / 构造器注入）\n");
        prompt.append("- 设计模式（策略、工厂、观察者、责任链等）\n");
        prompt.append("- 核心数据流向（输入 → 处理 → 输出）\n\n");

        // ── Step 3：规划文档
        prompt.append("## Step 3：制定文档计划（先规划，再写文件）\n\n");
        prompt.append("在开始写任何文件之前，先用 **TodoList 工具**列出所有待生成文档，例如：\n\n");
        prompt.append("- [ ] `README.md` — Wiki 首页与完整导航\n");
        prompt.append("- [ ] `overview.md` — 项目概述与核心价値\n");
        prompt.append("- [ ] `architecture/system-design.md` — 系统架构总览\n");
        prompt.append("- [ ] `architecture/module-overview.md` — 模块划分与职责说明\n");
        prompt.append("- [ ] `api/core-interfaces.md` — 核心接口与数据模型参考\n");
        prompt.append("- [ ] `guides/getting-started.md` — 快速入门指南\n");
        prompt.append("- [ ] `guides/developer-guide.md` — 开发者扩展指南\n");
        prompt.append("- [ ] ……根据项目实际特点增减文档\n\n");

        // ── Step 4：生成并写入
        prompt.append("## Step 4：逐篇生成文档并写入文件\n\n");
        prompt.append(String.format(
                "按计划逐一生成每篇文档，写完一篇立即用 WriteFile 工具写入 `%s` 对应路径，"
                + "不要在对话中直接输出文档全文。\n", wikiAbsPath));
        prompt.append("每篇写完后更新 TodoList 状态为已完成。\n\n");
        prompt.append("---\n\n");

        // ── 质量标准
        prompt.append("## 文档质量标准\n\n");
        prompt.append("### 内容深度\n\n");
        prompt.append("| 维度 | 要求 |\n");
        prompt.append("|------|------|\n");
        prompt.append("| 真实性 | 每条论述须有对应源码支撑，文档末尾用「源码位置」注释标注文件路径 |\n");
        prompt.append("| 完整性 | 覆盖架构设计、核心模块、API 参考、使用指南四个维度 |\n");
        prompt.append("| 深度 | 不只描述“是什么”，还要解释“为什么这样设计”（设计意图）|\n");
        prompt.append("| 示例 | 关键接口、扩展点、核心用法须附带带语言标注的代码块 |\n\n");
        prompt.append("### 格式规范\n\n");
        prompt.append("- **语言**：中文撰写，技术术语保留英文原名\n");
        prompt.append(String.format(
                "- **文档头部**：每篇文档第二行写生成时间注释 `<!-- 生成时间: %s -->`\n", dateStr));
        prompt.append("- **Mermaid 图**：架构文档必须包含 Mermaid 图（flowchart / sequenceDiagram / classDiagram）\n");
        prompt.append("- **层级结构**：使用 `##` / `###` 分层，单个段落不超过 6 行\n");
        prompt.append("- **交叉引用**：文档间使用相对路径互相链接，README.md 包含所有文档导航目录\n\n");
        prompt.append("### README.md 必须包含\n\n");
        prompt.append("1. 项目一句话简介\n");
        prompt.append("2. 核心功能列表\n");
        prompt.append("3. 完整的文档导航目录（带相对路径链接）\n");
        prompt.append("4. 快速开始入口\n\n");
        prompt.append("---\n\n");

        // ── 注意事项
        prompt.append("## 注意事项\n\n");
        prompt.append("- 分析阶段尽量批量读取文件，减少工具调用轮次\n");
        prompt.append("- 每篇文档**独立成文**，读者无需依赖其他文档即可理解核心内容\n");
        prompt.append("- 若发现代码中有 TODO / FIXME / HACK 注释，在开发者指南中单独说明已知问题\n");
        prompt.append(String.format(
                "- 全部文档写完后，输出一行摘要：共生成 N 个文档，位于 `%s`\n", wikiAbsPath));
        
        return prompt.toString();
    }

    
    /**
     * 统计 Agent 生成的文档数量
     */
    private void countGeneratedDocuments(Path wikiPath, GenerationResult result) {
        try (Stream<Path> paths = Files.walk(wikiPath)) {
            long count = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> {
                        try {
                            return Files.size(p) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .count();
            result.generatedDocs = (int) count;
        } catch (IOException e) {
            log.warn("Failed to count generated documents", e);
        }
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        documentCache.clear();
        log.info("Document cache cleared");
    }
    
    /**
     * 关闭生成器
     */
    public void shutdown() {
        executor.shutdown();
        log.info("WikiGenerator shutdown");
    }
    
    /**
     * 缓存文档（内部数据结构）
     */
    @Data
    @Builder
    private static class CachedDocument {
        private String content;
        private long timestamp;
    }
    
    /**
     * 生成结果
     */
    @Data
    public static class GenerationResult {
        private boolean success;
        private String errorMessage;
        private int generatedDocs;
        private int cachedDocs;
        private long startTime;
        private long endTime;
        
        public long getDuration() {
            return endTime - startTime;
        }
    }
}
