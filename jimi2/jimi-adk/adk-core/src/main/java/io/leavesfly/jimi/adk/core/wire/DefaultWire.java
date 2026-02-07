package io.leavesfly.jimi.adk.core.wire;

import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Wire 默认实现
 * 基于 Reactor Sinks 的消息总线
 */
@Slf4j
public class DefaultWire implements Wire {
    
    /**
     * 消息发送器（多播模式）
     */
    private final Sinks.Many<WireMessage> sink;
    
    public DefaultWire() {
        this.sink = Sinks.many().multicast().onBackpressureBuffer();
    }
    
    @Override
    public void send(WireMessage message) {
        if (message == null) {
            return;
        }
        log.debug("Wire 发送消息: {}", message.getMessageType());
        sink.tryEmitNext(message);
    }
    
    @Override
    public Flux<WireMessage> asFlux() {
        return sink.asFlux();
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T extends WireMessage> Flux<T> ofType(Class<T> messageType) {
        return asFlux()
                .filter(messageType::isInstance)
                .map(msg -> (T) msg);
    }
}
