package io.leavesfly.jimi.retrieval;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * 简单代码分块器实现
 * <p>
 * 策略：固定行数窗口分块（通用，适合所有语言）
 * 后续可扩展为语言感知的AST级分块
 */
@Slf4j
public class SimpleChunker implements Chunker {

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = new HashMap<>();

    static {
        EXTENSION_TO_LANGUAGE.put(".java", "java");
        EXTENSION_TO_LANGUAGE.put(".kt", "kotlin");
        EXTENSION_TO_LANGUAGE.put(".py", "python");
        EXTENSION_TO_LANGUAGE.put(".js", "javascript");
        EXTENSION_TO_LANGUAGE.put(".ts", "typescript");
        EXTENSION_TO_LANGUAGE.put(".go", "go");
        EXTENSION_TO_LANGUAGE.put(".rs", "rust");
        EXTENSION_TO_LANGUAGE.put(".cpp", "cpp");
        EXTENSION_TO_LANGUAGE.put(".c", "c");
        EXTENSION_TO_LANGUAGE.put(".h", "c");
        EXTENSION_TO_LANGUAGE.put(".hpp", "cpp");
    }

    @Override
    public Flux<CodeChunk> chunk(String filePath, String content, int chunkSize, int overlap) {
        return Flux.defer(() -> {
            try {
                List<CodeChunk> chunks = new ArrayList<>();
                String[] lines = content.split("\n");
                String language = detectLanguage(filePath);

                int totalLines = lines.length;
                int startLine = 0;

                while (startLine < totalLines) {
                    int endLine = Math.min(startLine + chunkSize, totalLines);
                    
                    // 构建片段内容
                    StringBuilder chunkContent = new StringBuilder();
                    for (int i = startLine; i < endLine; i++) {
                        chunkContent.append(lines[i]);
                        if (i < endLine - 1) {
                            chunkContent.append("\n");
                        }
                    }

                    String chunkText = chunkContent.toString();
                    
                    // 跳过空白片段
                    if (chunkText.trim().isEmpty()) {
                        startLine = endLine;
                        continue;
                    }

                    // 生成唯一ID
                    String chunkId = generateChunkId(filePath, startLine, endLine);

                    // 计算内容哈希
                    String contentHash = calculateMD5(chunkText);

                    // 构建CodeChunk
                    CodeChunk chunk = CodeChunk.builder()
                            .id(chunkId)
                            .content(chunkText)
                            .filePath(filePath)
                            .startLine(startLine + 1) // 行号从1开始
                            .endLine(endLine)
                            .language(language)
                            .contentHash(contentHash)
                            .updatedAt(System.currentTimeMillis())
                            .metadata(new HashMap<>())
                            .build();

                    chunks.add(chunk);

                    // 移动到下一个窗口（考虑重叠）
                    startLine = endLine - overlap;
                    if (startLine >= totalLines - overlap) {
                        break; // 避免最后的小片段
                    }
                }

                return Flux.fromIterable(chunks);
            } catch (Exception e) {
                log.error("Failed to chunk file: {}", filePath, e);
                return Flux.error(e);
            }
        });
    }

    @Override
    public Flux<CodeChunk> chunkFiles(List<Path> files, int chunkSize, int overlap) {
        return Flux.fromIterable(files)
                .flatMap(file -> {
                    try {
                        String content = Files.readString(file);
                        String relativePath = file.toString();
                        return chunk(relativePath, content, chunkSize, overlap);
                    } catch (IOException e) {
                        log.warn("Failed to read file: {}", file, e);
                        return Flux.empty();
                    }
                });
    }

    @Override
    public List<String> getSupportedLanguages() {
        return new ArrayList<>(new HashSet<>(EXTENSION_TO_LANGUAGE.values()));
    }

    @Override
    public String detectLanguage(String filePath) {
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filePath.length() - 1) {
            return "unknown";
        }

        String extension = filePath.substring(dotIndex).toLowerCase();
        return EXTENSION_TO_LANGUAGE.getOrDefault(extension, "unknown");
    }

    /**
     * 生成片段唯一ID
     */
    private String generateChunkId(String filePath, int startLine, int endLine) {
        return String.format("%s_%d_%d", 
                filePath.replace("/", "_").replace("\\", "_"),
                startLine,
                endLine);
    }

    /**
     * 计算MD5哈希
     */
    private String calculateMD5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to calculate MD5", e);
            return "";
        }
    }
}
