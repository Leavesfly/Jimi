package io.leavesfly.jimi.soul.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 内置系统提示词参数
 * 这些参数会被替换到系统提示词模板中
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuiltinSystemPromptArgs {
    
    /**
     * 当前日期时间（ISO 8601 格式）
     */
    private String jimiNow;
    
    /**
     * 工作目录路径
     */
    private Path jimiWorkDir;
    
    /**
     * 工作目录列表
     */
    private String jimiWorkDirLs;
    
    /**
     * AGENTS.md 内容
     */
    private String jimiAgentsMd;
}
