package io.leavesfly.jimi.adk.knowledge.wiki;

import io.leavesfly.jimi.adk.api.llm.ChatCompletionChunk;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.knowledge.rag.CodeChunk;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Wiki 文档生成引擎
 * <p>
 * 职责：
 * - 分阶段生成 Wiki 文档
 * - 支持并发生成提升性能
 * - 集成检索增强提高质量
 * - 管理文档缓存避免重复生成
 */
@Slf4j
public class WikiGenerator {
    
    private final WikiIndexManager wikiIndexManager;
    private final DiagramGenerator diagramGenerator;
    
    // 文档缓存
    private final Map<String, CachedDocument> documentCache = new ConcurrentHashMap<>();
    
    // 并发执行器
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    
    public WikiGenerator(WikiIndexManager wikiIndexManager, DiagramGenerator diagramGenerator) {
        this.wikiIndexManager = wikiIndexManager;
        this.diagramGenerator = diagramGenerator;
    }
    
    /**
     * 生成完整 Wiki 文档系统
     *
     * @param wikiPath Wiki 目录路径
     * @param workDir  工作目录
     * @param llm      LLM 实例
     * @return 生成任务的 Future
     */
    public CompletableFuture<GenerationResult> generateWiki(Path wikiPath, String workDir, LLM llm) {
        return CompletableFuture.supplyAsync(() -> {
            GenerationResult result = new GenerationResult();
            result.startTime = System.currentTimeMillis();
            
            try {
                log.info("Starting wiki generation: {}", wikiPath);
                
                // Stage 1: 智能规划 - 分析项目结构决定生成哪些文档
                WikiPlan plan = planWikiStructure(workDir);
                
                if (plan == null || plan.documents.isEmpty()) {
                    log.warn("Failed to generate wiki plan, using fallback");
                    generateFallbackWiki(wikiPath, workDir, result);
                } else {
                    log.info("Wiki plan generated: {} documents", plan.documents.size());
                    
                    // Stage 2: 根据规划生成文档
                    generateFromPlan(wikiPath, workDir, llm, plan, result);
                }
                
                result.endTime = System.currentTimeMillis();
                result.success = true;
                
                log.info("Wiki generation completed in {} ms", result.getDuration());
                
            } catch (Exception e) {
                log.error("Wiki generation failed", e);
                result.success = false;
                result.errorMessage = e.getMessage();
            }
            
            return result;
            
        }, executor);
    }
    
    /**
     * 使用 LLM generateStream 收集完整响应
     */
    private String completeLlm(LLM llm, String prompt) {
        try {
            List<Message> messages = List.of(Message.user(prompt));
            
            String content = llm.generateStream(null, messages, null)
                    .filter(chunk -> chunk.getChoices() != null && !chunk.getChoices().isEmpty())
                    .map(chunk -> chunk.getChoices().get(0).getDelta())
                    .filter(delta -> delta != null && delta.getContent() != null)
                    .map(ChatCompletionChunk.Delta::getContent)
                    .collectList()
                    .map(parts -> String.join("", parts))
                    .block();
            
            return content;
        } catch (Exception e) {
            log.error("LLM completion failed", e);
            return null;
        }
    }
    
    /**
     * Wiki 规划结果
     */
    @Data
    private static class WikiPlan {
        private List<DocumentSpec> documents = new ArrayList<>();
        
        @Data
        private static class DocumentSpec {
            private String path;        // 文档路径，如 "architecture/overview.md"
            private String title;       // 文档标题
            private String description; // 文档描述
            private int priority;       // 优先级（1-5）
            private boolean needsDiagram; // 是否需要图表
        }
    }
    
    /**
     * 智能规划 Wiki 结构
     * <p>
     * 基于项目代码分析，动态决定生成哪些文档
     */
    private WikiPlan planWikiStructure(String workDir) {
        log.info("Planning wiki structure based on project analysis");
        
        try {
            // 分析项目代码结构
            String projectStructure = analyzeProjectStructure(workDir);
            
            // 基于分析结果构建智能规划
            return buildIntelligentPlan(projectStructure, workDir);
            
        } catch (Exception e) {
            log.error("Failed to plan wiki structure", e);
            return null;
        }
    }
    
