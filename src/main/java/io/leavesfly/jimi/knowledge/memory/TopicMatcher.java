package io.leavesfly.jimi.knowledge.memory;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Topic 文件匹配器
 * <p>
 * 对标 Claude Code 的 Layer 2 读取路径：根据用户输入按需加载相关 Topic 文件。
 * <p>
 * 匹配策略（基于关键词，不依赖 LLM）：
 * <ol>
 *   <li>将 Topic 文件名按 '-' 分割为关键词</li>
 *   <li>将用户输入转为小写后，检查是否包含 Topic 关键词</li>
 *   <li>返回匹配度最高的 Topic 列表</li>
 * </ol>
 */
@Slf4j
public class TopicMatcher {

    private final MemoryStore store;

    public TopicMatcher(MemoryStore store) {
        this.store = store;
    }

    /**
     * 根据用户输入匹配相关的 Topic 文件
     *
     * @param userInput 用户输入文本
     * @param maxTopics 最多返回的 Topic 数量
     * @return 匹配到的 Topic 内容列表（已拼接为可注入的格式）
     */
    public List<MatchedTopic> match(String userInput, int maxTopics) {
        if (userInput == null || userInput.isEmpty()) {
            return List.of();
        }

        List<String> allTopics = store.listTopics();
        if (allTopics.isEmpty()) {
            return List.of();
        }

        String inputLower = userInput.toLowerCase();
        List<MatchedTopic> scored = new ArrayList<>();

        for (String topicName : allTopics) {
            int score = calculateMatchScore(topicName, inputLower);
            if (score > 0) {
                scored.add(new MatchedTopic(topicName, score));
            }
        }

        // 按匹配度降序排列，取前 maxTopics 个
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<MatchedTopic> result = scored.stream()
                .limit(maxTopics)
                .toList();

        if (!result.isEmpty()) {
            log.debug("Matched {} topics for user input: {}",
                    result.size(), result.stream().map(MatchedTopic::topicName).toList());
        }

        return result;
    }

    /**
     * 加载匹配到的 Topic 内容，格式化为可注入上下文的文本
     *
     * @param matchedTopics 匹配到的 Topic 列表
     * @return 格式化后的 Topic 内容，如果无匹配则返回空字符串
     */
    public String loadMatchedTopics(List<MatchedTopic> matchedTopics) {
        if (matchedTopics.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("## Relevant Memory Topics\n\n");

        for (MatchedTopic matched : matchedTopics) {
            String content = store.readTopic(matched.topicName());
            if (!content.isEmpty()) {
                builder.append("### ").append(formatTopicName(matched.topicName())).append("\n\n");
                builder.append(content).append("\n\n");
            }
        }

        return builder.toString().trim();
    }

    /**
     * 计算 Topic 名称与用户输入的匹配分数
     */
    private int calculateMatchScore(String topicName, String inputLower) {
        // 将 topic-name 分割为关键词
        String[] keywords = topicName.toLowerCase().split("[-_]");
        int score = 0;

        for (String keyword : keywords) {
            if (keyword.length() < 2) {
                continue;
            }
            if (inputLower.contains(keyword)) {
                score += keyword.length();
            }
        }

        return score;
    }

    /**
     * 格式化 Topic 名称为可读标题
     * 例如：project-structure → Project Structure
     */
    private String formatTopicName(String topicName) {
        String[] parts = topicName.split("[-_]");
        StringBuilder formatted = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (!formatted.isEmpty()) {
                    formatted.append(" ");
                }
                formatted.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    formatted.append(part.substring(1));
                }
            }
        }
        return formatted.toString();
    }

    /**
     * 匹配到的 Topic 记录
     */
    public record MatchedTopic(String topicName, int score) {}
}
