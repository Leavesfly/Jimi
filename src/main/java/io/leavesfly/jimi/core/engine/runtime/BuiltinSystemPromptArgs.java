package io.leavesfly.jimi.core.engine.runtime;

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
    
    /**
     * 可用技能摘要（渐进式披露）
     * 包含技能列表的简要描述，供 LLM 决定是否调用 SkillsTool
     */
    private String jimiSkillsSummary;
}
