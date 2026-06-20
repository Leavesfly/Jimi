package io.leavesfly.jimi.core.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.info.LoopEngineeringConfig;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.LLMFactory;
import io.leavesfly.jimi.llm.message.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

/**
 * 独立目标验证器
 * <p>
 * 使用独立的 LLM 实例评估目标条件是否已满足。
 * 关键设计：验证者和执行者分离，避免"自己批改自己作业"。
 * <p>
 * 验证者会：
 * 1. 使用配置中指定的模型（或默认模型）
 * 2. 使用专门的验证 system prompt
 * 3. 仅评估条件是否满足，返回 JSON 结构化结果
 */
@Slf4j
@Service
public class GoalVerifier {

    private static final String VERIFIER_SYSTEM_PROMPT = """
            你是一个严格的目标条件验证者。你的唯一任务是评估给定的目标条件是否已经被满足。
            
            规则：
            1. 只基于提供的"当前状态"信息进行判断
            2. 不要猜测、不要假设、不要编造信息
            3. 如果信息不足以判断，回答"未满足"并说明缺少什么信息
            4. 返回严格的 JSON 格式，不要包含其他内容
            
            返回格式：
            {"satisfied": true/false, "reason": "简短的判断理由"}
            """;

    @Autowired
    private LLMFactory llmFactory;

    @Autowired
    private LoopEngineeringConfig config;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 验证目标条件是否满足
     *
     * @param goalCondition 目标条件描述
     * @param currentState  当前工作状态描述
     * @return 验证结果
     */
    public Mono<GoalVerification> verify(String goalCondition, String currentState) {
        return Mono.fromCallable(() -> {
            try {
                LLM verifierLLM = getVerifierLLM();
                if (verifierLLM == null) {
                    log.warn("Verifier LLM not available, assuming not satisfied");
                    return GoalVerification.notSatisfied("验证器 LLM 不可用");
                }

                String userPrompt = String.format("""
                        请评估以下目标条件是否已经满足。
                        
                        ## 目标条件
                        %s
                        
                        ## 当前状态
                        %s
                        
                        请返回 JSON 格式的评估结果。
                        """, goalCondition, currentState);

                // 构建消息列表
                Message userMessage = Message.user(userPrompt);

                // 调用 LLM（使用 generate 接口：systemPrompt + history + tools）
                ChatCompletionResult result = verifierLLM.getChatProvider()
                        .generate(VERIFIER_SYSTEM_PROMPT, List.of(userMessage), Collections.emptyList())
                        .block();

                String response = (result != null && result.getMessage() != null)
                        ? result.getMessage().getTextContent()
                        : null;

                if (response == null || response.isBlank()) {
                    return GoalVerification.notSatisfied("验证器无响应");
                }

                return parseVerification(response);

            } catch (Exception e) {
                log.error("Goal verification failed: {}", e.getMessage());
                return GoalVerification.notSatisfied("验证失败: " + e.getMessage());
            }
        });
    }

    // ==================== 内部方法 ====================

    /**
     * 获取验证者 LLM 实例
     * 如果配置了专门的验证模型则使用，否则回退到默认模型
     */
    private LLM getVerifierLLM() {
        String verifierModel = config.getGoalVerifierModel();
        if (verifierModel != null && !verifierModel.isEmpty()) {
            return llmFactory.getOrCreateLLM(verifierModel);
        }
        // 回退到默认模型
        return llmFactory.getOrCreateLLM(null);
    }

    /**
     * 解析 LLM 返回的验证结果 JSON
     */
    private GoalVerification parseVerification(String response) {
        try {
            // 尝试从响应中提取 JSON
            String json = extractJson(response);
            JsonNode node = objectMapper.readTree(json);

            boolean satisfied = node.has("satisfied") && node.get("satisfied").asBoolean(false);
            String reason = node.has("reason") ? node.get("reason").asText("") : "";

            return GoalVerification.builder()
                    .satisfied(satisfied)
                    .reason(reason)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse verification response: {}", response);
            // 解析失败时保守处理：假设未满足
            return GoalVerification.notSatisfied("验证响应解析失败: " + response);
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 部分
     * 处理可能包含 markdown code fence 的情况
     */
    private String extractJson(String response) {
        String trimmed = response.trim();

        // 处理 ```json ... ``` 格式
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        // 处理 ``` ... ``` 格式
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("\n") + 1;
            int end = trimmed.lastIndexOf("```");
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        // 尝试直接找到 JSON 对象
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
    }
}
