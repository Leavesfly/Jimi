package io.leavesfly.jimi.adk.tools.extended;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.tools.base.AbstractTool;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grep 工具 - 使用正则表达式搜索文件内容
 *
 * @author Jimi2 Team
 */
@Slf4j
public class GrepTool extends AbstractTool<GrepTool.Params> {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int BINARY_CHECK_SIZE = 8192;
    private static final List<String> BINARY_EXTENSIONS = Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".ico", ".svg",
            ".pdf", ".zip", ".tar", ".gz", ".7z", ".rar",
            ".exe", ".dll", ".so", ".dylib",
            ".class", ".jar", ".war",
            ".mp3", ".mp4", ".avi", ".mov",
            ".DS_Store"
    );

    private final Runtime runtime;

    public GrepTool(Runtime runtime) {
        super("grep",
                "Search file contents using regex patterns",
                Params.class);
        this.runtime = runtime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("Regex pattern to search for")
        private String pattern;

        @JsonPropertyDescription("Path to search (file or directory). Default: '.' (current dir)")
        @Builder.Default
        private String path = ".";

        @JsonPropertyDescription("Glob pattern to filter files (e.g., *.java)")
        private String glob;

        @JsonPropertyDescription("Output mode: 'content' (show matching lines), 'files_with_matches' (show files), 'count_matches' (show counts). Default: 'files_with_matches'")
        @Builder.Default
        private String outputMode = "files_with_matches";

        @JsonPropertyDescription("Show line numbers in content mode")
        @Builder.Default
        private boolean lineNumber = false;

        @JsonPropertyDescription("Case-insensitive matching")
        @Builder.Default
        private boolean ignoreCase = false;

        @JsonPropertyDescription("Limit output lines")
        private Integer headLimit;
    }

    @Override
    public Mono<ToolResult> doExecute(Params params) {
        return Mono.defer(() -> {
            try {
                if (params.pattern == null || params.pattern.trim().isEmpty()) {
                    return Mono.just(ToolResult.error("Pattern is required"));
                }

                int flags = params.ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
                Pattern pattern;
                try {
                    pattern = Pattern.compile(params.pattern, flags);
                } catch (PatternSyntaxException e) {
                    return Mono.just(ToolResult.error("Invalid regex pattern: " + e.getMessage()));
                }

                Path searchPath = ".".equals(params.path) ? runtime.getConfig().getWorkDir() : Path.of(params.path);
                if (!searchPath.isAbsolute()) {
                    searchPath = runtime.getConfig().getWorkDir().resolve(searchPath);
                }

                if (!Files.exists(searchPath)) {
                    return Mono.just(ToolResult.error("Path does not exist: " + params.path));
                }

                SearchResult result = performSearch(searchPath, pattern, params);
                return Mono.just(formatResult(result, params));

            } catch (Exception e) {
                log.error("Failed to grep: {}", params.pattern, e);
                return Mono.just(ToolResult.error("Failed to grep: " + e.getMessage()));
            }
        });
    }

    private SearchResult performSearch(Path searchPath, Pattern pattern, Params params) throws Exception {
        SearchResult result = new SearchResult();

        if (Files.isRegularFile(searchPath)) {
            searchFile(searchPath, pattern, params, result);
        } else {
            Files.walkFileTree(searchPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();

                    if (fileName.startsWith(".")) return FileVisitResult.CONTINUE;

                    if (params.glob != null && !matchesGlob(fileName, params.glob)) {
                        return FileVisitResult.CONTINUE;
                    }

                    if (attrs.size() > MAX_FILE_SIZE) return FileVisitResult.CONTINUE;
                    if (isBinaryFileByExtension(fileName)) return FileVisitResult.CONTINUE;

                    try {
                        searchFile(file, pattern, params, result);
                    } catch (Exception e) {
                        log.debug("Skipped file (likely binary): {}", file);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
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

    private void searchFile(Path file, Pattern pattern, Params params, SearchResult result) throws Exception {
        if (isBinaryFile(file)) return;

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
            log.debug("Skipped binary file: {}", file);
            return;
        }

        if (fileMatched) {
            result.filesWithMatches.add(file.toString());
            result.matchCounts.add(String.format("%s:%d", file, matchCount));
        }
    }

    private boolean matchesGlob(String fileName, String glob) {
        if (glob.startsWith("*.")) {
            return fileName.endsWith(glob.substring(1));
        }
        return fileName.equals(glob);
    }

    private boolean isBinaryFileByExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return BINARY_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private boolean isBinaryFile(Path file) throws Exception {
        byte[] bytes = new byte[BINARY_CHECK_SIZE];
        int bytesRead;

        try (var is = Files.newInputStream(file)) {
            bytesRead = is.read(bytes);
        }

        if (bytesRead <= 0) return false;

        int nonAsciiCount = 0;
        for (int i = 0; i < bytesRead; i++) {
            byte b = bytes[i];
            if (b == 0) return true;
            if (b < 0x09 || (b > 0x0D && b < 0x20) || b == 0x7F) {
                nonAsciiCount++;
            }
        }

        return (double) nonAsciiCount / bytesRead > 0.3;
    }

    private ToolResult formatResult(SearchResult result, Params params) {
        List<String> output;

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
                return ToolResult.error("Invalid output mode: " + params.outputMode);
        }

        if (params.headLimit != null && output.size() > params.headLimit) {
            output = output.subList(0, params.headLimit);
            String text = String.join("\n", output) +
                    String.format("\n... (results truncated to %d lines)", params.headLimit);
            return ToolResult.success(text);
        }

        if (output.isEmpty()) {
            return ToolResult.success("No matches found");
        }

        return ToolResult.success(String.join("\n", output));
    }

    private static class SearchResult {
        List<String> contentLines = new ArrayList<>();
        List<String> filesWithMatches = new ArrayList<>();
        List<String> matchCounts = new ArrayList<>();
    }
}
