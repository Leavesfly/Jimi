package io.leavesfly.jimi.adk.knowledge.api.spi;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.knowledge.api.result.GraphResult;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

/**
 * 图谱生命周期管理接口
 * <p>
 * 负责图谱的初始化、构建、保存、加载等生命周期管理。
 * 从 GraphService 中拆分，遵循接口隔离原则。
 * </p>
 */
public interface GraphLifecycle {

    /**
     * 初始化图谱服务
     */
    Mono<Boolean> initialize(Runtime runtime);

    /**
     * 构建代码图谱
     */
    Mono<GraphResult> build(Path projectRoot);

    /**
     * 保存图谱到磁盘
     */
    Mono<Boolean> save();

    /**
     * 加载图谱
     */
    Mono<Boolean> load(Path storagePath);

    /**
     * 检查图谱是否已初始化
     */
    boolean isInitialized();

    /**
     * 检查图谱功能是否启用
     */
    boolean isEnabled();
}
