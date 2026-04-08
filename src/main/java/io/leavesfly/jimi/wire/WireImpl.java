package io.leavesfly.jimi.wire;

import io.leavesfly.jimi.wire.message.WireMessage;
import io.leavesfly.jimi.wire.message.WireRequest;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Wire 消息总线实现
 * <p>
 * 使用 Reactor Sinks 实现双向异步通信：
 * - 下行通道（Engine → Client）：通过 messageSink 实现
 * - 上行通道（Client → Engine）：通过 requestSink 实现请求-响应模式
 * <p>
 * 支持通过 reset() 重置 Sink 以支持多次执行
 */
@Slf4j
public class WireImpl implements Wire {

    /** 下行消息通道（Engine → Client） */
    private final AtomicReference<Sinks.Many<WireMessage>> messageSinkRef;

    /** 上行请求通道（Client → Engine） */
    private final AtomicReference<Sinks.Many<WireRequest<?>>> requestSinkRef;

    public WireImpl() {
        this.messageSinkRef = new AtomicReference<>(createMessageSink());
        this.requestSinkRef = new AtomicReference<>(createRequestSink());
    }

    private Sinks.Many<WireMessage> createMessageSink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    private Sinks.Many<WireRequest<?>> createRequestSink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    // ==================== 下行通道 ====================

    @Override
    public void send(WireMessage message) {
        messageSinkRef.get().tryEmitNext(message);
    }

    @Override
    public Flux<WireMessage> asFlux() {
        return messageSinkRef.get().asFlux();
    }

    // ==================== 上行通道 ====================

    @Override
    public <R> Mono<R> request(WireRequest<R> request) {
        log.debug("Sending wire request: {}", request.getMessageType());
        requestSinkRef.get().tryEmitNext(request);
        return request.getResponseMono();
    }

    @Override
    public Flux<WireRequest<?>> requests() {
        return requestSinkRef.get().asFlux();
    }

    // ==================== 生命周期 ====================

    @Override
    public void complete() {
        messageSinkRef.get().tryEmitComplete();
    }

    @Override
    public void reset() {
        messageSinkRef.set(createMessageSink());
        requestSinkRef.set(createRequestSink());
    }
}