    /**
     * 基于项目分析构建智能规划
     */
    private WikiPlan buildIntelligentPlan(String projectStructure, String workDir) {
        WikiPlan plan = new WikiPlan();
        
        // 分析项目特征
        boolean hasComplexArchitecture = projectStructure.contains("engine") || 
                                        projectStructure.contains("service");
        boolean hasApiLayer = projectStructure.contains("controller") || 
                             projectStructure.contains("api");
        boolean hasCommandSystem = projectStructure.contains("command");
        boolean hasAgentSystem = projectStructure.contains("agent");
        
        // 必需文档：README
        plan.documents.add(createDocSpec("README.md", "项目概览", 
            "项目整体介绍和文档导航", 5, false));
        
        // 架构文档（高优先级）
        if (hasComplexArchitecture) {
            plan.documents.add(createDocSpec("architecture/overview.md", "系统架构", 
                "系统整体架构设计和技术选型", 5, true));
            plan.documents.add(createDocSpec("architecture/modules.md", "模块设计", 
                "模块划分和依赖关系", 4, true));
        }
        
        // API 文档
        if (hasApiLayer) {
            plan.documents.add(createDocSpec("api/interfaces.md", "核心接口", 
                "主要接口和数据模型", 4, false));
        }
        
        // 命令系统文档
        if (hasCommandSystem) {
            plan.documents.add(createDocSpec("guides/commands.md", "命令使用指南", 
                "命令系统使用说明和示例", 4, false));
        }
        
        // Agent 系统文档
        if (hasAgentSystem) {
            plan.documents.add(createDocSpec("guides/agents.md", "Agent 开发指南", 
                "Agent 配置和开发说明", 4, false));
        }
        
        // 开发指南（中优先级）
        plan.documents.add(createDocSpec("guides/development.md", "开发指南", 
            "环境搭建、开发规范和最佳实践", 3, false));
        
        log.info("Intelligent plan created with {} documents", plan.documents.size());
        return plan;
    }
    
    /**
     * 创建文档规格
     */
    private WikiPlan.DocumentSpec createDocSpec(String path, String title, 
                                                String description, int priority, 
                                                boolean needsDiagram) {
        WikiPlan.DocumentSpec spec = new WikiPlan.DocumentSpec();
        spec.setPath(path);
        spec.setTitle(title);
        spec.setDescription(description);
        spec.setPriority(priority);
        spec.setNeedsDiagram(needsDiagram);
        return spec;
    }
    
