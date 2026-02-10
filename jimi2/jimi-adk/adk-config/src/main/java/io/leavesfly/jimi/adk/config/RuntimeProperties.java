package io.leavesfly.jimi.adk.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运行时强类型配置属性
 * <p>
 * 从 YAML 配置文件的顶层节点反序列化。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeProperties {

    /** 是否为 YOLO 模式（跳过审批） */
    @Builder.Default
    private boolean yoloMode = false;

    /** 上下文最大 Token 数 */
    @Builder.Default
    private int maxContextTokens = 100000;
}
