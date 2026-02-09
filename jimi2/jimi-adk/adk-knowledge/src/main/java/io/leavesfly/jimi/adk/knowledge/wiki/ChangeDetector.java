package io.leavesfly.jimi.adk.knowledge.wiki;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/**
 * 智能变更检测器
 * <p>
 * 职责：
 * - 基于内容哈希检测文件变更
 * - 分析变更影响范围
 * - 评估变更重要性
 * - 集成 Git diff 分析
 */
@Slf4j
public class ChangeDetector {
    
    private static final String HASH_CACHE_FILE = ".wiki-hash-cache";
    private static final int MIN_CHANGE_LINES = 5; // 最小变更行数阈值
    
    /**
     * 检测代码变更
     *
     * @param wikiPath Wiki 目录路径
     * @return 变更文件列表
     */
    public List<FileChange> detectChanges(Path wikiPath) {
        List<FileChange> changes = new ArrayList<>();
        
        try {
            // 获取项目根目录
            Path workDir = wikiPath.getParent().getParent();
            Path srcPath = workDir.resolve("src");
            
            if (!Files.exists(srcPath)) {
                log.warn("Source directory not found: {}", srcPath);
                return changes;
            }
            
            // 加载哈希缓存
            Map<String, String> hashCache = loadHashCache(wikiPath);
            
            // 扫描源代码文件
            try (Stream<Path> paths = Files.walk(srcPath)) {
                paths.filter(Files::isRegularFile)
                     .filter(this::isSourceFile)
                     .forEach(file -> {
                         try {
                             FileChange change = detectFileChange(workDir, file, hashCache);
                             if (change != null) {
                                 changes.add(change);
                             }
                         } catch (Exception e) {
                             log.error("Failed to detect change for file: {}", file, e);
                         }
                     });
            }
            
            // 保存更新后的哈希缓存
            saveHashCache(wikiPath, hashCache);
            
        } catch (Exception e) {
            log.error("Failed to detect changes", e);
        }
        
        return changes;
    }
    
    /**
     * 检测单个文件的变更
     */
    private FileChange detectFileChange(Path workDir, Path file, Map<String, String> hashCache) 
            throws IOException {
        
        String relativePath = workDir.relativize(file).toString();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String currentHash = calculateHash(content);
        
        String oldHash = hashCache.get(relativePath);
        
        // 判断变更类型
        FileChange.ChangeType changeType;
        if (oldHash == null) {
            changeType = FileChange.ChangeType.ADDED;
        } else if (!currentHash.equals(oldHash)) {
            changeType = FileChange.ChangeType.MODIFIED;
        } else {
            // 未变更，更新缓存后返回
            hashCache.put(relativePath, currentHash);
            return null;
        }
        
        // 计算变更行数（简化版本，可后续集成 Git diff）
        int changedLines = estimateChangedLines(oldHash, currentHash, content);
        
        // 评估变更重要性
        FileChange.ChangeImportance importance = assessImportance(relativePath, content, changedLines);
        
        // 更新缓存
        hashCache.put(relativePath, currentHash);
        
        return FileChange.builder()
                .filePath(relativePath)
                .absolutePath(file)
                .changeType(changeType)
                .oldHash(oldHash)
                .newHash(currentHash)
                .changedLines(changedLines)
                .language(detectLanguage(relativePath))
                .importance(importance)
                .build();
    }
    
    /**
     * 评估变更重要性
     */
    private FileChange.ChangeImportance assessImportance(String filePath, String content, int changedLines) {
        // 1. 微小变更：行数太少
        if (changedLines < MIN_CHANGE_LINES) {
            return FileChange.ChangeImportance.TRIVIAL;
        }
        
        // 2. 关键变更：核心架构文件
        if (isCriticalFile(filePath)) {
            return FileChange.ChangeImportance.CRITICAL;
        }
        
        // 3. 重要变更：接口或公共 API
        if (isApiFile(filePath, content)) {
            return FileChange.ChangeImportance.MAJOR;
        }
        
        // 4. 一般变更：普通实现文件
        return FileChange.ChangeImportance.MINOR;
    }
    
