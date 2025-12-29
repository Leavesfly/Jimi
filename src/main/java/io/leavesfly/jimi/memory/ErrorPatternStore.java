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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 错误模式存储
 * 管理所有错误模式记录
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorPatternStore {
    
    /**
     * 版本号
     */
    @JsonProperty("version")
    private String version = "1.0";
    
    /**
     * 错误模式列表
     */
    @JsonProperty("patterns")
    private List<ErrorPattern> patterns = new ArrayList<>();
    
    /**
     * 添加或更新错误模式
     * 如果已存在相似模式，则增加计数；否则添加新模式
     */
    public void addOrUpdate(ErrorPattern newPattern) {
        // 查找是否存在相似模式
        Optional<ErrorPattern> existing = patterns.stream()
                .filter(p -> p.matches(newPattern.getErrorMessage(), newPattern.getContext()))
                .findFirst();
        
        if (existing.isPresent()) {
            // 更新现有模式
            ErrorPattern pattern = existing.get();
            pattern.incrementOccurrence();
            
            // 如果新模式有更好的解决方案，更新它
            if (newPattern.getSolution() != null && !newPattern.getSolution().isEmpty()) {
                pattern.setSolution(newPattern.getSolution());
            }
        } else {
            // 添加新模式
            patterns.add(newPattern);
        }
    }
    
    /**
     * 记录某个模式的解决成功
     */
    public void recordResolution(String errorMessage, String context) {
        patterns.stream()
                .filter(p -> p.matches(errorMessage, context))
                .findFirst()
                .ifPresent(ErrorPattern::recordResolution);
    }
    
    /**
     * 查找匹配的错误模式
     */
    public Optional<ErrorPattern> findMatch(String errorMessage, String context) {
        return patterns.stream()
                .filter(p -> p.matches(errorMessage, context))
                .findFirst();
    }
    
    /**
     * 获取最常见的错误模式
     */
    public List<ErrorPattern> getMostFrequent(int limit) {
        return patterns.stream()
                .sorted(Comparator.comparing(ErrorPattern::getOccurrenceCount).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取最近的错误模式
     */
    public List<ErrorPattern> getRecent(int limit) {
        return patterns.stream()
                .sorted(Comparator.comparing(ErrorPattern::getLastSeen).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * 按错误类型获取模式
     */
    public List<ErrorPattern> getByType(String errorType, int limit) {
        return patterns.stream()
                .filter(p -> p.getErrorType() != null && 
                             p.getErrorType().equalsIgnoreCase(errorType))
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * 按工具名称获取模式
     */
    public List<ErrorPattern> getByTool(String toolName, int limit) {
        return patterns.stream()
                .filter(p -> p.getToolName() != null && 
                             p.getToolName().equalsIgnoreCase(toolName))
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * 按关键词搜索
     */
    public List<ErrorPattern> searchByKeyword(String keyword, int limit) {
        if (keyword == null || keyword.isEmpty()) {
            return List.of();
        }
        
        String lowerKeyword = keyword.toLowerCase();
        
        return patterns.stream()
                .filter(p -> 
                    (p.getErrorMessage() != null && p.getErrorMessage().toLowerCase().contains(lowerKeyword)) ||
                    (p.getContext() != null && p.getContext().toLowerCase().contains(lowerKeyword)) ||
                    (p.getSolution() != null && p.getSolution().toLowerCase().contains(lowerKeyword))
                )
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * 清理过期的错误模式
     */
    public void prune(int maxSize, int expiryDays) {
        // 1. 移除过期模式（但保留高频模式）
        Instant expiry = Instant.now().minus(expiryDays, ChronoUnit.DAYS);
        patterns.removeIf(p -> 
                p.getLastSeen() != null && 
                p.getLastSeen().isBefore(expiry) && 
                p.getOccurrenceCount() < 3);  // 保留出现3次以上的
        
        // 2. 如果仍然超限，按重要性排序后保留
        if (patterns.size() > maxSize) {
            patterns = patterns.stream()
                    .sorted((a, b) -> {
                        // 优先按出现次数，其次按最近时间
                        int cmp = Integer.compare(b.getOccurrenceCount(), a.getOccurrenceCount());
                        if (cmp == 0) {
                            return b.getLastSeen().compareTo(a.getLastSeen());
                        }
                        return cmp;
                    })
                    .limit(maxSize)
                    .collect(Collectors.toList());
        }
    }
    
    /**
     * 获取统计信息
     */
    public ErrorStats getStats() {
        if (patterns.isEmpty()) {
            return new ErrorStats(0, 0, 0, 0.0);
        }
        
        int totalPatterns = patterns.size();
        int totalOccurrences = patterns.stream().mapToInt(ErrorPattern::getOccurrenceCount).sum();
        int totalResolved = patterns.stream().mapToInt(ErrorPattern::getResolvedCount).sum();
        double avgResolutionRate = totalOccurrences > 0 
                ? (double) totalResolved / totalOccurrences 
                : 0.0;
        
        return new ErrorStats(totalPatterns, totalOccurrences, totalResolved, avgResolutionRate);
    }
    
    /**
     * 统计信息数据类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorStats {
        private int totalPatterns;
        private int totalOccurrences;
        private int totalResolved;
        private double avgResolutionRate;
    }
}
