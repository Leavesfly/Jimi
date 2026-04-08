package io.leavesfly.jimi.wire.message;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Wire 请求消息基类
 * <p>
 * 支持请求-响应模式：发送方通过 {@link #getResponseMono()} 等待响应，
 * 处理方通过 {@link #complete(Object)} 或 {@link #fail(Throwable)} 回写结果。
 *
 * @param <R> 响应类型
 */
public abstract class WireRequest<R> implements WireMessage {

    private final Sinks.One<R> responseSink = Sinks.one();

    /**
     * 获取响应的 Mono，调用方通过此 Mono 等待处理结果
     */
    public Mono<R> getResponseMono() {
        return responseSink.asMono();
    }

    /**
     * 完成请求，回写成功响应
     *
     * @param response 响应值
     */
    public void complete(R response) {
        responseSink.tryEmitValue(response);
    }

    /**
     * 完成请求（无返回值场景），发出空完成信号
     */
    public void completeEmpty() {
        responseSink.tryEmitEmpty();
    }

    /**
     * 请求失败，回写错误
     *
     * @param error 错误信息
     */
    public void fail(Throwable error) {
        responseSink.tryEmitError(error);
    }
}
