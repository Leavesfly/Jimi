package io.leavesfly.jimi.adk.api.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 运行时配置 - 不可变配置值对象
 * <p>
 * 聚合运行时的纯配置属性，与服务协作者（LLM、Session）分离。
 * <p>
 * 注意：YOLO 模式配置已移至 {@link InteractionContext}，
 * 通过 {@link io.leavesfly.jimi.adk.api.interaction.Approval#isYolo()} 统一管理。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeConfig {

    /**
     * 工作目录
     */
    private Path workDir;

    /**
     * 上下文最大 Token 数
     */
    @Builder.Default
    private int maxContextTokens = 100000;
}
