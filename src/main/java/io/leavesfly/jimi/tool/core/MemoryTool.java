package io.leavesfly.jimi.tool.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.memory.MemoryManager;
import io.leavesfly.jimi.memory.MemoryStore;
import io.leavesfly.jimi.tool.SyncTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆管理工具
 * <p>
 * 让 Agent 可以主动读写长期记忆，对标 Claude Code 的 Manual write 路径。
 * <p>
 * 支持的操作：
 * <ul>
 *   <li>read - 读取 MEMORY.md 完整内容</li>
 *   <li>write - 覆盖写入指定 section 的内容</li>
 *   <li>append - 向指定 section 追加条目</li>
 *   <li>list_topics - 列出所有 Topic 文件</li>
 *   <li>read_topic - 读取指定 Topic 文件内容</li>
 *   <li>write_topic - 写入 Topic 文件</li>
 * </ul>
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class MemoryTool extends SyncTool<MemoryTool.Params> {

    private static final String NAME = "Memory";
    private static final String DESCRIPTION =
            "管理项目的长期记忆。支持的操作：\n"
            + "- read: 读取当前项目的完整记忆内容（MEMORY.md）\n"
            + "- write: 覆盖写入指定 section 的内容（如 'User Preferences'、'Key Decisions'）\n"
            + "- append: 向指定 section 追加一条记忆条目\n"
            + "- list_topics: 列出所有主题文件\n"
            + "- read_topic: 读取指定主题文件的内容\n"
            + "- write_topic: 写入主题文件\n\n"
            + "当用户要求你'记住'某些偏好、决策或经验时，使用此工具将信息持久化到长期记忆中。";

    private MemoryManager memoryManager;
    private String workDirPath;

    public MemoryTool() {
        super(NAME, DESCRIPTION, Params.class);
    }

    /**
     * 设置运行时依赖
     */
    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    public void setWorkDirPath(String workDirPath) {
        this.workDirPath = workDirPath;
    }

    @Override
    protected ToolResult executeSync(Params params) {
        if (memoryManager == null || workDirPath == null) {
            return ToolResult.error("Memory tool not properly initialized", "初始化失败");
        }

        if (!memoryManager.getConfig().isEnabled()) {
            return ToolResult.error("Memory system is disabled", "记忆系统未启用");
        }

        String action = params.getAction();
        if (action == null || action.isEmpty()) {
            return ToolResult.error("action is required", "缺少 action 参数");
        }

        return switch (action.toLowerCase()) {
            case "read" -> handleRead();
            case "write" -> handleWrite(params);
            case "append" -> handleAppend(params);
            case "list_topics" -> handleListTopics();
            case "read_topic" -> handleReadTopic(params);
            case "write_topic" -> handleWriteTopic(params);
            default -> ToolResult.error(
                    "Unknown action: " + action + ". Supported: read, write, append, list_topics, read_topic, write_topic",
                    "未知操作");
        };
    }

    private ToolResult handleRead() {
        String content = memoryManager.readMemory(workDirPath);
        if (content.isEmpty()) {
            return ToolResult.ok("No memory content found for this project.", "记忆为空");
        }
        return ToolResult.ok(content, "Memory content loaded", "读取记忆");
    }

    private ToolResult handleWrite(Params params) {
        if (params.getSection() == null || params.getSection().isEmpty()) {
            return ToolResult.error("section is required for write action", "缺少 section");
        }
        if (params.getContent() == null) {
            return ToolResult.error("content is required for write action", "缺少 content");
        }

        memoryManager.writeMemory(workDirPath, params.getSection(), params.getContent());
        return ToolResult.ok(
                "Successfully updated section '" + params.getSection() + "'",
                "记忆已更新",
                "更新 " + params.getSection());
    }

    private ToolResult handleAppend(Params params) {
        if (params.getSection() == null || params.getSection().isEmpty()) {
            return ToolResult.error("section is required for append action", "缺少 section");
        }
        if (params.getContent() == null || params.getContent().isEmpty()) {
            return ToolResult.error("content is required for append action", "缺少 content");
        }

        memoryManager.appendMemory(workDirPath, params.getSection(), params.getContent());
        return ToolResult.ok(
                "Successfully appended to section '" + params.getSection() + "': " + params.getContent(),
                "记忆已追加",
                "追加到 " + params.getSection());
    }

    private ToolResult handleListTopics() {
        MemoryStore store = memoryManager.getOrCreateStore(workDirPath);
        List<String> topics = store.listTopics();
        if (topics.isEmpty()) {
            return ToolResult.ok("No topic files found.", "无主题文件");
        }
        String topicList = String.join("\n", topics.stream()
                .map(t -> "- " + t)
                .toList());
        return ToolResult.ok(topicList, "Found " + topics.size() + " topics", "列出主题");
    }

    private ToolResult handleReadTopic(Params params) {
        if (params.getTopicName() == null || params.getTopicName().isEmpty()) {
            return ToolResult.error("topic_name is required for read_topic action", "缺少 topic_name");
        }

        MemoryStore store = memoryManager.getOrCreateStore(workDirPath);
        String content = store.readTopic(params.getTopicName());
        if (content.isEmpty()) {
            return ToolResult.ok("Topic '" + params.getTopicName() + "' not found or empty.", "主题不存在");
        }
        return ToolResult.ok(content, "Topic loaded: " + params.getTopicName(), "读取主题");
    }

    private ToolResult handleWriteTopic(Params params) {
        if (params.getTopicName() == null || params.getTopicName().isEmpty()) {
            return ToolResult.error("topic_name is required for write_topic action", "缺少 topic_name");
        }
        if (params.getContent() == null) {
            return ToolResult.error("content is required for write_topic action", "缺少 content");
        }

        MemoryStore store = memoryManager.getOrCreateStore(workDirPath);
        store.writeTopic(params.getTopicName(), params.getContent());
        return ToolResult.ok(
                "Successfully written topic: " + params.getTopicName(),
                "主题已写入",
                "写入主题 " + params.getTopicName());
    }

    @Override
    public boolean isConcurrentSafe() {
        return false;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {

        @JsonProperty("action")
        @JsonPropertyDescription("操作类型：read（读取记忆）、write（覆盖写入 section）、append（追加条目）、list_topics（列出主题）、read_topic（读取主题）、write_topic（写入主题）")
        private String action;

        @JsonProperty("section")
        @JsonPropertyDescription("记忆区域名称，用于 write/append 操作。常用值：'User Preferences'、'Key Decisions'、'Lessons Learned'、'Project Overview'")
        private String section;

        @JsonProperty("content")
        @JsonPropertyDescription("要写入或追加的内容")
        private String content;

        @JsonProperty("topic_name")
        @JsonPropertyDescription("主题文件名称（不含 .md 后缀），用于 read_topic/write_topic 操作")
        private String topicName;
    }
}
