package io.leavesfly.jimi.core.team;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 团队消息
 * <p>
 * 用于 Teammate 之间的横向通信。
 */
@Data
@Builder
public class TeamMessage {

    /**
     * 消息唯一标识
     */
    @Builder.Default
    private final String messageId = UUID.randomUUID().toString();

    /**
     * 发送者 Teammate ID
     */
    private final String fromTeammateId;

    /**
     * 接收者 Teammate ID（null 表示广播）
     */
    private final String toTeammateId;

    /**
     * 消息类型
     */
    @Builder.Default
    private final TeamMessageType type = TeamMessageType.BROADCAST;

    /**
     * 消息内容
     */
    private final String content;

    /**
     * 时间戳
     */
    @Builder.Default
    private final Instant timestamp = Instant.now();

    /**
     * 是否为广播消息
     */
    public boolean isBroadcast() {
        return toTeammateId == null || type == TeamMessageType.BROADCAST;
    }

    /**
     * 消息类型枚举
     */
    public enum TeamMessageType {
        /**
         * 直接消息：发给指定 Teammate
         */
        DIRECT,

        /**
         * 广播消息：发给所有 Teammate
         */
        BROADCAST,

        /**
         * 协助请求：请求其他 Teammate 帮助
         */
        HELP_REQUEST,

        /**
         * 任务发现：通知发现了新的子任务
         */
        TASK_DISCOVERY
    }
}
