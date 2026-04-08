package io.leavesfly.jimi.wire;

import io.leavesfly.jimi.wire.message.WireMessage;
import io.leavesfly.jimi.wire.message.WireRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Wire 消息总线接口
 * <p>
 * 支持双向通信：
 * - 下行通道（Engine → Client）：通过 {@link #send(WireMessage)} 和 {@link #asFlux()} 实现
 * - 上行通道（Client → Engine）：通过 {@link #request(WireRequest)} 和 {@link #requests()} 实现
 */
public interface Wire {

    // ==================== 下行通道（Engine → Client） ====================

    /**
     * 发送下行消息（Engine 发给 Client）
     */
    void send(WireMessage message);

    /**
     * 获取下行消息流（Client 订阅）
     */
    Flux<WireMessage> asFlux();

    // ==================== 上行通道（Client → Engine） ====================

    /**
     * 发送请求并等待响应（Client 调用）
     * <p>
     * 请求通过上行通道发送给 Engine，Engine 处理后通过 WireRequest 内置的 Sink 回写响应。
     *
     * @param request 请求消息
     * @param <R>     响应类型
     * @return 响应的 Mono
     */
    <R> Mono<R> request(WireRequest<R> request);

    /**
     * 获取上行请求流（Engine 订阅，用于处理 Client 发来的请求）
     */
    Flux<WireRequest<?>> requests();

    // ==================== 生命周期 ====================

    /**
     * 完成消息发送
     */
    void complete();

    /**
     * 重置 Wire，创建新的 Sink 以支持多次执行
     */
    void reset();
}