    /**
     * 判断是否为关键文件
     */
    private boolean isCriticalFile(String filePath) {
        return filePath.contains("/engine/") ||
               filePath.contains("/agent/") ||
               filePath.contains("/llm/") ||
               filePath.endsWith("Configuration.java") ||
               filePath.endsWith("Factory.java") ||
               filePath.endsWith("Registry.java");
    }
    
    /**
     * 判断是否为 API 文件
     */
    private boolean isApiFile(String filePath, String content) {
        // 接口文件
        if (filePath.endsWith("Handler.java") || 
            filePath.endsWith("Provider.java") ||
            content.contains("public interface ")) {
            return true;
        }
        
        // 包含公共 API 方法
        return content.contains("public class") && 
               content.contains("public ") && content.contains("(");
    }
    
    /**
     * 估算变更行数
     */
    private int estimateChangedLines(String oldHash, String newHash, String content) {
        if (oldHash == null) {
            // 新增文件，返回总行数
            return (int) content.lines().count();
        }
        
        // 简化版本：假设哈希不同意味着至少有一定行数变更
        // 实际可通过 Git diff 获取精确行数
        int totalLines = (int) content.lines().count();
        return Math.max(MIN_CHANGE_LINES, totalLines / 10); // 粗略估计 10% 变更
    }
    
    /**
     * 计算文件内容哈希
     */
    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to calculate hash", e);
            return UUID.randomUUID().toString();
        }
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * 判断是否为源代码文件
     */
    private boolean isSourceFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") ||
               name.endsWith(".xml") ||
               name.endsWith(".yml") ||
               name.endsWith(".yaml") ||
               name.endsWith(".properties") ||
               name.endsWith(".md");
    }
    
    /**
     * 检测文件语言
     */
    private String detectLanguage(String filePath) {
        if (filePath.endsWith(".java")) return "java";
        if (filePath.endsWith(".xml")) return "xml";
        if (filePath.endsWith(".yml") || filePath.endsWith(".yaml")) return "yaml";
        if (filePath.endsWith(".properties")) return "properties";
        if (filePath.endsWith(".md")) return "markdown";
        return "unknown";
    }
    
    /**
     * 加载哈希缓存
     */
    private Map<String, String> loadHashCache(Path wikiPath) {
        Map<String, String> cache = new HashMap<>();
        Path cacheFile = wikiPath.resolve(HASH_CACHE_FILE);
        
        if (!Files.exists(cacheFile)) {
            return cache;
        }
        
        try {
            List<String> lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] parts = line.split("\\|", 2);
                if (parts.length == 2) {
                    cache.put(parts[0], parts[1]);
                }
            }
            log.debug("Loaded {} hash entries from cache", cache.size());
        } catch (IOException e) {
            log.warn("Failed to load hash cache", e);
        }
        
        return cache;
    }
    
    /**
     * 保存哈希缓存
     */
    private void saveHashCache(Path wikiPath, Map<String, String> cache) {
        Path cacheFile = wikiPath.resolve(HASH_CACHE_FILE);
        
        try {
            List<String> lines = new ArrayList<>();
            cache.forEach((path, hash) -> lines.add(path + "|" + hash));
            Files.write(cacheFile, lines, StandardCharsets.UTF_8);
            log.debug("Saved {} hash entries to cache", cache.size());
        } catch (IOException e) {
            log.warn("Failed to save hash cache", e);
        }
    }
    
    /**
     * 使用 Git diff 分析变更（可选，需要 Git 环境）
     */
    public Optional<Integer> getExactChangedLines(Path file) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--numstat", "HEAD", file.toString());
            pb.directory(file.getParent().toFile());
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        int added = Integer.parseInt(parts[0]);
                        int deleted = Integer.parseInt(parts[1]);
                        return Optional.of(added + deleted);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Git diff not available: {}", e.getMessage());
        }
        
        return Optional.empty();
    }
}
