package io.leavesfly.jimi.tool.core.ask;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 人工交互工具
 * 允许Agent向用户提问并等待反馈
 * <p>
 * 使用场景：
 * - 方案确认：生成技术方案后询问用户是否满意
 * - 获取输入：需要用户提供额外信息
 * - 多选决策：让用户从多个选项中选择
 */
@Slf4j
@Component
public class AskHuman extends AbstractTool<AskHuman.Params> {

    @Autowired
    private HumanInteraction humanInteraction;

    public AskHuman() {
        super("AskHuman",
                "向用户提问并等待反馈。用于需要人工确认、获取用户意见或等待用户输入的场景。" +
                        "支持三种交互类型：confirm(确认方案)、input(自由输入)、choice(多选项选择)。",
                Params.class);
    }


    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("AskHuman tool invoked: type={}, question={}",
                params.getInputType(), truncateForLog(params.getQuestion()));

        String inputType = params.getInputType() != null ? params.getInputType().toLowerCase() : "confirm";

        return switch (inputType) {
            case "confirm" -> humanInteraction.requestConfirmation(params.getQuestion())
                    .map(this::formatConfirmResult);
            case "input" -> humanInteraction.requestInput(params.getQuestion(), params.getDefaultValue())
                    .map(this::formatInputResult);
            case "choice" -> {
                if (params.getChoices() == null || params.getChoices().isEmpty()) {
                    yield Mono.just(ToolResult.error("choice类型需要提供choices选项列表", "缺少选项"));
                }
                yield humanInteraction.requestChoice(params.getQuestion(), params.getChoices())
                        .map(this::formatChoiceResult);
            }
            default ->
                    Mono.just(ToolResult.error("未知的输入类型: " + inputType + "，支持的类型: confirm, input, choice", "参数错误"));
        };
    }

    /**
     * 格式化确认结果
     */
    private ToolResult formatConfirmResult(HumanInputResponse response) {
        return switch (response.getStatus()) {
            case APPROVED -> ToolResult.ok(
                    "用户已确认：满意当前方案，可以继续执行后续步骤。",
                    "用户确认通过",
                    "用户确认通过"
            );
            case NEEDS_MODIFICATION -> ToolResult.ok(
                    "用户反馈需要修改。修改意见：" + response.getContent() +
                            "\n\n请根据用户的修改意见调整方案后，再次使用ask_human工具确认。",
                    "需要修改",
                    "需要修改"
            );
            case REJECTED -> ToolResult.ok(
                    "用户拒绝了当前方案。请重新考虑方案或询问用户具体需求。",
                    "用户拒绝",
                    "用户拒绝"
            );
            default -> ToolResult.ok(
                    "用户输入：" + response.getContent(),
                    "已获取用户反馈",
                    "已获取用户反馈"
            );
        };
    }

    /**
     * 格式化自由输入结果
     */
    private ToolResult formatInputResult(HumanInputResponse response) {
        String content = response.getContent();
        if (content == null || content.isEmpty()) {
            return ToolResult.ok("用户未提供输入。", "无输入", "无输入");
        }
        return ToolResult.ok(
                "用户输入内容：" + content,
                "已获取用户输入",
                "已获取用户输入"
        );
    }

    /**
     * 格式化选择结果
     */
    private ToolResult formatChoiceResult(HumanInputResponse response) {
        String content = response.getContent();
        if (content == null || content.isEmpty()) {
            return ToolResult.ok("用户未做出选择。", "无选择", "无选择");
        }
        return ToolResult.ok(
                "用户选择了：" + content,
                "已获取用户选择",
                "已获取用户选择"
        );
    }

    /**
     * 截断日志输出
     */
    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    /**
     * AskHuman工具参数
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {

        @JsonPropertyDescription("要向用户提出的问题或需要确认的内容。支持Markdown格式。")
        private String question;

        @JsonPropertyDescription("输入类型。confirm: 确认方案(满意/需要修改/拒绝)；input: 自由文本输入；choice: 从选项中选择。默认为confirm。")
        private String inputType;

        @JsonPropertyDescription("选项列表，仅在inputType为choice时需要。例如: [\"方案A\", \"方案B\", \"方案C\"]")
        private List<String> choices;

        @JsonPropertyDescription("默认值，仅在inputType为input时使用。如果用户直接回车，将使用此默认值。")
        private String defaultValue;
    }
}
