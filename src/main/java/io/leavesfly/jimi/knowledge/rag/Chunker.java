package io.leavesfly.jimi.knowledge.rag;

import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

/**
 * 代码分块器接口
 * <p>
 * 职责：
 * - 将源代码文件分割成适合向量化的片段
 * - 支持语言感知的智能分块（按函数、类等）
 * - 支持简单的固定窗口分块
 * <p>
 * 分块策略：
 * - 固定行数窗口（简单，通用）
 * - 符号级分块（需要AST解析，精确）
 * - 滑动窗口（带重叠，适合长函数）
 */
public interface Chunker {

    /**
     * 将文件内容分块
     *
     * @param filePath   文件路径（相对于项目根目录）
     * @param content    文件内容
     * @param chunkSize  分块大小（行数或Token数）
     * @param overlap    重叠大小（行数或Token数，0表示无重叠）
     * @return 代码片段流（异步）
     */
    Flux<CodeChunk> chunk(String filePath, String content, int chunkSize, int overlap);

    /**
     * 批量分块多个文件
     *
     * @param files      文件路径列表
     * @param chunkSize  分块大小
     * @param overlap    重叠大小
     * @return 代码片段流（异步）
     */
    Flux<CodeChunk> chunkFiles(List<Path> files, int chunkSize, int overlap);

    /**
     * 获取分块器支持的语言列表
     *
     * @return 支持的语言列表（如：["java", "python", "javascript"]）
     */
    List<String> getSupportedLanguages();

    /**
     * 检测文件语言
     *
     * @param filePath 文件路径
     * @return 语言标识（如："java"、"python"、"unknown"）
     */
    String detectLanguage(String filePath);
}
