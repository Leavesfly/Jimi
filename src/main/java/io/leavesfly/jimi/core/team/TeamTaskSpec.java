package io.leavesfly.jimi.core.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 团队任务配置规范
 * <p>
 * 定义初始任务的配置，支持优先级和依赖关系。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamTaskSpec {

    /**
     * 任务描述
     */
    @JsonProperty("description")
    private String description;

    /**
     * 优先级（越小越高，默认 5）
     */
    @JsonProperty("priority")
    @Builder.Default
    private int priority = 5;

    /**
     * 依赖的任务索引列表（引用 initial_tasks 中的索引）
     */
    @JsonProperty("dependencies")
    @Builder.Default
    private List<String> dependencies = new ArrayList<>();

    /**
     * 优先分配给指定 Teammate（可选）
     */
    @JsonProperty("preferred_teammate")
    private String preferredTeammate;
}
