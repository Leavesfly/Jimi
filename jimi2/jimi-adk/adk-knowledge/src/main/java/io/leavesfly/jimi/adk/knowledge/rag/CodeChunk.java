package io.leavesfly.jimi.adk.knowledge.rag;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 代码片段数据模型
 * <p>
 * 表示索引中的一个代码片段，包含内容、元数据和向量
 */
@Data
@Builder
public class CodeChunk {

    /**
     * 唯一标识符（通常是 file_path + offset 的哈希）
     */
    private String id;

    /**
     * 片段内容
     */
    private String content;

    /**
     * 文件路径（相对于项目根目录）
     */
    private String filePath;

    /**
     * 符号名称（可选：类名、方法名等）
     */
    private String symbol;

    /**
     * 起始行号
     */
    private int startLine;

    /**
     * 结束行号
     */
    private int endLine;

    /**
     * 编程语言
     */
    private String language;

    /**
     * 内容MD5哈希（用于检测变化）
     */
    private String contentHash;

    /**
     * 更新时间戳
     */
    private long updatedAt;

    /**
     * 嵌入向量
     */
    private float[] embedding;

    /**
     * 扩展元数据（如：导入、依赖、注释等）
     */
    private Map<String, String> metadata;

    /**
     * 获取片段描述（用于日志显示）
     */
    public String getDescription() {
        return String.format("%s:%d-%d%s",
                filePath,
                startLine,
                endLine,
                symbol != null ? " (" + symbol + ")" : "");
    }
}
