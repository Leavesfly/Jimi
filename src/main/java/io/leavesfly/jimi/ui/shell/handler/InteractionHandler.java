package io.leavesfly.jimi.ui.shell.handler;

import io.leavesfly.jimi.tool.core.ask.HumanInputRequest;
import io.leavesfly.jimi.tool.core.ask.HumanInputResponse;
import io.leavesfly.jimi.core.approval.ApprovalRequest;
import io.leavesfly.jimi.core.approval.ApprovalResponse;
import io.leavesfly.jimi.ui.shell.output.AssistantTextRenderer;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.List;

/**
 * 人机交互处理器
 * 负责审批请求和自由人工输入两类阻塞式交互
 */
@Slf4j
public class InteractionHandler {

    private final Terminal terminal;
    private final OutputFormatter outputFormatter;
    private final LineReader lineReader;
    private final AssistantTextRenderer renderer;

    public InteractionHandler(Terminal terminal,
                              OutputFormatter outputFormatter,
                              LineReader lineReader,
                              AssistantTextRenderer renderer) {
        this.terminal = terminal;
        this.outputFormatter = outputFormatter;
        this.lineReader = lineReader;
        this.renderer = renderer;
    }

    /**
     * 处理审批请求（在 Wire 订阅线程中调用，直接阻塞等待用户输入）
     */
    public void handleApprovalRequest(ApprovalRequest request) {
        try {
            log.info("[InteractionHandler] Processing approval request for action: {}", request.getAction());

            renderer.flushLineIfNeeded();

            terminal.writer().println();
            terminal.flush();
            outputFormatter.printStatus("⚠️  需要审批:");
            outputFormatter.printInfo("  操作类型: " + request.getAction());
            outputFormatter.printInfo("  操作描述: " + request.getDescription());
            terminal.writer().println();
            terminal.flush();

            String prompt = new AttributedString(
                    "❓ 是否批准？[y/n/a] (y=批准, n=拒绝, a=本次会话全部批准): ",
                    AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold())
                    .toAnsi();

            int maxRetries = 3;
            String response = "";
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                response = lineReader.readLine(prompt).trim().toLowerCase();
                if (!response.isEmpty()) {
                    break;
                }
                log.warn("[InteractionHandler] Received empty input for approval (attempt {}/{}), retrying...",
                        attempt + 1, maxRetries);
            }

            log.debug("[InteractionHandler] Approval input received: '{}'", response);

            ApprovalResponse approvalResponse = switch (response) {
                case "y", "yes" -> {
                    outputFormatter.printSuccess("✅ 已批准");
                    yield ApprovalResponse.APPROVE;
                }
                case "a", "all" -> {
                    outputFormatter.printSuccess("✅ 已批准（本次会话全部同类操作）");
                    yield ApprovalResponse.APPROVE_FOR_SESSION;
                }
                case "n", "no" -> {
                    outputFormatter.printError("❌ 已拒绝");
                    yield ApprovalResponse.REJECT;
                }
                default -> {
                    if (response.isEmpty()) {
                        log.warn("[InteractionHandler] All {} retry attempts returned empty input, rejecting by default", maxRetries);
                    } else {
                        log.info("[InteractionHandler] Unrecognized input '{}', treating as reject", response);
                    }
                    outputFormatter.printError("❌ 已拒绝");
                    yield ApprovalResponse.REJECT;
                }
            };

            terminal.writer().println();
            terminal.flush();
            request.resolve(approvalResponse);
            log.info("[InteractionHandler] Approval request resolved: {}", approvalResponse);

        } catch (UserInterruptException e) {
            log.info("Approval interrupted by user");
            outputFormatter.printError("❌ 审批已取消");
            request.resolve(ApprovalResponse.REJECT);
        } catch (Exception e) {
            log.error("Error handling approval request", e);
            request.resolve(ApprovalResponse.REJECT);
        }
    }

    /**
     * 处理人工交互请求（显示问题并等待用户输入）
     */
    public void handleHumanInputRequest(HumanInputRequest request) {
        try {
            renderer.flushLineIfNeeded();

            terminal.writer().println();
            outputFormatter.printStatus("🤔 Agent 需要您的反馈:");
            outputFormatter.printInfo(request.getQuestion());
            terminal.writer().println();
            terminal.flush();

            HumanInputResponse response;

            switch (request.getInputType()) {
                case CONFIRM -> {
                    String prompt = new AttributedString(
                            "❓ 请选择 [y=满意继续 / m=需要修改 / n=拒绝]: ",
                            AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold())
                            .toAnsi();
                    String input = lineReader.readLine(prompt).trim().toLowerCase();

                    response = switch (input) {
                        case "y", "yes", "满意" -> {
                            outputFormatter.printSuccess("✅ 已确认");
                            yield HumanInputResponse.approved();
                        }
                        case "m", "modify", "修改" -> {
                            String modPrompt = new AttributedString(
                                    "📝 请输入修改意见: ",
                                    AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
                                    .toAnsi();
                            String modification = lineReader.readLine(modPrompt);
                            outputFormatter.printInfo("💬 已记录修改意见");
                            yield HumanInputResponse.needsModification(modification);
                        }
                        default -> {
                            outputFormatter.printError("❌ 已拒绝");
                            yield HumanInputResponse.rejected();
                        }
                    };
                }
                case FREE_INPUT -> {
                    String defaultHint = request.getDefaultValue() != null
                            ? " (默认: " + request.getDefaultValue() + ")" : "";
                    String prompt = new AttributedString(
                            "📝 请输入" + defaultHint + ": ",
                            AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
                            .toAnsi();
                    String input = lineReader.readLine(prompt).trim();
                    if (input.isEmpty() && request.getDefaultValue() != null) {
                        input = request.getDefaultValue();
                    }
                    outputFormatter.printInfo("✅ 已记录输入");
                    response = HumanInputResponse.inputProvided(input);
                }
                case CHOICE -> {
                    List<String> choices = request.getChoices();
                    if (choices != null && !choices.isEmpty()) {
                        outputFormatter.printInfo("请从以下选项中选择:");
                        for (int i = 0; i < choices.size(); i++) {
                            outputFormatter.printInfo("  " + (i + 1) + ". " + choices.get(i));
                        }
                        String prompt = new AttributedString(
                                "👉 请输入选项序号: ",
                                AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
                                .toAnsi();
                        String input = lineReader.readLine(prompt).trim();
                        try {
                            int index = Integer.parseInt(input) - 1;
                            if (index >= 0 && index < choices.size()) {
                                String selected = choices.get(index);
                                outputFormatter.printSuccess("✅ 已选择: " + selected);
                                response = HumanInputResponse.inputProvided(selected);
                            } else {
                                outputFormatter.printError("❌ 无效的选项序号");
                                response = HumanInputResponse.rejected();
                            }
                        } catch (NumberFormatException e) {
                            outputFormatter.printError("❌ 请输入有效的序号");
                            response = HumanInputResponse.rejected();
                        }
                    } else {
                        outputFormatter.printError("❌ 没有可用的选项");
                        response = HumanInputResponse.rejected();
                    }
                }
                default -> {
                    outputFormatter.printError("❌ 未知的输入类型");
                    response = HumanInputResponse.rejected();
                }
            }

            terminal.writer().println();
            terminal.flush();
            request.resolve(response);
            log.info("[InteractionHandler] Human input request resolved: {}", response.getStatus());

        } catch (UserInterruptException e) {
            log.info("Human input interrupted by user");
            outputFormatter.printError("❌ 交互已取消");
            request.resolve(HumanInputResponse.rejected());
        } catch (Exception e) {
            log.error("Error handling human input request", e);
            request.resolve(HumanInputResponse.rejected());
        }
    }
}
