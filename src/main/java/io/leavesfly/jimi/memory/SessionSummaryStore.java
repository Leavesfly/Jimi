package io.leavesfly.jimi.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话摘要存储
 * 管理所有会话摘要历史
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummaryStore {
    
    /**
     * 版本号
     */
    @JsonProperty("version")
    private String version = "1.0";
    
    /**
     * 会话摘要列表
     */
    @JsonProperty("sessions")
    private List<SessionSummary> sessions = new ArrayList<>();
    
    /**
     * 添加会话摘要
     */
    public void add(SessionSummary session) {
        sessions.add(session);
    }
    
    /**
     * 获取最近的会话（按时间倒序）
     * 
     * @param limit 返回数量
     * @return 会话列表
     */
    public List<SessionSummary> getRecent(int limit) {
        return sessions.stream()
                .sorted(Comparator.comparing(SessionSummary::getStartTime).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取最近一次会话
     */
    public SessionSummary getLastSession() {
        if (sessions.isEmpty()) {
            return null;
        }
        return sessions.stream()
                .max(Comparator.comparing(SessionSummary::getStartTime))
                .orElse(null);
    }
    
    /**
     * 按时间范围查询会话
     * 
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 会话列表
     */
    public List<SessionSummary> getByTimeRange(Instant startTime, Instant endTime) {
        return sessions.stream()
                .filter(session -> {
                    Instant timestamp = session.getStartTime();
                    return timestamp != null && 
                           !timestamp.isBefore(startTime) && 
                           !timestamp.isAfter(endTime);
                })
                .sorted(Comparator.comparing(SessionSummary::getStartTime).reversed())
                .collect(Collectors.toList());
    }
    
    /**
     * 按关键词搜索会话
     * 
     * @param keyword 关键词
     * @param limit 返回数量
     * @return 会话列表
     */
    public List<SessionSummary> searchByKeyword(String keyword, int limit) {
        if (keyword == null || keyword.isEmpty()) {
            return List.of();
        }
        
        String lowerKeyword = keyword.toLowerCase();
        
        return sessions.stream()
                .filter(session -> 
                    (session.getGoal() != null && session.getGoal().toLowerCase().contains(lowerKeyword)) ||
                    (session.getOutcome() != null && session.getOutcome().toLowerCase().contains(lowerKeyword)) ||
                    session.getFilesModified().stream().anyMatch(f -> f.toLowerCase().contains(lowerKeyword))
                )
                .sorted(Comparator.comparing(SessionSummary::getStartTime).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * 清理过期的会话摘要
     * 
     * @param maxSize 最大保留数量
     * @param expiryDays 过期天数
     */
    public void prune(int maxSize, int expiryDays) {
        // 1. 移除过期会话
        Instant expiry = Instant.now().minus(expiryDays, ChronoUnit.DAYS);
        sessions.removeIf(session -> 
                session.getStartTime() != null && session.getStartTime().isBefore(expiry));
        
        // 2. 如果仍然超限，只保留最近的会话
        if (sessions.size() > maxSize) {
            sessions = sessions.stream()
                    .sorted(Comparator.comparing(SessionSummary::getStartTime).reversed())
                    .limit(maxSize)
                    .collect(Collectors.toList());
        }
    }
    
    /**
     * 获取统计信息
     */
    public SessionStats getStats() {
        if (sessions.isEmpty()) {
            return new SessionStats(0, 0, 0, 0);
        }
        
        int totalSessions = sessions.size();
        int totalSteps = sessions.stream().mapToInt(SessionSummary::getTotalSteps).sum();
        int totalTokens = sessions.stream().mapToInt(SessionSummary::getTotalTokens).sum();
        long totalDuration = sessions.stream().mapToLong(SessionSummary::getDurationMs).sum();
        
        return new SessionStats(totalSessions, totalSteps, totalTokens, totalDuration);
    }
    
    /**
     * 统计信息数据类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionStats {
        private int totalSessions;
        private int totalSteps;
        private int totalTokens;
        private long totalDurationMs;
    }
}
