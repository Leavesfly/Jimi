package io.leavesfly.jimi.ui.shell;

import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.command.CommandRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Jimi 智能命令补全器
 * 提供以下功能：
 * 1. 元命令补全（从 CommandRegistry 动态获取）
 * 2. 命令别名补全
 * 3. 文件路径补全（@file 引用）
 * 4. 常用短语补全
 * 5. 上下文感知补全
 */
@Slf4j
public class JimiCompleter implements Completer {
    
    private final CommandRegistry commandRegistry;
    private final Path workingDir;
    
    // 常用短语
    private static final String[] COMMON_PHRASES = {
        "help me",
        "show me",
        "explain",
        "what is",
        "how to",
        "please",
        "analyze",
        "fix",
        "refactor",
        "implement",
        "create",
        "update",
        "delete",
        "find",
        "search"
    };
    
    // 忽略的目录（用于文件补全）
    private static final Set<String> IGNORED_DIRS = Set.of(
        ".git", ".svn", ".hg", ".bzr",
        "node_modules", ".gradle", ".maven",
        "target", "build", "out", "bin",
        ".idea", ".vscode", ".eclipse",
        "__pycache__", ".pytest_cache",
        ".DS_Store", "Thumbs.db"
    );
    
    public JimiCompleter(CommandRegistry commandRegistry, Path workingDir) {
        this.commandRegistry = commandRegistry;
        this.workingDir = workingDir != null ? workingDir : Paths.get(System.getProperty("user.dir"));
        log.debug("JimiCompleter initialized with working directory: {}", this.workingDir);
    }
    
    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        String fullLine = line.line();
        
        log.debug("Completion triggered - word: '{}', line: '{}', wordIndex: {}", word, fullLine, line.wordIndex());
        
