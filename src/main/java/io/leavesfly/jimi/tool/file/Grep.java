package io.leavesfly.jimi.tool.file;

import io.leavesfly.jimi.soul.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.ToolResultBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grep 工具 - 使用正则表达式搜索文件内容
 * 简化实现，使用Java内置的文件遍历和正则表达式
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class Grep extends AbstractTool<Grep.Params> {
    
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private Path workDir;
    
    /**
     * 参数模型
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        /**
         * 正则表达式模式
         */
        private String pattern;
        
        /**
         * 搜索路径（文件或目录）
         */
        @Builder.Default
        private String path = ".";
        
        /**
         * Glob 模式过滤文件
         */
        @Builder.Default
        private String glob = null;
        
        /**
         * 输出模式：content, files_with_matches, count_matches
         */
        @Builder.Default
        private String outputMode = "files_with_matches";
        
        /**
         * 显示行号（仅 content 模式）
         */
        @Builder.Default
        private boolean lineNumber = false;
        
        /**
         * 忽略大小写
         */
        @Builder.Default
        private boolean ignoreCase = false;
        
        /**
         * 限制输出行数
         */
        @Builder.Default
        private Integer headLimit = null;
    }
    
    public Grep() {
        super(
            "Grep",
            "使用正则表达式搜索文件内容。",
            Params.class
        );
    }
    
    public void setBuiltinArgs(BuiltinSystemPromptArgs builtinArgs) {
        this.workDir = builtinArgs.getJimiWorkDir();
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.defer(() -> {
            try {
                // 验证参数
                if (params.pattern == null || params.pattern.trim().isEmpty()) {
                    return Mono.just(ToolResult.error(
                        "Pattern is required. Please provide a valid regex pattern.",
                        "Missing pattern"
                    ));
                }
                
                // 编译正则表达式
                int flags = params.ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
                Pattern pattern;
                try {
                    pattern = Pattern.compile(params.pattern, flags);
                } catch (PatternSyntaxException e) {
                    return Mono.just(ToolResult.error(
                        String.format("Invalid regex pattern: %s", e.getMessage()),
                        "Invalid pattern"
                    ));
                }
                
                // 确定搜索路径
                Path searchPath = ".".equals(params.path) ? workDir : Path.of(params.path);
                
                if (!searchPath.isAbsolute()) {
                    searchPath = workDir.resolve(searchPath);
                }
                
                if (!Files.exists(searchPath)) {
                    return Mono.just(ToolResult.error(
                        String.format("Path does not exist: %s", params.path),
                        "Path not found"
                    ));
                }
                
                // 执行搜索
                SearchResult result = performSearch(searchPath, pattern, params);
                
                // 生成输出
                return Mono.just(formatResult(result, params));
                
            } catch (Exception e) {
                log.error("Failed to grep: {}", params.pattern, e);
                return Mono.just(ToolResult.error(
                    String.format("Failed to grep. Error: %s", e.getMessage()),
                    "Failed to grep"
                ));
            }
        });
    }
    
    /**
     * 执行搜索
     */
    private SearchResult performSearch(Path searchPath, Pattern pattern, Params params) throws IOException {
        SearchResult result = new SearchResult();
        
        if (Files.isRegularFile(searchPath)) {
            // 搜索单个文件
            searchFile(searchPath, pattern, params, result);
        } else {
            // 搜索目录
            Files.walkFileTree(searchPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // 检查 glob 过滤
                    if (params.glob != null) {
                        String fileName = file.getFileName().toString();
                        if (!matchesGlob(fileName, params.glob)) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    
                    // 跳过大文件
                    if (attrs.size() > MAX_FILE_SIZE) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    try {
                        searchFile(file, pattern, params, result);
                    } catch (Exception e) {
                        log.warn("Failed to search file: {}", file, e);
                    }
                    
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        
        return result;
    }
    
    /**
     * 搜索单个文件
     */
    private void searchFile(Path file, Pattern pattern, Params params, SearchResult result) throws IOException {
        List<String> lines = Files.readAllLines(file);
        boolean fileMatched = false;
        int matchCount = 0;
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = pattern.matcher(line);
            
            if (matcher.find()) {
                fileMatched = true;
                matchCount++;
                
                if ("content".equals(params.outputMode)) {
                    String prefix = params.lineNumber ? String.format("%d:", i + 1) : "";
                    result.contentLines.add(String.format("%s:%s%s", file, prefix, line));
                }
            }
        }
        
        if (fileMatched) {
            result.filesWithMatches.add(file.toString());
            result.matchCounts.add(String.format("%s:%d", file, matchCount));
        }
    }
    
    /**
     * 简单的 Glob 匹配
     */
    private boolean matchesGlob(String fileName, String glob) {
        // 简化实现：只支持 *.ext 格式
        if (glob.startsWith("*.")) {
            String ext = glob.substring(1);
            return fileName.endsWith(ext);
        }
        return fileName.equals(glob);
    }
    
    /**
     * 格式化结果
     */
    private ToolResult formatResult(SearchResult result, Params params) {
        ToolResultBuilder builder = new ToolResultBuilder();
        List<String> output = new ArrayList<>();
        
        switch (params.outputMode) {
            case "content":
                output = result.contentLines;
                break;
            case "files_with_matches":
                output = result.filesWithMatches;
                break;
            case "count_matches":
                output = result.matchCounts;
                break;
            default:
                return ToolResult.error("Invalid output mode: " + params.outputMode, "Invalid mode");
        }
        
        // 应用 headLimit
        if (params.headLimit != null && output.size() > params.headLimit) {
            output = output.subList(0, params.headLimit);
            builder.write(String.join("\n", output));
            builder.write(String.format("\n... (results truncated to %d lines)", params.headLimit));
        } else {
            builder.write(String.join("\n", output));
        }
        
        if (output.isEmpty()) {
            return builder.ok("No matches found");
        }
        
        return builder.ok("");
    }
    
    /**
     * 搜索结果
     */
    private static class SearchResult {
        List<String> contentLines = new ArrayList<>();
        List<String> filesWithMatches = new ArrayList<>();
        List<String> matchCounts = new ArrayList<>();
    }
}
