package io.leavesfly.jimi.adk.api.context;

import java.nio.file.Path;

/**
 * 可持久化上下文接口
 * <p>
 * 继承 {@link Context}，增加持久化保存和恢复能力。
 * 替代原有的 {@link ContextManager}，消除职责重叠。
 * </p>
 */
public interface PersistableContext extends Context {

    /**
     * 保存上下文到指定路径
     *
     * @param path 保存路径
     * @return 是否保存成功
     */
    boolean save(Path path);

    /**
     * 从持久化存储中恢复上下文
     *
     * @return 是否成功恢复
     */
    boolean restore();

    /**
     * 从指定路径恢复上下文
     *
     * @param path 恢复路径
     * @return 是否成功恢复
     */
    boolean restore(Path path);
}
