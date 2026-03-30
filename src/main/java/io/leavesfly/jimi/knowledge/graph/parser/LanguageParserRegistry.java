package io.leavesfly.jimi.knowledge.graph.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 语言解析器注册中心
 * <p>
 * 管理所有已注册的语言解析器，根据文件类型自动选择合适的解析器。
 * 支持动态注册/注销解析器，线程安全。
 */
@Slf4j
@Component
public class LanguageParserRegistry {
    
    /**
     * 按扩展名索引的解析器映射（一个扩展名可能对应多个解析器）
     */
    private final Map<String, List<LanguageParser>> parsersByExtension = new ConcurrentHashMap<>();
    
    /**
     * 所有已注册的解析器
     */
    private final Set<LanguageParser> registeredParsers = ConcurrentHashMap.newKeySet();
    
    /**
     * 构造函数，自动注入所有 LanguageParser 实现
     */
    public LanguageParserRegistry(List<LanguageParser> parsers) {
        if (parsers != null) {
            parsers.forEach(this::register);
        }
        log.info("LanguageParserRegistry initialized with {} parsers: {}", 
            registeredParsers.size(),
            registeredParsers.stream()
                .map(LanguageParser::getLanguageName)
                .collect(Collectors.joining(", ")));
    }
    
    /**
     * 注册解析器
     * 
     * @param parser 要注册的解析器
     */
    public void register(LanguageParser parser) {
        if (parser == null || !parser.isAvailable()) {
            log.warn("Skipping unavailable parser: {}", 
                parser != null ? parser.getLanguageName() : "null");
            return;
        }
        
        if (registeredParsers.add(parser)) {
            // 按扩展名索引
            for (String extension : parser.getSupportedExtensions()) {
                String normalizedExt = normalizeExtension(extension);
                parsersByExtension
                    .computeIfAbsent(normalizedExt, k -> new ArrayList<>())
                    .add(parser);
            }
            log.debug("Registered parser: {} for extensions: {}", 
                parser.getLanguageName(), parser.getSupportedExtensions());
        }
    }
    
    /**
     * 注销解析器
     * 
     * @param parser 要注销的解析器
     */
    public void unregister(LanguageParser parser) {
        if (parser == null) {
            return;
        }
        
        if (registeredParsers.remove(parser)) {
            // 从扩展名索引中移除
            for (String extension : parser.getSupportedExtensions()) {
                String normalizedExt = normalizeExtension(extension);
                List<LanguageParser> parsers = parsersByExtension.get(normalizedExt);
                if (parsers != null) {
                    parsers.remove(parser);
                    if (parsers.isEmpty()) {
                        parsersByExtension.remove(normalizedExt);
                    }
                }
            }
            log.debug("Unregistered parser: {}", parser.getLanguageName());
        }
    }
    
    /**
     * 根据文件路径获取最合适的解析器
     * 
     * @param filePath 文件路径
     * @return 最合适的解析器，如果没有找到则返回 Optional.empty()
     */
    public Optional<LanguageParser> getParserForFile(Path filePath) {
        if (filePath == null) {
            return Optional.empty();
        }
        
        String fileName = filePath.getFileName().toString();
        String extension = extractExtension(fileName);
        
        if (extension.isEmpty()) {
            return Optional.empty();
        }
        
        List<LanguageParser> candidates = parsersByExtension.get(extension);
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        
        // 如果只有一个候选，直接返回
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }
        
        // 多个候选时，选择优先级最高且支持该文件的
        return candidates.stream()
            .filter(LanguageParser::isAvailable)
            .filter(p -> p.supportsFile(filePath))
            .max(Comparator.comparingInt(LanguageParser::getPriority));
    }
    
    /**
     * 检查是否支持解析指定文件
     * 
     * @param filePath 文件路径
     * @return 是否有可用的解析器
     */
    public boolean canParse(Path filePath) {
        return getParserForFile(filePath).isPresent();
    }
    
    /**
     * 获取所有已注册的解析器
     * 
     * @return 解析器集合的不可变副本
     */
    public Set<LanguageParser> getAllParsers() {
        return Collections.unmodifiableSet(new HashSet<>(registeredParsers));
    }
    
    /**
     * 获取所有支持的文件扩展名
     * 
     * @return 扩展名集合
     */
    public Set<String> getSupportedExtensions() {
        return Collections.unmodifiableSet(new HashSet<>(parsersByExtension.keySet()));
    }
    
    /**
     * 获取支持的语言列表
     * 
     * @return 语言名称列表
     */
    public List<String> getSupportedLanguages() {
        return registeredParsers.stream()
            .filter(LanguageParser::isAvailable)
            .map(LanguageParser::getLanguageName)
            .sorted()
            .collect(Collectors.toList());
    }
    
    /**
     * 统一扩展名格式
     */
    private String normalizeExtension(String extension) {
        String ext = extension.toLowerCase().trim();
        return ext.startsWith(".") ? ext : "." + ext;
    }
    
    /**
     * 从文件名提取扩展名
     */
    private String extractExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot).toLowerCase();
        }
        return "";
    }
}