        try {
            // 1. 元命令补全（以 / 开头）
            if (word.startsWith("/")) {
                log.debug("Meta command completion for: {}", word);
                completeMetaCommands(word, candidates);
                log.debug("Found {} meta command candidates", candidates.size());
                return;
            }
            
            // 2. 文件路径补全（以 @ 开头）
            if (word.startsWith("@")) {
                log.debug("File path completion for: {}", word);
                completeFilePaths(word, candidates);
                log.debug("Found {} file path candidates", candidates.size());
                return;
            }
            
            // 3. 常用短语补全（仅在行首或空白后）
            if (shouldCompletePhrase(line, word)) {
                log.debug("Common phrase completion for: {}", word);
                completeCommonPhrases(word, candidates);
            }
            
            // 4. 文件扩展名建议（在合适的上下文中）
            if (shouldSuggestFiles(fullLine, word)) {
                log.debug("File type suggestion for: {}", word);
                suggestFileTypes(word, candidates);
            }
            
            log.debug("Total candidates: {}", candidates.size());
            
        } catch (Exception e) {
            log.error("Error during completion", e);
        }
    }
    
    /**
     * 补全元命令
     */
    private void completeMetaCommands(String word, List<Candidate> candidates) {
        String prefix = word.substring(1).toLowerCase(); // 去掉 /
        
        // 从 CommandRegistry 获取所有命令
        for (CommandHandler handler : commandRegistry.getAllHandlers()) {
            String name = handler.getName();
            String slashName = "/" + name;
            
            // 主命令名匹配
            if (name.toLowerCase().startsWith(prefix)) {
                String aliasInfo = handler.getAliases().isEmpty() 
                    ? "" 
                    : " (" + String.join(", ", handler.getAliases()) + ")";
                
                candidates.add(new Candidate(
                    slashName,
                    slashName,
                    null,
                    handler.getDescription() + aliasInfo,
                    null,
                    null,
                    true
                ));
            }
            
            // 别名匹配
            for (String alias : handler.getAliases()) {
                if (alias.toLowerCase().startsWith(prefix)) {
                    candidates.add(new Candidate(
                        slashName,  // 插入主命令名
                        "/" + alias,  // 显示别名
                        null,
                        handler.getDescription() + " (alias for /" + name + ")",
                        null,
                        null,
                        true
                    ));
                }
            }
        }
    }
    
    /**
     * 补全文件路径
     */
    private void completeFilePaths(String word, List<Candidate> candidates) {
        String pathFragment = word.substring(1); // 去掉 @
        Path basePath = workingDir;
        
        // 如果包含路径分隔符，计算基础路径
        if (pathFragment.contains(File.separator)) {
            int lastSep = pathFragment.lastIndexOf(File.separator);
            String dirPath = pathFragment.substring(0, lastSep);
            pathFragment = pathFragment.substring(lastSep + 1);
            basePath = workingDir.resolve(dirPath);
        }
        
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            return;
        }
        
        final String finalFragment = pathFragment.toLowerCase();
        
        try (Stream<Path> paths = Files.list(basePath)) {
            paths
                .filter(p -> !shouldIgnore(p))
                .filter(p -> p.getFileName().toString().toLowerCase().startsWith(finalFragment))
                .limit(50)  // 限制结果数量
                .sorted((a, b) -> {
                    // 目录优先，然后按名称排序
                    boolean aIsDir = Files.isDirectory(a);
                    boolean bIsDir = Files.isDirectory(b);
                    if (aIsDir != bIsDir) {
                        return aIsDir ? -1 : 1;
                    }
                    return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                })
                .forEach(p -> {
                    String fileName = p.getFileName().toString();
                    boolean isDir = Files.isDirectory(p);
                    String display = isDir ? fileName + "/" : fileName;
                    String relativePath = workingDir.relativize(p).toString();
                    
                    candidates.add(new Candidate(
                        "@" + relativePath + (isDir ? "/" : ""),
                        display,
                        null,
                        isDir ? "directory" : getFileType(fileName),
                        null,
                        null,
                        !isDir  // 文件补全后添加空格
                    ));
                });
        } catch (IOException e) {
            log.debug("Error listing directory: {}", basePath, e);
        }
    }
    
    /**
     * 补全常用短语
     */
    private void completeCommonPhrases(String word, List<Candidate> candidates) {
        String lowerWord = word.toLowerCase();
        
        for (String phrase : COMMON_PHRASES) {
            if (phrase.startsWith(lowerWord)) {
                candidates.add(new Candidate(
                    phrase,
                    phrase,
                    null,
                    "common phrase",
                    null,
                    null,
                    false
                ));
            }
        }
    }
    
    /**
     * 建议文件类型
     */
    private void suggestFileTypes(String word, List<Candidate> candidates) {
        String lowerWord = word.toLowerCase();
        
        Map<String, String> fileTypes = Map.of(
            ".java", "Java source file",
            ".py", "Python source file",
            ".js", "JavaScript file",
            ".ts", "TypeScript file",
            ".md", "Markdown file",
            ".yaml", "YAML configuration",
            ".json", "JSON file",
            ".xml", "XML file"
        );
        
        for (Map.Entry<String, String> entry : fileTypes.entrySet()) {
            if (entry.getKey().startsWith(lowerWord)) {
                candidates.add(new Candidate(
                    entry.getKey(),
                    entry.getKey(),
                    null,
                    entry.getValue(),
                    null,
                    null,
                    false
                ));
            }
        }
    }
    
    /**
     * 判断是否应该补全短语
     */
    private boolean shouldCompletePhrase(ParsedLine line, String word) {
        // 在行首或者前一个 token 是空白
        return line.wordIndex() == 0 || 
               (line.wordIndex() > 0 && line.line().trim().split("\\s+").length <= 2);
    }
    
    /**
     * 判断是否应该建议文件
     */
    private boolean shouldSuggestFiles(String fullLine, String word) {
        // 如果输入包含文件相关的关键词
        String lowerLine = fullLine.toLowerCase();
        return lowerLine.contains("file") || 
               lowerLine.contains("read") || 
               lowerLine.contains("write") ||
               lowerLine.contains("open") ||
               word.startsWith(".");
    }
    
    /**
     * 判断路径是否应该被忽略
     */
    private boolean shouldIgnore(Path path) {
        String fileName = path.getFileName().toString();
        
        // 隐藏文件（除非明确搜索）
        if (fileName.startsWith(".") && !fileName.equals(".") && !fileName.equals("..")) {
            return IGNORED_DIRS.contains(fileName);
        }
        
        // 忽略的目录
        if (Files.isDirectory(path)) {
            return IGNORED_DIRS.contains(fileName);
        }
        
        return false;
    }
    
    /**
     * 获取文件类型描述
     */
    private String getFileType(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            String ext = fileName.substring(lastDot);
            return switch (ext.toLowerCase()) {
                case ".java" -> "Java source";
                case ".py" -> "Python source";
                case ".js" -> "JavaScript";
                case ".ts" -> "TypeScript";
                case ".md" -> "Markdown";
                case ".yaml", ".yml" -> "YAML config";
                case ".json" -> "JSON";
                case ".xml" -> "XML";
                case ".properties" -> "Properties";
                case ".sh" -> "Shell script";
                case ".bat" -> "Batch script";
                default -> ext + " file";
            };
        }
        return "file";
    }
}
