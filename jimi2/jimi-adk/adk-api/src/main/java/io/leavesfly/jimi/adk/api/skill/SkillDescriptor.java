package io.leavesfly.jimi.adk.api.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 描述信息
 * <p>
 * 轻量级的 Skill 元数据描述，用于 API 层的信息传递，
 * 不包含具体的实现细节（如文件路径、脚本配置等）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDescriptor {

    /** Skill 名称（唯一标识） */
    private String name;

    /** 简短描述 */
    private String description;

    /** 版本号 */
    @Builder.Default
    private String version = "1.0.0";

    /** 分类标签 */
    private String category;

    /** 触发关键词列表 */
    @Builder.Default
    private List<String> triggers = new ArrayList<>();

    /** 作用域：GLOBAL 或 PROJECT */
    private String scope;
}
