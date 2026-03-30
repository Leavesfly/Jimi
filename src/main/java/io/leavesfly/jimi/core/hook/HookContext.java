package io.leavesfly.jimi.core.hook;

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
 * 
 * 包含 Hook 执行时的环境信息和数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookContext {
    
    /**
     * Hook 类型
     */
    private HookType hookType;
    
    /**
     * 工作目录
     */
    private Path workDir;
    
    /**
     * 触发的工具名称 (对于工具调用 Hook)
     */
    private String toolName;
    
    /**
     * 工具调用 ID (对于工具调用 Hook)
     */
    private String toolCallId;
    
    /**
     * 工具执行结果 (对于 POST_TOOL_CALL)
     */
    private String toolResult;
    
    /**
     * 受影响的文件列表 (对于文件操作工具)
     */
    @Builder.Default
    private List<Path> affectedFiles = new ArrayList<>();
    
    /**
     * 切换的 Agent 名称 (对于 Agent 切换 Hook)
     */
    private String agentName;
    
    /**
     * 前一个 Agent 名称 (对于 POST_AGENT_SWITCH)
     */
    private String previousAgentName;
    
    /**
     * 错误信息 (对于 ON_ERROR)
     */
    private String errorMessage;
    
    /**
     * 错误堆栈 (对于 ON_ERROR)
     */
    private String errorStackTrace;
    
    /**
     * 用户输入 (对于用户输入 Hook)
     */
    private String userInput;
    
    /**
     * 额外的上下文数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 添加元数据
     */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    /**
     * 获取元数据
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    /**
     * 添加受影响的文件
     */
    public void addAffectedFile(Path file) {
        affectedFiles.add(file);
    }
    
    /**
     * 获取受影响文件的路径字符串列表
     */
    public List<String> getAffectedFilePaths() {
        return affectedFiles.stream()
                .map(Path::toString)
                .toList();
    }
}