    /**
     * 分析项目代码结构
     */
    private String analyzeProjectStructure(String workDir) {
        StringBuilder analysis = new StringBuilder();
        analysis.append("## 项目结构分析\n\n");
        
        Path srcPath = Path.of(workDir, "src");
        if (Files.exists(srcPath)) {
            try {
                // 统计包结构
                Map<String, Integer> packageCount = new HashMap<>();
                Files.walk(srcPath)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        String pkgPath = p.getParent().toString();
                        if (pkgPath.contains("src/main/java/")) {
                            String pkg = pkgPath.substring(pkgPath.indexOf("src/main/java/") + 14)
                                               .replace("/", ".");
                            packageCount.merge(pkg, 1, Integer::sum);
                        }
                    });
                
                analysis.append("### 主要模块\n");
                packageCount.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(10)
                    .forEach(e -> analysis.append(String.format("- `%s`: %d 个文件\n", e.getKey(), e.getValue())));
                
            } catch (IOException e) {
                log.warn("Failed to analyze project structure", e);
            }
        }
        
        return analysis.toString();
    }
    
    /**
     * 根据规划生成文档
     */
    private void generateFromPlan(Path wikiPath, String workDir, LLM llm, 
                                  WikiPlan plan, GenerationResult result) {
        log.info("Generating {} documents from plan", plan.documents.size());
        
        // 按优先级排序
        List<WikiPlan.DocumentSpec> sortedDocs = plan.documents.stream()
            .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
            .collect(Collectors.toList());
        
        // 并发生成（限制并发数）
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        
        for (WikiPlan.DocumentSpec doc : sortedDocs) {
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                try {
                    generateDocument(wikiPath, workDir, llm, doc, result);
                } catch (Exception e) {
                    log.error("Failed to generate document: {}", doc.getPath(), e);
                }
            }, executor);
            
            tasks.add(task);
        }
        
        // 等待所有文档生成完成
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
    }
    
    /**
     * 生成单个文档
     */
    private void generateDocument(Path wikiPath, String workDir, LLM llm,
                                  WikiPlan.DocumentSpec docSpec, GenerationResult result) throws Exception {
        String docKey = docSpec.getPath();
        
        // 检查缓存
        if (isCached(docKey)) {
            log.debug("Using cached document: {}", docKey);
            result.cachedDocs++;
            return;
        }
        
        log.info("Generating document: {} - {}", docSpec.getPath(), docSpec.getTitle());
        
        // 创建目录
        Path docPath = wikiPath.resolve(docSpec.getPath());
        Files.createDirectories(docPath.getParent());
        
        // 构建文档生成 Prompt
        String prompt = buildDocumentPrompt(workDir, docSpec);
        
        try {
            // 调用 LLM 生成文档内容
            String content = completeLlm(llm, prompt);
            
            if (content != null && !content.isEmpty()) {
                // 直接写入文件
                Files.writeString(docPath, content);
                result.generatedDocs++;
                cacheDocument(docKey, content);
                log.info("Document generated: {}", docPath);
            } else {
                log.warn("LLM returned empty content for: {}", docSpec.getPath());
                generatePlaceholder(docPath, docSpec.getTitle(), result);
            }
            
        } catch (Exception e) {
            log.error("Failed to generate with LLM: {}", docSpec.getPath(), e);
            // Fallback: 生成占位符
            generatePlaceholder(docPath, docSpec.getTitle(), result);
        }
    }
    
    /**
     * 构建文档生成 Prompt
     */
    private String buildDocumentPrompt(String workDir, WikiPlan.DocumentSpec docSpec) {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("请生成 Markdown 格式的 Wiki 文档：%s\n\n", docSpec.getTitle()));
        
        prompt.append("## 文档信息\n\n");
        prompt.append(String.format("- **标题**: %s\n", docSpec.getTitle()));
        prompt.append(String.format("- **描述**: %s\n\n", docSpec.getDescription()));
        
        // 集成检索增强
        if (wikiIndexManager != null && wikiIndexManager.isAvailable()) {
            List<CodeChunk> relevantCode = wikiIndexManager.retrieveRelevantCode(
                docSpec.getTitle() + " " + docSpec.getDescription(), 5);
            if (!relevantCode.isEmpty()) {
                prompt.append("## 参考代码\n\n");
                prompt.append(wikiIndexManager.formatCodeChunks(relevantCode));
                prompt.append("\n");
            }
        }
        
        // 集成图表生成
        if (docSpec.isNeedsDiagram() && diagramGenerator != null) {
            Path srcPath = Path.of(workDir, "src");
            if (Files.exists(srcPath)) {
                String diagram = "";
                if (docSpec.getPath().contains("architecture")) {
                    diagram = diagramGenerator.generateArchitectureOverview(srcPath);
                } else if (docSpec.getPath().contains("module")) {
                    diagram = diagramGenerator.generateModuleDiagram(srcPath);
                }
                
                if (!diagram.isEmpty()) {
                    prompt.append("## 架构图表\n\n");
                    prompt.append("请在文档中包含以下图表：\n\n");
                    prompt.append(diagram).append("\n\n");
                }
            }
        }
        
        prompt.append("## 输出要求\n\n");
        prompt.append("1. 中文撰写，结构清晰\n");
        prompt.append(String.format("2. 生成时间：%s\n", dateStr));
        prompt.append("3. 基于实际代码分析，内容准确详实\n");
        prompt.append("4. 直接输出 Markdown 内容，不要包含额外的说明\n");
        
        return prompt.toString();
    }
    
    /**
     * 生成 Fallback Wiki（规划失败时使用）
     */
    private void generateFallbackWiki(Path wikiPath, String workDir, GenerationResult result) throws Exception {
        log.info("Generating fallback wiki");
        
        // 生成基本文档
        generatePlaceholder(wikiPath.resolve("README.md"), "项目 Wiki", result);
        
        Path archDir = wikiPath.resolve("architecture");
        Files.createDirectories(archDir);
        generatePlaceholder(archDir.resolve("overview.md"), "系统架构概览", result);
    }
    
    /**
     * 生成占位文档
     */
    private void generatePlaceholder(Path docPath, String title, GenerationResult result) throws Exception {
        String docKey = docPath.getFileName().toString();
        
        if (isCached(docKey)) {
            result.cachedDocs++;
            return;
        }
        
        StringBuilder content = new StringBuilder();
        content.append("# ").append(title).append("\n\n");
        content.append("> 生成时间: ").append(getCurrentTimestamp()).append("\n\n");
        content.append("此文档待完善。\n\n");
        
        Files.writeString(docPath, content.toString());
        cacheDocument(docKey, content.toString());
        result.generatedDocs++;
    }
    
    /**
     * 检查文档是否已缓存
     */
    private boolean isCached(String docKey) {
        CachedDocument cached = documentCache.get(docKey);
        if (cached == null) {
            return false;
        }
        
        // 检查缓存是否过期（1小时）
        long expireTime = 3600000; // 1 hour
        return (System.currentTimeMillis() - cached.timestamp) < expireTime;
    }
    
    /**
     * 缓存文档
     */
    private void cacheDocument(String docKey, String content) {
        documentCache.put(docKey, CachedDocument.builder()
            .content(content)
            .timestamp(System.currentTimeMillis())
            .build());
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        documentCache.clear();
        log.info("Document cache cleared");
    }
    
    /**
     * 获取当前时间戳字符串
     */
    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    /**
     * 关闭生成器
     */
    public void shutdown() {
        executor.shutdown();
        log.info("WikiGenerator shutdown");
    }
    
    /**
     * 缓存文档
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
