package io.leavesfly.jimi.adk.api.session;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 会话接口
 * 表示一个用户会话
 */
public interface Session {
    
    /**
     * 获取会话 ID
     *
     * @return 会话 ID
     */
    String getId();
    
    /**
     * 获取工作目录
     *
     * @return 工作目录路径
     */
    Path getWorkDir();
    
    /**
     * 获取会话创建时间
     *
     * @return 创建时间
     */
    Instant getCreatedAt();
    
    /**
     * 获取会话最后活动时间
     *
     * @return 最后活动时间
     */
    Instant getLastActivityAt();
    
    /**
     * 更新最后活动时间
     */
    void touch();
    
    /**
     * 检查会话是否有效
     *
     * @return 是否有效
     */
    boolean isValid();
}
