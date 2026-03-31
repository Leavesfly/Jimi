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
 * 包含 Hook 执行时的环境信息和数据。
 * 对齐 Claude Code 标准：
 * - 支持 JSON 格式的 stdin 输入传递给 hook 脚本
 * - 支持 exit code 决策控制（0=允许, 2=阻塞）
 * - 支持 stdout JSON 输出解析
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
     * 会话 ID
     */
    private String sessionId;

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
     * 工具输入参数 (对齐 Claude Code: tool_input)
     */
    @Builder.Default
    private Map<String, Object> toolInput = new HashMap<>();

    /**
     * 工具执行结果 (对于 POST_TOOL_USE)
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
     * 错误信息 (对于 ON_ERROR / POST_TOOL_USE_FAILURE)
     */
    private String errorMessage;

    /**
     * 错误堆栈 (对于 ON_ERROR)
     */
    private String errorStackTrace;

    /**
     * 用户输入 (对于 USER_PROMPT_SUBMIT)
     */
    private String userInput;

    /**
     * 通知消息 (对于 NOTIFICATION)
     */
    private String notificationMessage;

    /**
     * 通知类型 (对于 NOTIFICATION)
     */
    private String notificationType;

    /**
     * 最后一条助手消息 (对于 STOP)
     */
    private String lastAssistantMessage;

    /**
     * Stop hook 是否已激活 (对于 STOP，防止无限循环)
     */
    @Builder.Default
    private boolean stopHookActive = false;

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

    /**
     * 构建传递给 hook 脚本的 JSON 输入（对齐 Claude Code stdin JSON 格式）
     */
    public Map<String, Object> toStdinJson() {
        Map<String, Object> json = new HashMap<>();
        json.put("hook_event_name", hookType != null ? hookType.name() : null);
        json.put("cwd", workDir != null ? workDir.toString() : null);

        if (sessionId != null) {
            json.put("session_id", sessionId);
        }
        if (toolName != null) {
            json.put("tool_name", toolName);
        }
        if (toolCallId != null) {
            json.put("tool_use_id", toolCallId);
        }
        if (toolInput != null && !toolInput.isEmpty()) {
            json.put("tool_input", toolInput);
        }
        if (toolResult != null) {
            json.put("tool_response", toolResult);
        }
        if (agentName != null) {
            json.put("agent_name", agentName);
        }
        if (previousAgentName != null) {
            json.put("previous_agent", previousAgentName);
        }
        if (errorMessage != null) {
            json.put("error", errorMessage);
        }
        if (userInput != null) {
            json.put("prompt", userInput);
        }
        if (notificationMessage != null) {
            json.put("message", notificationMessage);
        }
        if (notificationType != null) {
            json.put("notification_type", notificationType);
        }
        if (lastAssistantMessage != null) {
            json.put("last_assistant_message", lastAssistantMessage);
        }
        json.put("stop_hook_active", stopHookActive);

        return json;
    }
}
