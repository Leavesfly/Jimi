package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.memory.MemoryManager;
import io.leavesfly.jimi.memory.MemorySearcher;
import io.leavesfly.jimi.memory.MemoryStore;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * /memory 命令处理器
 * <p>
 * 提供记忆系统的终端管理界面，支持以下子命令：
 * <ul>
 *   <li>/memory - 显示当前记忆摘要</li>
 *   <li>/memory read - 读取完整 MEMORY.md</li>
 *   <li>/memory topics - 列出所有 Topic 文件</li>
 *   <li>/memory search &lt;query&gt; - 搜索历史会话记录</li>
 *   <li>/memory write &lt;section&gt; &lt;content&gt; - 写入记忆</li>
 *   <li>/memory clear - 清空记忆</li>
 * </ul>
 */
@Slf4j
@Component
public class MemoryCommandHandler implements CommandHandler {

    @Autowired
    private MemoryManager memoryManager;

    @Override
    public String getName() {
        return "memory";
    }

    @Override
    public String getDescription() {
        return "管理项目长期记忆";
    }

    @Override
    public List<String> getAliases() {
        return List.of("mem");
    }

    @Override
    public String getUsage() {
        return "/memory [read|topics|search <query>|write <section> <content>|clear]";
    }

    @Override
    public String getCategory() {
        return "knowledge";
    }

    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        if (!memoryManager.getConfig().isEnabled()) {
            out.printWarning("记忆系统未启用。请在 config.yml 中设置 memory.enabled: true");
            return;
        }

        String workDirPath = getWorkDirPath(context);
        if (workDirPath == null) {
            out.printError("无法获取工作目录");
            return;
        }

        String subCommand = context.getArg(0);
        if (subCommand == null || subCommand.isEmpty()) {
            showMemorySummary(out, workDirPath);
            return;
        }

        switch (subCommand.toLowerCase()) {
            case "read" -> showFullMemory(out, workDirPath);
            case "topics" -> showTopics(out, workDirPath);
            case "search" -> searchHistory(out, context, workDirPath);
            case "write" -> writeMemory(out, context, workDirPath);
            case "clear" -> clearMemory(out, workDirPath);
            case "help" -> showHelp(out);
            default -> {
                out.printError("未知子命令: " + subCommand);
                showHelp(out);
            }
        }
    }

    /**
     * 显示记忆摘要（默认行为）
     */
    private void showMemorySummary(OutputFormatter out, String workDirPath) {
        out.println();
        out.printSuccess("📝 项目记忆摘要");
        out.println();

        String memory = memoryManager.readMemory(workDirPath);
        if (memory.isEmpty()) {
            out.println("  暂无记忆内容。使用 /memory write <section> <content> 添加记忆。");
        } else {
            // 显示摘要（前 500 字符）
            String summary = memory.length() > 500
                    ? memory.substring(0, 500) + "\n  ... (使用 /memory read 查看完整内容)"
                    : memory;
            out.println("  " + summary.replace("\n", "\n  "));
        }

        // 显示 Topic 数量
        MemoryStore store = memoryManager.getOrCreateStore(workDirPath);
        List<String> topics = store.listTopics();
        if (!topics.isEmpty()) {
            out.println();
            out.println("  📂 Topic 文件: " + topics.size() + " 个");
            topics.forEach(t -> out.println("    - " + t));
        }

        out.println();
    }

    /**
     * 显示完整 MEMORY.md 内容
     */
    private void showFullMemory(OutputFormatter out, String workDirPath) {
        out.println();
        String memory = memoryManager.readMemory(workDirPath);
        if (memory.isEmpty()) {
            out.println("暂无记忆内容。");
        } else {
            out.println(memory);
        }
        out.println();
    }

    /**
     * 列出所有 Topic 文件
     */
    private void showTopics(OutputFormatter out, String workDirPath) {
        out.println();
        MemoryStore store = memoryManager.getOrCreateStore(workDirPath);
        List<String> topics = store.listTopics();

        if (topics.isEmpty()) {
            out.println("暂无 Topic 文件。");
        } else {
            out.printSuccess("📂 Topic 文件列表 (" + topics.size() + " 个):");
            out.println();
            for (String topic : topics) {
                String content = store.readTopic(topic);
                int lineCount = content.split("\n").length;
                out.println("  - " + topic + " (" + lineCount + " 行)");
            }
        }
        out.println();
    }

    /**
     * 搜索历史会话记录（搜索所有工作目录的会话）
     */
    private void searchHistory(OutputFormatter out, CommandContext context, String workDirPath) {
        String query = buildQueryFromArgs(context, 1);
        if (query.isEmpty()) {
            out.printError("请提供搜索关键词: /memory search <query>");
            return;
        }

        out.println();
        out.printSuccess("🔍 搜索历史会话: \"" + query + "\"");
        out.println();

        MemorySearcher searcher = new MemorySearcher();
        List<MemorySearcher.SearchResult> results = searcher.searchAll(query, 5);

        if (results.isEmpty()) {
            out.println("  未找到匹配结果。");
        } else {
            out.println("  找到 " + results.size() + " 条结果:");
            out.println();
            for (MemorySearcher.SearchResult result : results) {
                out.println("  " + result.format().replace("\n", "\n  "));
                out.println();
            }
        }
    }

    /**
     * 写入记忆
     */
    private void writeMemory(OutputFormatter out, CommandContext context, String workDirPath) {
        if (context.getArgCount() < 3) {
            out.printError("用法: /memory write <section> <content>");
            out.println("  示例: /memory write \"User Preferences\" 用户偏好使用中文回复");
            return;
        }

        String section = context.getArg(1);
        String content = buildQueryFromArgs(context, 2);

        memoryManager.writeMemory(workDirPath, section, content);
        out.println();
        out.printSuccess("✅ 已写入记忆: [" + section + "] " + content);
        out.println();
    }

    /**
     * 清空记忆
     */
    private void clearMemory(OutputFormatter out, String workDirPath) {
        memoryManager.overwriteMemory(workDirPath, "");
        out.println();
        out.printSuccess("✅ 记忆已清空");
        out.println();
    }

    /**
     * 显示帮助信息
     */
    private void showHelp(OutputFormatter out) {
        out.println();
        out.printSuccess("📝 /memory 命令帮助");
        out.println();
        out.println("  /memory          显示记忆摘要");
        out.println("  /memory read     读取完整 MEMORY.md");
        out.println("  /memory topics   列出所有 Topic 文件");
        out.println("  /memory search   <query>  搜索历史会话记录");
        out.println("  /memory write    <section> <content>  写入记忆");
        out.println("  /memory clear    清空记忆");
        out.println("  /memory help     显示此帮助");
        out.println();
    }

    /**
     * 获取工作目录路径
     */
    private String getWorkDirPath(CommandContext context) {
        return context.getEngineClient().getWorkDir().toAbsolutePath().toString();
    }

    /**
     * 从参数中构建查询字符串（从指定索引开始拼接所有参数）
     */
    private String buildQueryFromArgs(CommandContext context, int startIndex) {
        if (context.getArgCount() <= startIndex) {
            return "";
        }
        StringBuilder query = new StringBuilder();
        for (int i = startIndex; i < context.getArgCount(); i++) {
            if (!query.isEmpty()) {
                query.append(" ");
            }
            query.append(context.getArg(i));
        }
        return query.toString();
    }
}
