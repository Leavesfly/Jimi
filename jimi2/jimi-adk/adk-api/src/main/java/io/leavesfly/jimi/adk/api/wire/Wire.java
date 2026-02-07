package io.leavesfly.jimi.adk.api.wire;

import reactor.core.publisher.Flux;

/**
 * 消息总线接口
 * 用于组件间的事件通信
 */
public interface Wire {
    
    /**
     * 发送消息到总线
     *
     * @param message 消息
     */
    void send(WireMessage message);
    
    /**
     * 订阅消息流
     *
     * @return 消息 Flux
     */
    Flux<WireMessage> asFlux();
    
    /**
     * 订阅特定类型的消息
     *
     * @param messageType 消息类型
     * @param <T> 消息类型泛型
     * @return 消息 Flux
     */
    <T extends WireMessage> Flux<T> ofType(Class<T> messageType);
}
