package io.leavesfly.jimi.knowledge.wiki;

import io.leavesfly.jimi.core.Engine;
import io.leavesfly.jimi.knowledge.rag.CodeChunk;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    
    @Autowired(required = false)
    private WikiIndexManager wikiIndexManager;
    
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
     */
    private String buildWikiGenerationPrompt(Path wikiPath, String workDir) {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("# Wiki 文档生成任务\n\n");
        prompt.append("请你为当前项目生成一套完整的 Wiki 文档系统。\n\n");
        
        prompt.append("## 项目信息\n\n");
        prompt.append(String.format("- **项目目录**: %s\n", workDir));
        prompt.append(String.format("- **Wiki 输出目录**: %s\n", wikiPath.toAbsolutePath()));
        prompt.append(String.format("- **生成时间**: %s\n\n", dateStr));
        
        prompt.append("## 你需要完成的工作\n\n");
        prompt.append("### 第一步：分析项目\n\n");
        prompt.append("使用工具（ReadFile、Grep 等）深入分析项目：\n");
        prompt.append("1. 阅读项目的 README、pom.xml/build.gradle 等了解项目概况\n");
        prompt.append("2. 浏览源代码目录结构，识别核心模块和包\n");
        prompt.append("3. 阅读核心类和接口的源代码，理解架构设计\n");
        prompt.append("4. 分析模块间的依赖关系和调用链路\n\n");
        
        prompt.append("### 第二步：规划文档结构\n\n");
        prompt.append("根据项目分析结果，自行决定需要生成哪些文档。参考结构：\n");
        prompt.append("- `README.md` — Wiki 首页和文档导航（必需）\n");
        prompt.append("- `architecture/` — 架构设计文档（系统架构、模块设计等）\n");
        prompt.append("- `api/` — 核心接口和 API 文档\n");
        prompt.append("- `guides/` — 使用指南和开发指南\n");
        prompt.append("- 根据项目特点自行增减文档\n\n");
        
        prompt.append("### 第三步：生成文档并写入文件\n\n");
        prompt.append(String.format("使用 WriteFile 工具将每个文档写入 `%s` 目录下对应的路径。\n\n", 
                wikiPath.toAbsolutePath()));
        
        // 附加检索增强的参考代码（如果可用）
        appendRetrievalContext(prompt);
        
        prompt.append("## 文档质量要求\n\n");
        prompt.append("1. **基于真实代码**：所有内容必须基于你实际阅读的源代码，不要编造\n");
        prompt.append("2. **中文撰写**：使用中文，结构清晰，层次分明\n");
        prompt.append("3. **包含代码示例**：关键接口和用法要附带代码示例\n");
        prompt.append("4. **包含 Mermaid 图表**：架构文档应包含 Mermaid 格式的架构图、类图或流程图\n");
        prompt.append(String.format("5. **标注生成时间**：每个文档开头标注生成时间 %s\n", dateStr));
        prompt.append("6. **文档间交叉引用**：README.md 中包含所有文档的导航链接\n\n");
        
        prompt.append("## 注意事项\n\n");
        prompt.append("- 直接使用工具写入文件，不需要在对话中输出文档内容\n");
        prompt.append("- 如果需要创建子目录，先创建目录再写入文件\n");
        prompt.append("- 每个文档独立成文，但通过 README.md 串联\n");
        
        return prompt.toString();
    }
    
    /**
     * 附加检索增强的参考代码到提示词中
     * <p>
     * 如果 WikiIndexManager 可用，提供一些相关代码片段作为 Agent 的分析起点。
     */
    private void appendRetrievalContext(StringBuilder prompt) {
        if (wikiIndexManager == null) {
            return;
        }
        
        List<CodeChunk> architectureCode = wikiIndexManager.retrieveRelevantCode("系统架构 核心模块", 3);
        List<CodeChunk> apiCode = wikiIndexManager.retrieveRelevantCode("核心接口 API", 3);
        
        boolean hasContext = !architectureCode.isEmpty() || !apiCode.isEmpty();
        if (!hasContext) {
            return;
        }
        
        prompt.append("## 参考代码片段（分析起点）\n\n");
        prompt.append("以下是通过代码检索找到的相关片段，可作为你分析的起点，但请务必自行深入阅读源代码：\n\n");
        
        if (!architectureCode.isEmpty()) {
            prompt.append("### 架构相关\n\n");
            prompt.append(wikiIndexManager.formatCodeChunks(architectureCode));
        }
        
        if (!apiCode.isEmpty()) {
            prompt.append("### 接口相关\n\n");
            prompt.append(wikiIndexManager.formatCodeChunks(apiCode));
        }
        
        prompt.append("\n");
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
