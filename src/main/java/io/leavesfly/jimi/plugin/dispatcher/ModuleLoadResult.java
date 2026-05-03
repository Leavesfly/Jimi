package io.leavesfly.jimi.plugin.dispatcher;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 单个扩展点模块的加载结果
 *
 * <p>由 {@link PluginModuleAdapter#load} 返回，记录：
 * <ul>
 *   <li>成功加载的扩展项名列表（便于后续 unload 逆操作）</li>
 *   <li>模块级错误信息（若加载失败）</li>
 * </ul>
 *
 * <p>加载失败不抛异常，而是返回带 {@link #getError()} 的结果对象，
 * 保证插件的其他模块仍可继续加载（容错粒度 = 单个模块）。
 */
@Getter
public final class ModuleLoadResult {

    /**
     * 成功加载的扩展项名列表（如 Skill 名 / Hook 名 / Command 名）
     */
    private final List<String> loadedItems;

    /**
     * 失败时的异常原因，成功时为 {@code null}
     */
    private final Throwable error;

    private ModuleLoadResult(List<String> loadedItems, Throwable error) {
        this.loadedItems = loadedItems;
        this.error = error;
    }

    /**
     * 构建成功结果。
     *
     * @param loadedItems 成功加载的扩展项名列表
     * @return 成功结果
     */
    public static ModuleLoadResult success(List<String> loadedItems) {
        return new ModuleLoadResult(
                loadedItems == null ? Collections.emptyList() : List.copyOf(loadedItems),
                null);
    }

    /**
     * 构建失败结果。
     *
     * @param error 失败原因
     * @return 失败结果
     */
    public static ModuleLoadResult failed(Throwable error) {
        return new ModuleLoadResult(Collections.emptyList(), error);
    }

    /**
     * 判断加载是否成功。
     *
     * @return 成功时返回 {@code true}
     */
    public boolean isSuccess() {
        return error == null;
    }

    /**
     * 获取失败原因的简短文本（便于日志输出）。
     *
     * @return 失败文本，成功时返回空字符串
     */
    public String getErrorMessage() {
        return error == null ? "" : error.getMessage();
    }
}
