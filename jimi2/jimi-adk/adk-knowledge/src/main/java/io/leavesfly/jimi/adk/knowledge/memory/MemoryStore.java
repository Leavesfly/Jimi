package io.leavesfly.jimi.adk.knowledge.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 统一的记忆存储
 * 替代原有的4个独立Store类,使用类型区分不同的记忆
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryStore {
    
    @JsonProperty("version")
    private String version = "1.0";
    
    @JsonProperty("workspaceRoot")
    private String workspaceRoot;
    
    @JsonProperty("entries")
    private Map<MemoryType, List<MemoryEntry>> entries = new ConcurrentHashMap<>();
    
    public void add(MemoryEntry entry) {
        entries.computeIfAbsent(entry.getType(), k -> new ArrayList<>()).add(entry);
    }
    
    public void addOrUpdateErrorPattern(MemoryEntry entry) {
        if (entry.getType() != MemoryType.ERROR_PATTERN) {
            throw new IllegalArgumentException("Entry type must be ERROR_PATTERN");
        }
        List<MemoryEntry> patterns = entries.computeIfAbsent(MemoryType.ERROR_PATTERN, k -> new ArrayList<>());
        String errorMessage = entry.getMetadataString("errorMessage");
        String context = entry.getMetadataString("context");
        Optional<MemoryEntry> existing = patterns.stream()
                .filter(p -> matchesError(p, errorMessage, context))
                .findFirst();
        if (existing.isPresent()) {
            MemoryEntry existingEntry = existing.get();
            Integer count = existingEntry.getMetadataInt("occurrenceCount");
            existingEntry.setMetadata("occurrenceCount", count != null ? count + 1 : 2);
            existingEntry.setMetadata("lastSeen", Instant.now());
            existingEntry.touch();
            String newSolution = entry.getMetadataString("solution");
            if (newSolution != null && !newSolution.isEmpty()) {
                existingEntry.setMetadata("solution", newSolution);
            }
        } else {
            entry.setMetadata("occurrenceCount", 1);
            entry.setMetadata("resolvedCount", 0);
            entry.setMetadata("firstSeen", Instant.now());
            entry.setMetadata("lastSeen", Instant.now());
            patterns.add(entry);
        }
    }
    
    private boolean matchesError(MemoryEntry entry, String errorMsg, String ctx) {
        if (errorMsg == null) return false;
        String storedErrorMsg = entry.getMetadataString("errorMessage");
        if (storedErrorMsg == null) return false;
        boolean msgMatch = errorMsg.toLowerCase().contains(storedErrorMsg.toLowerCase());
        if (ctx != null) {
            String storedCtx = entry.getMetadataString("context");
            if (storedCtx != null) {
                boolean ctxMatch = ctx.toLowerCase().contains(storedCtx.toLowerCase());
                return msgMatch && ctxMatch;
            }
        }
        return msgMatch;
    }
    
    public List<MemoryEntry> getByType(MemoryType type) {
        return entries.getOrDefault(type, new ArrayList<>());
    }
    
    public List<MemoryEntry> getRecent(MemoryType type, int limit) {
        return getByType(type).stream()
                .sorted(Comparator.comparing(MemoryEntry::getCreatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    public List<MemoryEntry> searchByKeyword(MemoryType type, String keyword, int limit) {
        if (keyword == null || keyword.isEmpty()) return List.of();
        String lowerKeyword = keyword.toLowerCase();
        return getByType(type).stream()
                .filter(entry -> matchesKeyword(entry, lowerKeyword))
                .sorted(Comparator.comparing(MemoryEntry::getCreatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    private boolean matchesKeyword(MemoryEntry entry, String keyword) {
        if (entry.getContent() != null && entry.getContent().toLowerCase().contains(keyword)) {
            return true;
        }
        for (Object value : entry.getMetadata().values()) {
            if (value != null && value.toString().toLowerCase().contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    public List<MemoryEntry> getByTimeRange(MemoryType type, Instant startTime, Instant endTime) {
        return getByType(type).stream()
                .filter(entry -> {
                    Instant timestamp = entry.getCreatedAt();
                    return !timestamp.isBefore(startTime) && !timestamp.isAfter(endTime);
                })
                .sorted(Comparator.comparing(MemoryEntry::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }
    
    public Optional<MemoryEntry> findErrorPattern(String errorMessage, String context) {
        return getByType(MemoryType.ERROR_PATTERN).stream()
                .filter(e -> matchesError(e, errorMessage, context))
                .findFirst();
    }
    
    public void recordErrorResolution(String errorMessage, String context) {
        findErrorPattern(errorMessage, context).ifPresent(entry -> {
            Integer resolvedCount = entry.getMetadataInt("resolvedCount");
            entry.setMetadata("resolvedCount", resolvedCount != null ? resolvedCount + 1 : 1);
            entry.touch();
        });
    }
    
    public List<MemoryEntry> getMostFrequentErrors(int limit) {
        return getByType(MemoryType.ERROR_PATTERN).stream()
                .sorted((a, b) -> {
                    Integer countA = a.getMetadataInt("occurrenceCount");
                    Integer countB = b.getMetadataInt("occurrenceCount");
                    return Integer.compare(countB != null ? countB : 0, countA != null ? countA : 0);
                })
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    public MemoryEntry getLastSession() {
        List<MemoryEntry> sessions = getByType(MemoryType.SESSION_SUMMARY);
        if (sessions.isEmpty()) return null;
        return sessions.stream()
                .max(Comparator.comparing(MemoryEntry::getCreatedAt))
                .orElse(null);
    }
    
    public void prune(MemoryType type, int maxSize, int expiryDays) {
        List<MemoryEntry> memoryList = getByType(type);
        if (memoryList.isEmpty()) return;
        Instant expiry = Instant.now().minus(expiryDays, ChronoUnit.DAYS);
        memoryList.removeIf(entry -> {
            Instant timestamp = entry.getUpdatedAt() != null ? entry.getUpdatedAt() : entry.getCreatedAt();
            return timestamp != null && timestamp.isBefore(expiry) && entry.getAccessCount() < 3;
        });
        if (memoryList.size() > maxSize) {
            List<MemoryEntry> sorted = memoryList.stream()
                    .sorted((a, b) -> {
                        int cmp = Integer.compare(b.getAccessCount(), a.getAccessCount());
                        if (cmp == 0) {
                            return b.getCreatedAt().compareTo(a.getCreatedAt());
                        }
                        return cmp;
                    })
                    .limit(maxSize)
                    .collect(Collectors.toList());
            memoryList.clear();
            memoryList.addAll(sorted);
        }
    }
    
    public void pruneAll(int maxSizePerType, int expiryDays) {
        for (MemoryType type : MemoryType.values()) {
            prune(type, maxSizePerType, expiryDays);
        }
    }
    
    public Map<MemoryType, Integer> getStats() {
        Map<MemoryType, Integer> stats = new HashMap<>();
        for (Map.Entry<MemoryType, List<MemoryEntry>> entry : entries.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        return stats;
    }
    
    public int getTotalCount() {
        return entries.values().stream().mapToInt(List::size).sum();
    }
}
