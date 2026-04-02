package io.leavesfly.jimi.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 记忆搜索器（Layer 3）
 * <p>
 * 对标 Claude Code 的 Layer 3 会话记录层：
 * 直接复用 SessionManager 的 .jsonl 会话文件，通过 grep 搜索历史会话内容。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>只读操作，不修改原始 .jsonl 文件</li>
 *   <li>与 SessionManager 松耦合，仅通过文件路径交互</li>
 *   <li>支持关键词搜索和正则表达式搜索</li>
 *   <li>返回匹配的上下文片段（包含前后文）</li>
 * </ul>
 */
@Slf4j
public class MemorySearcher {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int CONTEXT_SNIPPET_MAX_LENGTH = 300;

    /**
     * 搜索指定工作目录下所有会话的历史记录
     *
     * @param sessionsDir 会话存储目录（~/.jimi/sessions/{dirHash}/）
     * @param query       搜索关键词
     * @param maxResults  最大返回结果数
     * @return 搜索结果列表
     */
    public List<SearchResult> search(Path sessionsDir, String query, int maxResults) {
        if (query == null || query.isBlank() || sessionsDir == null || !Files.isDirectory(sessionsDir)) {
            return List.of();
        }

        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        List<SearchResult> results = new ArrayList<>();
        String queryLower = query.toLowerCase();

        try (Stream<Path> files = Files.list(sessionsDir)) {
            List<Path> jsonlFiles = files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted((a, b) -> {
                        // 按文件修改时间降序（最近的会话优先）
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .toList();

            for (Path jsonlFile : jsonlFiles) {
                if (results.size() >= limit) {
                    break;
                }
                searchInFile(jsonlFile, queryLower, limit - results.size(), results);
            }
        } catch (IOException e) {
            log.warn("Failed to list session files in: {}", sessionsDir, e);
        }

        return results;
    }

    /**
     * 搜索所有工作目录的会话历史记录
     * <p>
     * 扫描 ~/.jimi/sessions/ 下的所有子目录，搜索所有会话文件。
     *
     * @param query      搜索关键词
     * @param maxResults 最大返回结果数
     * @return 搜索结果列表
     */
    public List<SearchResult> searchAll(String query, int maxResults) {
        Path sessionsRoot = Paths.get(System.getProperty("user.home"), ".jimi", "sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }

        List<Path> allSessionDirs = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(sessionsRoot)) {
            dirs.filter(Files::isDirectory).forEach(allSessionDirs::add);
        } catch (IOException e) {
            log.warn("Failed to list sessions root: {}", sessionsRoot, e);
            return List.of();
        }

        if (allSessionDirs.isEmpty()) {
            return List.of();
        }

        return searchInDirs(allSessionDirs, query, maxResults);
    }

    /**
     * 在多个会话目录中搜索关键词
     */
    private List<SearchResult> searchInDirs(Collection<Path> sessionsDirs, String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        List<SearchResult> results = new ArrayList<>();
        String queryLower = query.toLowerCase();

        List<Path> allJsonlFiles = new ArrayList<>();
        for (Path sessionsDir : sessionsDirs) {
            if (!Files.isDirectory(sessionsDir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(sessionsDir)) {
                files.filter(p -> p.toString().endsWith(".jsonl")).forEach(allJsonlFiles::add);
            } catch (IOException e) {
                log.warn("Failed to list session files in: {}", sessionsDir, e);
            }
        }

        allJsonlFiles.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });

        for (Path jsonlFile : allJsonlFiles) {
            if (results.size() >= limit) {
                break;
            }
            searchInFile(jsonlFile, queryLower, limit - results.size(), results);
        }

        return results;
    }

    /**
     * 使用正则表达式搜索会话历史
     *
     * @param sessionsDir 会话存储目录
     * @param regex       正则表达式
     * @param maxResults  最大返回结果数
     * @return 搜索结果列表
     */
    public List<SearchResult> searchRegex(Path sessionsDir, String regex, int maxResults) {
        if (regex == null || regex.isBlank() || sessionsDir == null || !Files.isDirectory(sessionsDir)) {
            return List.of();
        }

        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            log.warn("Invalid regex pattern: {}", regex, e);
            return List.of();
        }

        List<SearchResult> results = new ArrayList<>();

        try (Stream<Path> files = Files.list(sessionsDir)) {
            List<Path> jsonlFiles = files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e2) {
                            return 0;
                        }
                    })
                    .toList();

            for (Path jsonlFile : jsonlFiles) {
                if (results.size() >= limit) {
                    break;
                }
                searchInFileRegex(jsonlFile, pattern, limit - results.size(), results);
            }
        } catch (IOException e) {
            log.warn("Failed to list session files in: {}", sessionsDir, e);
        }

        return results;
    }

    /**
     * 在单个 .jsonl 文件中搜索关键词
     */
    private void searchInFile(Path jsonlFile, String queryLower, int remaining, List<SearchResult> results) {
        String sessionId = extractSessionId(jsonlFile);
        int initialSize = results.size();

        try (BufferedReader reader = Files.newBufferedReader(jsonlFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (results.size() - initialSize >= remaining) {
                    break;
                }

                String textContent = extractTextContent(line);
                if (textContent != null && textContent.toLowerCase().contains(queryLower)) {
                    String role = extractRole(line);
                    String snippet = createSnippet(textContent, queryLower);
                    results.add(new SearchResult(sessionId, lineNumber, role, snippet));
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read session file: {}", jsonlFile, e);
        }
    }

    /**
     * 在单个 .jsonl 文件中使用正则搜索
     */
    private void searchInFileRegex(Path jsonlFile, Pattern pattern, int remaining, List<SearchResult> results) {
        String sessionId = extractSessionId(jsonlFile);
        int initialSize = results.size();

        try (BufferedReader reader = Files.newBufferedReader(jsonlFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (results.size() - initialSize >= remaining) {
                    break;
                }

                String textContent = extractTextContent(line);
                if (textContent != null && pattern.matcher(textContent).find()) {
                    String role = extractRole(line);
                    String snippet = createSnippet(textContent, pattern.pattern());
                    results.add(new SearchResult(sessionId, lineNumber, role, snippet));
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read session file: {}", jsonlFile, e);
        }
    }

    /**
     * 从 JSONL 行中提取文本内容
     * <p>
     * .jsonl 格式为每行一个 JSON 对象，包含 role 和 content 字段。
     * content 可能是字符串或包含 text 字段的数组。
     */
    private String extractTextContent(String jsonLine) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(jsonLine);

            // 尝试从 content 字段提取文本
            JsonNode contentNode = node.get("content");
            if (contentNode == null) {
                return null;
            }

            if (contentNode.isTextual()) {
                return contentNode.asText();
            }

            if (contentNode.isArray()) {
                StringBuilder textBuilder = new StringBuilder();
                for (JsonNode part : contentNode) {
                    JsonNode textNode = part.get("text");
                    if (textNode != null && textNode.isTextual()) {
                        if (!textBuilder.isEmpty()) {
                            textBuilder.append(" ");
                        }
                        textBuilder.append(textNode.asText());
                    }
                }
                return textBuilder.isEmpty() ? null : textBuilder.toString();
            }

            return null;
        } catch (Exception e) {
            // 非 JSON 行，跳过
            return null;
        }
    }

    /**
     * 从 JSONL 行中提取角色
     */
    private String extractRole(String jsonLine) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(jsonLine);
            JsonNode roleNode = node.get("role");
            return roleNode != null ? roleNode.asText() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 创建搜索结果的上下文片段
     */
    private String createSnippet(String text, String query) {
        if (text.length() <= CONTEXT_SNIPPET_MAX_LENGTH) {
            return text;
        }

        int queryIndex = text.toLowerCase().indexOf(query.toLowerCase());
        if (queryIndex == -1) {
            return text.substring(0, CONTEXT_SNIPPET_MAX_LENGTH) + "...";
        }

        int snippetStart = Math.max(0, queryIndex - 50);
        int snippetEnd = Math.min(text.length(), queryIndex + query.length() + 200);

        String snippet = text.substring(snippetStart, snippetEnd);
        if (snippetStart > 0) {
            snippet = "..." + snippet;
        }
        if (snippetEnd < text.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    /**
     * 从文件路径中提取 session ID
     */
    private String extractSessionId(Path jsonlFile) {
        String fileName = jsonlFile.getFileName().toString();
        if (fileName.endsWith(".jsonl")) {
            return fileName.substring(0, fileName.length() - ".jsonl".length());
        }
        return fileName;
    }

    /**
     * 搜索结果
     */
    public record SearchResult(
            String sessionId,
            int lineNumber,
            String role,
            String snippet
    ) {
        /**
         * 格式化为可读字符串
         */
        public String format() {
            String roleLabel = switch (role) {
                case "user" -> "[User]";
                case "assistant" -> "[Assistant]";
                case "system" -> "[System]";
                default -> "[" + role + "]";
            };
            return String.format("Session: %s (line %d) %s\n  %s",
                    sessionId.substring(0, Math.min(8, sessionId.length())),
                    lineNumber, roleLabel, snippet);
        }
    }
}
