package io.leavesfly.jimi.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话实体
 * 表示一个工作目录下的会话
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {
    
    /**
     * 会话 ID（UUID 格式）
     */
    private String id;
    
    /**
     * 工作目录绝对路径
     */
    private Path workDir;
    
    /**
     * 历史记录文件路径
     */
    private Path historyFile;
    
    /**
     * 创建时间戳
     */
    @Builder.Default
    private Instant createdAt = Instant.now();
    
    /**
     * 全局步数计数器（跨所有Agent共享）
     * 用于统计整个会话的总执行步数
     */
    @Builder.Default
    private AtomicInteger globalStepCounter = new AtomicInteger(0);
    
    /**
     * 获取并递增全局步数
     * @return 递增后的步数
     */
    public int incrementAndGetGlobalStep() {
        return globalStepCounter.incrementAndGet();
    }
    
    /**
     * 获取当前全局步数
     * @return 当前步数
     */
    public int getGlobalStepCount() {
        return globalStepCounter.get();
    }
}
