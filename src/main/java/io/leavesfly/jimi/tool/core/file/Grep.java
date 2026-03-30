package io.leavesfly.jimi.tool.core.file;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.core.engine.context.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.tool.SyncTool;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grep 工具 - 使用正则表达式搜索文件内容
 * <p>
 * 继承 SyncTool 基类，简化实现，使用 Java 内置的文件遍历和正则表达式。
 * 只需实现 executeSync() 方法，无需关心 Reactor 的 Mono 包装。
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class Grep extends SyncTool<Grep.Params> {
    
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int BINARY_CHECK_SIZE = 8192; // 检查前8KB判断是否为二进制
    private static final List<String> BINARY_EXTENSIONS = Arrays.asList(
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".ico", ".svg",
        ".pdf", ".zip", ".tar", ".gz", ".7z", ".rar",
        ".exe", ".dll", ".so", ".dylib",
        ".class", ".jar", ".war",
        ".mp3", ".mp4", ".avi", ".mov",
        ".DS_Store"
    );
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
        @JsonPropertyDescription("用于搜索的正则表达式模式（支持 Java 正则语法）")
        private String pattern;
        
        /**
         * 搜索路径（文件或目录）
         */
        @JsonPropertyDescription("要搜索的文件或目录路径。可以是相对路径或绝对路径。默认为 '.' （当前目录）")
        @Builder.Default
        private String path = ".";
        
        /**
         * Glob 模式过滤文件
         */
        @JsonPropertyDescription("用于过滤文件名的 Glob 模式（例如：*.java）。默认为 null（不过滤）")
        @Builder.Default
        private String glob = null;
        
        /**
         * 输出模式：content, files_with_matches, count_matches
         */
        @JsonPropertyDescription("输出模式：'content'（显示匹配的行）、'files_with_matches'（显示包含匹配的文件）、'count_matches'（显示每个文件的匹配数）。默认为 'files_with_matches'")
        @Builder.Default
        private String outputMode = "files_with_matches";
        
        /**
         * 显示行号（仅 content 模式）
         */
        @JsonPropertyDescription("在 content 模式下是否显示行号。默认为 false")
        @Builder.Default
        private boolean lineNumber = false;
        
        /**
         * 忽略大小写
         */
        @JsonPropertyDescription("是否在匹配时忽略大小写。默认为 false")
        @Builder.Default
        private boolean ignoreCase = false;
        
        /**
         * 限制输出行数
         */
        @JsonPropertyDescription("限制输出的最大行数。默认为 null（不限制）")
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
    protected ToolResult executeSync(Params params) {
        try {
            // 验证参数
            if (params.pattern == null || params.pattern.trim().isEmpty()) {
                return ToolResult.error(
                    "Pattern is required. Please provide a valid regex pattern.",
                    "Missing pattern"
                );
            }
            
            // 编译正则表达式
            int flags = params.ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
            Pattern pattern;
            try {
                pattern = Pattern.compile(params.pattern, flags);
            } catch (PatternSyntaxException e) {
                return ToolResult.error(
                    String.format("Invalid regex pattern: %s", e.getMessage()),
                    "Invalid pattern"
                );
            }
            
            // 确定搜索路径
            Path searchPath = ".".equals(params.path) ? workDir : Path.of(params.path);
            
            if (!searchPath.isAbsolute()) {
                searchPath = workDir.resolve(searchPath);
            }
            
            if (!Files.exists(searchPath)) {
                return ToolResult.error(
                    String.format("Path does not exist: %s", params.path),
                    "Path not found"
                );
            }
            
            // 执行搜索
            SearchResult result = performSearch(searchPath, pattern, params);
            
            // 生成输出
            return formatResult(result, params);
            
        } catch (Exception e) {
            log.error("Failed to grep: {}", params.pattern, e);
            return ToolResult.error(
                String.format("Failed to grep. Error: %s", e.getMessage()),
                "Failed to grep"
            );
        }
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
                    String fileName = file.getFileName().toString();
                    
                    // 跳过隐藏文件
                    if (fileName.startsWith(".")) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    // 检查 glob 过滤
                    if (params.glob != null) {
                        if (!matchesGlob(fileName, params.glob)) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    
                    // 跳过大文件
                    if (attrs.size() > MAX_FILE_SIZE) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    // 跳过已知的二进制文件类型
                    if (isBinaryFileByExtension(fileName)) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    try {
                        searchFile(file, pattern, params, result);
                    } catch (Exception e) {
                        log.debug("Skipped file (likely binary): {}", file);
                    }
                    
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 跳过隐藏目录
                    String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (dirName.startsWith(".") && !dir.equals(searchPath)) {
                        return FileVisitResult.SKIP_SUBTREE;
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
        // 先检查是否为二进制文件
        if (isBinaryFile(file)) {
            return;
        }
        
        boolean fileMatched = false;
        int matchCount = 0;
        int lineNumber = 0;
        
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher matcher = pattern.matcher(line);
                
                if (matcher.find()) {
                    fileMatched = true;
                    matchCount++;
                    
                    if ("content".equals(params.outputMode)) {
                        String prefix = params.lineNumber ? String.format("%d:", lineNumber) : "";
                        result.contentLines.add(String.format("%s:%s%s", file, prefix, line));
                    }
                }
            }
        } catch (CharacterCodingException e) {
            // 遇到编码错误，认为是二进制文件，静默跳过
            log.debug("Skipped binary file: {}", file);
            return;
        }
        
        if (fileMatched) {
            result.filesWithMatches.add(file.toString());
            result.matchCounts.add(String.format("%s:%d", file, matchCount));
        }
    }
    
    /**
     * 使用 Java PathMatcher 进行 Glob 匹配
     */
    private boolean matchesGlob(String fileName, String glob) {
        try {
            PathMatcher matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + glob);
            return matcher.matches(Path.of(fileName));
        } catch (Exception e) {
            log.warn("Invalid glob pattern: {}", glob, e);
            return false;
        }
    }
    
    /**
     * 根据文件扩展名判断是否为二进制文件
     */
    private boolean isBinaryFileByExtension(String fileName) {
        String lowerFileName = fileName.toLowerCase();
        return BINARY_EXTENSIONS.stream()
            .anyMatch(lowerFileName::endsWith);
    }
    
    /**
     * 检测文件是否为二进制文件
     * 通过读取文件前几个字节判断是否包含非文本字符
     */
    private boolean isBinaryFile(Path file) throws IOException {
        byte[] bytes = new byte[BINARY_CHECK_SIZE];
        int bytesRead;
        
        try (var is = Files.newInputStream(file)) {
            bytesRead = is.read(bytes);
        }
        
        if (bytesRead <= 0) {
            return false;
        }
        
        // 检查是否包含 NUL 字符或大量非ASCII字符
        int nonAsciiCount = 0;
        for (int i = 0; i < bytesRead; i++) {
            byte b = bytes[i];
            if (b == 0) {
                // 包含 NUL 字符，很可能是二进制文件
                return true;
            }
            if (b < 0x09 || (b > 0x0D && b < 0x20) || b == 0x7F) {
                // 控制字符（除了 tab, LF, CR）
                nonAsciiCount++;
            }
        }
        
        // 如果超过30%是非文本字符，认为是二进制文件
        return (double) nonAsciiCount / bytesRead > 0.3;
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
