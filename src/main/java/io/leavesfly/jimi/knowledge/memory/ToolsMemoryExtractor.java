package io.leavesfly.jimi.knowledge.memory;

import io.leavesfly.jimi.config.info.MemoryConfig;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * 记忆提取器
 * 从工具执行结果中提取有价值的知识
 */
@Slf4j
@Component
public class ToolsMemoryExtractor {

    @Autowired
    private final MemoryManager memoryManager;

    @Autowired
    private final MemoryConfig config;

    public ToolsMemoryExtractor(MemoryManager memoryManager, MemoryConfig config) {
        this.memoryManager = memoryManager;
        this.config = config;
    }

    /**
     * 获取MemoryManager实例（供外部调用）
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 从工具执行结果提取知识
     *
     * @param result   工具执行结果
     * @param toolName 工具名称
     * @return 完成的 Mono
     */
    public Mono<Void> extractFromToolResult(ToolResult result, String toolName) {
        // 检查是否启用且设置为自动提取
        if (!config.isLongTermEnabled() || !config.isAutoExtract()) {
            return Mono.empty();
        }

        // 只处理成功的结果
        if (!result.isOk() || result.getOutput() == null || result.getOutput().isEmpty()) {
            return Mono.empty();
        }

        return Mono.defer(() -> {
            // 根据工具类型分类
            String category = categorizeByTool(toolName);

            // 如果是低价值工具，跳过提取
            if (category.equals("skip")) {
                return Mono.empty();
            }

            // 提取和摘要内容
            String content = summarize(result.getOutput(), 200);

            // 创建知识条目
            ProjectInsight insight = ProjectInsight.builder()
                    .id("insight_" + System.currentTimeMillis())
                    .category(category)
                    .timestamp(Instant.now())
                    .content(content)
                    .source("tool_execution:" + toolName)
                    .confidence(calculateConfidence(toolName, result))
                    .accessCount(0)
                    .lastAccessed(Instant.now())
                    .build();

            // 保存到记忆管理器
            return memoryManager.addInsight(insight);
        });
    }

    /**
     * 根据工具名称分类知识
     *
     * @param toolName 工具名称
     * @return 分类名称
     */
    private String categorizeByTool(String toolName) {
        return switch (toolName) {
            case "read_file", "list_dir" -> "code_structure";
            case "search_codebase", "search_symbol" -> "architecture";
            case "grep_code", "search_file" -> "code_search";
            case "run_in_terminal" -> "execution";
            case "write", "create_file" -> "code_creation";
            case "search_replace" -> "code_modification";
            case "Task" -> "subagent_task";

            // 低价值工具，跳过提取
            case "think", "add_tasks", "update_tasks" -> "skip";

            default -> "general";
        };
    }

    /**
     * 摘要化内容
     *
     * @param text      原始文本
     * @param maxLength 最大长度
     * @return 摘要文本
     */
    private String summarize(String text, int maxLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // 移除多余空白
        String cleaned = text.trim().replaceAll("\\s+", " ");

        if (cleaned.length() <= maxLength) {
            return cleaned;
        }

        // 截断并添加省略号
        return cleaned.substring(0, maxLength) + "...";
    }

    /**
     * 计算知识置信度
     *
     * @param toolName 工具名称
     * @param result   工具结果
     * @return 置信度 (0.0-1.0)
     */
    private double calculateConfidence(String toolName, ToolResult result) {
        // 基础置信度
        double confidence = 0.7;

        // 根据工具类型调整
        confidence = switch (toolName) {
            case "search_codebase", "search_symbol" -> 0.9;  // 代码搜索结果可信度高
            case "read_file", "list_dir" -> 0.85;           // 文件读取结果准确
            case "run_in_terminal" -> 0.8;                  // 执行结果真实
            case "Task" -> 0.75;                            // Subagent结果需验证
            default -> 0.7;
        };

        // 根据输出长度调整（太短可能信息不足）
        int outputLength = result.getOutput().length();
        if (outputLength < 50) {
            confidence *= 0.8;
        } else if (outputLength > 500) {
            confidence *= 1.1;
            confidence = Math.min(confidence, 1.0);
        }

        return Math.max(0.5, Math.min(1.0, confidence));
    }
}
