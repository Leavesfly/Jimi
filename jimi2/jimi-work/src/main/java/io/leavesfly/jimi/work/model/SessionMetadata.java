package io.leavesfly.jimi.work.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话元数据 - 用于持久化和恢复会话
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionMetadata {

    private String id;
    private String workDir;
    private String agentName;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;

    /**
     * 从 WorkSession 创建元数据
     */
    public static SessionMetadata fromWorkSession(WorkSession session) {
        return SessionMetadata.builder()
                .id(session.getId())
                .workDir(session.getWorkDir().toString())
                .agentName(session.getAgentName())
                .createdAt(session.getCreatedAt())
                .lastAccessedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 获取显示名称
     */
    public String getDisplayName() {
        String dirName = workDir;
        int lastSep = dirName.lastIndexOf('/');
        if (lastSep < 0) lastSep = dirName.lastIndexOf('\\');
        if (lastSep >= 0) dirName = dirName.substring(lastSep + 1);
        return dirName + " (" + agentName + ")";
    }
}
