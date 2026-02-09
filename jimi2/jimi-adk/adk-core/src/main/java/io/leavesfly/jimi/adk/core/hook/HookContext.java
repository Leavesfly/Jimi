package io.leavesfly.jimi.adk.core.hook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 执行上下文
 * 包含 Hook 执行时的环境信息和数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookContext {
    
    private HookType hookType;
    private Path workDir;
    private String toolName;
    private String toolCallId;
    private String toolResult;
    
    @Builder.Default
    private List<Path> affectedFiles = new ArrayList<>();
    
    private String agentName;
    private String previousAgentName;
    private String errorMessage;
    private String errorStackTrace;
    private String userInput;
    
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    public void addAffectedFile(Path file) {
        affectedFiles.add(file);
    }
    
    public List<String> getAffectedFilePaths() {
        return affectedFiles.stream()
                .map(Path::toString)
                .toList();
    }
}
