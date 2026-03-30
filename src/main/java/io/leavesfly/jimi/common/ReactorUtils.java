package io.leavesfly.jimi.common;

import io.leavesfly.jimi.core.hook.HookContext;
import io.leavesfly.jimi.core.hook.HookRegistry;
import io.leavesfly.jimi.core.hook.HookType;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Supplier;

/**
 * Reactor 辅助工具类
 * <p>
 * 封装常见的 Reactor 模式，减少项目中的重复代码，
 * 降低 Reactor 的使用门槛。
 * <p>
 * 主要功能：
 * - Hook 安全触发（统一错误处理）
 * - 同步操作到 Mono 的包装
 */
@Slf4j
public final class ReactorUtils {

    private ReactorUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 安全触发 Hook，失败时静默处理，不影响主流程
     * <p>
     * 统一了 AgentExecutor 和 ToolDispatcher 中重复的 Hook 触发逻辑。
     *
     * @param registry Hook 注册表
     * @param type     Hook 类型
     * @param ctx      Hook 上下文
     * @return 完成的 Mono（即使 Hook 失败也不会传播错误）
     */
    public static Mono<Void> triggerHookSafely(HookRegistry registry, HookType type, HookContext ctx) {
        try {
            return registry.trigger(type, ctx)
                    .onErrorResume(e -> {
                        log.warn("Hook trigger failed for type {}: {}", type, e.getMessage());
                        return Mono.empty();
                    });
        } catch (Exception e) {
            log.warn("Hook trigger failed for type {}: {}", type, e.getMessage());
            return Mono.empty();
        }
    }

    /**
     * 将同步操作包装为 Mono，在弹性线程池中执行
     * <p>
     * 适用于需要在响应式链路中执行阻塞操作的场景，
     * 避免阻塞事件循环线程。
     *
     * @param supplier 同步操作
     * @param <T>      返回值类型
     * @return 包装后的 Mono
     */
    public static <T> Mono<T> fromSync(Supplier<T> supplier) {
        return Mono.fromCallable(supplier::get)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 将同步无返回值操作包装为 Mono
     *
     * @param runnable 同步操作
     * @return 完成的 Mono
     */
    public static Mono<Void> runSync(Runnable runnable) {
        return Mono.fromRunnable(runnable)
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
