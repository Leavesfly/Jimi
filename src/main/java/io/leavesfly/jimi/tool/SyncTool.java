package io.leavesfly.jimi.tool;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 同步工具基类
 * <p>
 * 适用于不需要异步操作的工具（如读取文件、搜索代码、格式化输出等）。
 * 子类只需实现 {@link #executeSync(Object)} 方法，无需关心 Reactor 的 Mono 包装。
 * <p>
 * 设计目的：
 * - 降低工具开发的学习成本，开发者无需了解 Reactor
 * - 减少样板代码（避免到处写 Mono.defer(() -> ...)）
 * - 保持与 Tool 接口的兼容性
 * <p>
 * 使用示例：
 * <pre>
 * public class MyTool extends SyncTool&lt;MyTool.Params&gt; {
 *     public MyTool() {
 *         super("my_tool", "工具描述", Params.class);
 *     }
 *
 *     &#64;Override
 *     protected ToolResult executeSync(Params params) {
 *         // 直接编写同步业务逻辑
 *         String result = doSomething(params);
 *         return ToolResult.ok(result);
 *     }
 * }
 * </pre>
 *
 * @param <P> 参数类型
 */
public abstract class SyncTool<P> extends AbstractTool<P> {

    protected SyncTool(String name, String description, Class<P> paramsType) {
        super(name, description, paramsType);
    }

    /**
     * 同步执行工具调用
     * <p>
     * 子类实现此方法，只需关注业务逻辑，无需处理 Mono 包装。
     *
     * @param params 工具参数
     * @return 工具执行结果
     */
    protected abstract ToolResult executeSync(P params);

    /**
     * 实现 Tool 接口的 execute 方法
     * <p>
     * 将同步执行包装为 Mono，在弹性线程池中执行以避免阻塞事件循环。
     *
     * @param params 工具参数
     * @return 工具执行结果的 Mono
     */
    @Override
    public final Mono<ToolResult> execute(P params) {
        return Mono.fromCallable(() -> executeSync(params))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
