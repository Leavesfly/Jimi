package io.leavesfly.jimi.knowledge.graph.parser;

import java.nio.file.Path;
import java.util.Set;

/**
 * 语言解析器接口
 * <p>
 * 定义代码解析的通用接口，支持多种编程语言的 AST 解析。
 * 每种语言的解析器需要实现此接口。
 */
public interface LanguageParser {
    
    /**
     * 获取解析器支持的语言名称
     * 
     * @return 语言名称（如 "Java", "Python", "TypeScript"）
     */
    String getLanguageName();
    
    /**
     * 获取解析器支持的文件扩展名
     * 
     * @return 文件扩展名集合（如 [".java"], [".py"], [".ts", ".tsx"]）
     */
    Set<String> getSupportedExtensions();
    
    /**
     * 检查是否支持指定文件
     * 
     * @param filePath 文件路径
     * @return 是否支持
     */
    default boolean supportsFile(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        return getSupportedExtensions().stream()
            .anyMatch(ext -> fileName.endsWith(ext.toLowerCase()));
    }
    
    /**
     * 解析代码文件
     * 
     * @param filePath 文件绝对路径
     * @param projectRoot 项目根目录（用于计算相对路径）
     * @return 解析结果，包含提取的实体和关系
     */
    ParseResult parseFile(Path filePath, Path projectRoot);
    
    /**
     * 获取解析器优先级
     * 当多个解析器都支持同一文件时，使用优先级最高的
     * 
     * @return 优先级（数值越大优先级越高，默认为 0）
     */
    default int getPriority() {
        return 0;
    }
    
    /**
     * 检查解析器是否可用
     * 某些解析器可能依赖外部库或工具，需要在运行时检查
     * 
     * @return 是否可用
     */
    default boolean isAvailable() {
        return true;
    }
}
