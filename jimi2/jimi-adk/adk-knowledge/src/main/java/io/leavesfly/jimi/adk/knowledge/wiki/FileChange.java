package io.leavesfly.jimi.adk.knowledge.wiki;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * 文件变更记录
 */
@Data
@Builder
public class FileChange {
    
    /**
     * 文件路径（相对路径）
     */
    private String filePath;
    
    /**
     * 绝对路径
     */
    private Path absolutePath;
    
    /**
     * 变更类型
     */
    private ChangeType changeType;
    
    /**
     * 旧内容哈希
     */
    private String oldHash;
    
    /**
     * 新内容哈希
     */
    private String newHash;
    
    /**
     * 变更行数
     */
    private int changedLines;
    
    /**
     * 文件语言类型
     */
    private String language;
    
    /**
     * 变更重要性
     */
    private ChangeImportance importance;
    
    /**
     * 变更类型枚举
     */
    public enum ChangeType {
        ADDED,      // 新增文件
        MODIFIED,   // 修改文件
        DELETED     // 删除文件
    }
    
    /**
     * 变更重要性枚举
     */
    public enum ChangeImportance {
        CRITICAL,   // 关键变更：架构调整、新增模块
        MAJOR,      // 重要变更：API/接口变更
        MINOR,      // 一般变更：实现细节优化
        TRIVIAL     // 微小变更：注释、格式调整
    }
}
