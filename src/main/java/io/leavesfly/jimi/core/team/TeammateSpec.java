package io.leavesfly.jimi.core.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Teammate 配置规范
 * <p>
 * 定义团队中单个 Teammate 的配置，复用现有 Agent 配置体系。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeammateSpec {

    /**
     * Teammate 唯一标识
     */
    @JsonProperty("teammate_id")
    private String teammateId;

    /**
     * Agent 配置文件路径（复用现有 Agent 配置）
     */
    @JsonProperty("agent_path")
    private Path agentPath;

    /**
     * Teammate 描述
     */
    @JsonProperty("description")
    private String description;

    /**
     * 擅长领域（用于智能任务分配）
     */
    @JsonProperty("specialties")
    @Builder.Default
    private List<String> specialties = new ArrayList<>();
}
