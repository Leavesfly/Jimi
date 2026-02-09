package io.leavesfly.jimi.adk.tools.extended;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.interaction.HumanInputResponse;
import io.leavesfly.jimi.adk.api.interaction.HumanInteraction;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.tools.base.AbstractTool;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 人工交互工具
 * 允许 Agent 向用户提问并等待反馈
 *
 * 使用场景：
 * - 方案确认：生成技术方案后询问用户是否满意
 * - 获取输入：需要用户提供额外信息
 * - 多选决策：让用户从多个选项中选择
 */
@Slf4j
public class AskHumanTool extends AbstractTool<AskHumanTool.Params> {

    private final HumanInteraction humanInteraction;

    /**
     * AskHuman 工具参数
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {

        @JsonPropertyDescription("要向用户提出的问题或需要确认的内容。支持 Markdown 格式。")
        private String question;

        @JsonPropertyDescription("输入类型。confirm: 确认方案(满意/需要修改/拒绝)；input: 自由文本输入；choice: 从选项中选择。默认为 confirm。")
        private String inputType;

        @JsonPropertyDescription("选项列表，仅在 inputType 为 choice 时需要。例如: [\"方案A\", \"方案B\", \"方案C\"]")
        private List<String> choices;

        @JsonPropertyDescription("默认值，仅在 inputType 为 input 时使用。如果用户直接回车，将使用此默认值。")
        private String defaultValue;
    }

    public AskHumanTool(Runtime runtime) {
        super("AskHuman",
              "向用户提问并等待反馈。用于需要人工确认、获取用户意见或等待用户输入的场景。" +
              "支持三种交互类型：confirm(确认方案)、input(自由输入)、choice(多选项选择)。",
              Params.class);
        this.humanInteraction = runtime.getHumanInteraction();
    }

    @Override
    protected Mono<ToolResult> doExecute(Params params) {
        if (humanInteraction == null) {
            return Mono.just(ToolResult.error("HumanInteraction not available in current runtime"));
        }

        if (params.getQuestion() == null || params.getQuestion().isBlank()) {
            return Mono.just(ToolResult.error("Question is required"));
        }

        log.info("AskHuman tool invoked: type={}, question={}",
                params.getInputType(), truncateForLog(params.getQuestion()));

        String inputType = params.getInputType() != null ? params.getInputType().toLowerCase() : "confirm";

        return Mono.fromCallable(() -> {
            switch (inputType) {
                case "confirm":
                    return formatConfirmResult(humanInteraction.requestConfirmation(params.getQuestion()));
                case "input":
                    return formatInputResult(humanInteraction.requestInput(params.getQuestion(), params.getDefaultValue()));
                case "choice": {
                    if (params.getChoices() == null || params.getChoices().isEmpty()) {
                        return ToolResult.error("choice 类型需要提供 choices 选项列表", "缺少选项");
                    }
                    String[] choiceArray = params.getChoices().toArray(new String[0]);
                    return formatChoiceResult(humanInteraction.requestChoice(params.getQuestion(), choiceArray));
                }
                default:
                    return ToolResult.error("未知的输入类型: " + inputType + "，支持的类型: confirm, input, choice");
            }
        });
    }

    /**
     * 格式化确认结果
     */
    private ToolResult formatConfirmResult(HumanInputResponse response) {
        return switch (response.getStatus()) {
            case APPROVED -> ToolResult.success(
                    "用户已确认：满意当前方案，可以继续执行后续步骤。"
            );
            case NEEDS_MODIFICATION -> ToolResult.success(
                    "用户反馈需要修改。修改意见：" + response.getContent() +
                    "\n\n请根据用户的修改意见调整方案后，再次使用 AskHuman 工具确认。"
            );
            case REJECTED -> ToolResult.success(
                    "用户拒绝了当前方案。请重新考虑方案或询问用户具体需求。"
            );
            default -> ToolResult.success(
                    "用户输入：" + response.getContent()
            );
        };
    }

    /**
     * 格式化自由输入结果
     */
    private ToolResult formatInputResult(HumanInputResponse response) {
        String content = response.getContent();
        if (content == null || content.isEmpty()) {
            return ToolResult.success("用户未提供输入。");
        }
        return ToolResult.success("用户输入内容：" + content);
    }

    /**
     * 格式化选择结果
     */
    private ToolResult formatChoiceResult(HumanInputResponse response) {
        String content = response.getContent();
        if (content == null || content.isEmpty()) {
            return ToolResult.success("用户未做出选择。");
        }
        return ToolResult.success("用户选择了：" + content);
    }

    /**
     * 截断日志输出
     */
    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
