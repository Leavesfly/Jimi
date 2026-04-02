package io.leavesfly.jimi.team;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 团队消息总线
 * <p>
 * 支持 Teammate 之间的横向通信，基于 Reactor Sinks 实现发布-订阅模式。
 * 同时维护消息历史，供 Teammate 在执行任务时获取上下文。
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class TeamMessageBus {

    private final Sinks.Many<TeamMessage> messageSink;
    private final CopyOnWriteArrayList<TeamMessage> messageHistory;
    private String teamId;

    public TeamMessageBus() {
        this.messageSink = Sinks.many().multicast().onBackpressureBuffer();
        this.messageHistory = new CopyOnWriteArrayList<>();
    }

    /**
     * 初始化团队 ID
     */
    public void init(String teamId) {
        this.teamId = teamId;
    }

    /**
     * 发送消息给指定 Teammate
     */
    public void sendTo(String fromId, String toId, String content) {
        TeamMessage message = TeamMessage.builder()
                .fromTeammateId(fromId)
                .toTeammateId(toId)
                .type(TeamMessage.TeamMessageType.DIRECT)
                .content(content)
                .build();
        publish(message);
    }

    /**
     * 广播消息给所有 Teammate
     */
    public void broadcast(String fromId, String content) {
        TeamMessage message = TeamMessage.builder()
                .fromTeammateId(fromId)
                .type(TeamMessage.TeamMessageType.BROADCAST)
                .content(content)
                .build();
        publish(message);
    }

    /**
     * 发送协助请求
     */
    public void requestHelp(String fromId, String topic, String detail) {
        TeamMessage message = TeamMessage.builder()
                .fromTeammateId(fromId)
                .type(TeamMessage.TeamMessageType.HELP_REQUEST)
                .content(String.format("[Help Request] Topic: %s\n%s", topic, detail))
                .build();
        publish(message);
    }

    /**
     * 通知发现了新的子任务
     */
    public void notifyTaskDiscovery(String fromId, String taskDescription) {
        TeamMessage message = TeamMessage.builder()
                .fromTeammateId(fromId)
                .type(TeamMessage.TeamMessageType.TASK_DISCOVERY)
                .content(String.format("[Task Discovery] %s", taskDescription))
                .build();
        publish(message);
    }

    /**
     * 订阅发给指定 Teammate 的消息（包括广播）
     */
    public Flux<TeamMessage> subscribe(String teammateId) {
        return messageSink.asFlux()
                .filter(msg -> msg.isBroadcast()
                        || teammateId.equals(msg.getToTeammateId()));
    }

    /**
     * 订阅所有消息
     */
    public Flux<TeamMessage> subscribeAll() {
        return messageSink.asFlux();
    }

    /**
     * 获取指定 Teammate 的最近消息（从历史中获取）
     *
     * @param teammateId Teammate ID
     * @param limit      最大消息数
     * @return 最近的消息列表
     */
    public List<TeamMessage> getRecentMessages(String teammateId, int limit) {
        return messageHistory.stream()
                .filter(msg -> msg.isBroadcast()
                        || teammateId.equals(msg.getToTeammateId())
                        || teammateId.equals(msg.getFromTeammateId()))
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有消息历史
     */
    public List<TeamMessage> getAllMessages() {
        return List.copyOf(messageHistory);
    }

    private void publish(TeamMessage message) {
        messageHistory.add(message);
        Sinks.EmitResult result = messageSink.tryEmitNext(message);
        if (result.isFailure()) {
            log.warn("[Team {}] Failed to emit message: {}", teamId, result);
        } else {
            log.debug("[Team {}] Message published: {} -> {}",
                    teamId, message.getFromTeammateId(),
                    message.getToTeammateId() != null ? message.getToTeammateId() : "ALL");
        }
    }

    /**
     * 关闭消息总线
     */
    public void close() {
        messageSink.tryEmitComplete();
    }
}
