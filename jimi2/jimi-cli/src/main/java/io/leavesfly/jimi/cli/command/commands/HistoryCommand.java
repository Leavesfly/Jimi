package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

import java.util.List;

/**
 * History 命令 - 显示对话历史
 *
 * @author Jimi2 Team
 */
public class HistoryCommand implements Command {

    private final Context adkContext;

    public HistoryCommand(Context adkContext) {
        this.adkContext = adkContext;
    }

    @Override
    public String getName() {
        return "history";
    }

    @Override
    public String getDescription() {
        return "Show conversation history";
    }

    @Override
    public String getCategory() {
        return "general";
    }

    @Override
    public String getUsage() {
        return "/history [limit]";
    }

    @Override
    public void execute(CommandContext context) {
        var output = context.getOutput();

        if (adkContext == null) {
            output.warn("Context not available");
            return;
        }

        List<Message> history = adkContext.getHistory();
        if (history.isEmpty()) {
            output.info("No conversation history");
            return;
        }

        int limit = 10; // 默认显示最近 10 条
        if (context.getArgCount() > 0) {
            try {
                limit = Integer.parseInt(context.getArg(0));
            } catch (NumberFormatException e) {
                output.error("Invalid limit: " + context.getArg(0));
                return;
            }
        }

        output.title("Conversation History (last " + limit + " messages)");
        output.separator();

        int start = Math.max(0, history.size() - limit);
        for (int i = start; i < history.size(); i++) {
            Message msg = history.get(i);
            String role = msg.getRole().name();
            String content = truncate(getMessageContent(msg), 100);
            output.println(String.format("[%d] %s: %s", i + 1, role, content));
        }

        output.println("");
        output.info("Total messages: " + history.size());
    }

    private String getMessageContent(Message message) {
        if (message.getContent() instanceof String) {
            return (String) message.getContent();
        }
        return message.getContent().toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
