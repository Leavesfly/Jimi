package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;

import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import io.leavesfly.jimi.command.wiki.WikiGenerator;

import io.leavesfly.jimi.command.wiki.WikiValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import java.util.stream.Stream;

/**
 * /wiki 命令处理器
 * 管理项目Wiki文档系统
 * <p>
 * 支持的操作：
 * - /wiki init: 初始化Wiki文档系统（幂等操作）
 * - /wiki update: 检测代码变更并更新Wiki文档
 * - /wiki delete: 删除Wiki文档系统
 */
@Slf4j
@Component
public class WikiCommandHandler implements CommandHandler {


    @Autowired(required = false)
    private WikiValidator wikiValidator;

    @Autowired(required = false)
    private WikiGenerator wikiGenerator;

    private static final String WIKI_DIR_NAME = ".jimi/wiki";
    private static final String TIMESTAMP_FILE = ".wiki-timestamp";

    @Override
    public String getName() {
        return "wiki";
    }

    @Override
    public String getDescription() {
        return "管理项目Wiki文档系统";
    }

    @Override
    public String getUsage() {
        return "/wiki [init|validate|delete]";
    }

    @Override
    public String getCategory() {
        return "documentation";
    }

    @Override
    public void execute(CommandContext context) throws Exception {
        // 根据参数数量分发到不同的处理方法
        if (context.getArgCount() == 0) {
            // 默认执行init
            executeInit(context);
        } else {
            String subCommand = context.getArg(0);
            switch (subCommand.toLowerCase()) {
                case "init":
                    executeInit(context);
                    break;
                case "validate":
                    executeValidate(context);
                    break;
                case "delete":
                    executeDelete(context);
                    break;
                default:
                    showUsageHelp(context);
                    break;
            }
        }
    }

    /**
     * 执行init子命令 - 初始化Wiki文档系统（幂等操作）
     */
    private void executeInit(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        try {
            // 获取工作目录
            String workDir = context.getEngineClient().getWorkDir().toString();
            Path wikiPath = Path.of(workDir, WIKI_DIR_NAME);

            // 检查Wiki目录是否已存在（幂等性检查）
            if (checkWikiExists(wikiPath)) {
                out.println();
                out.printSuccess("✅ Wiki文档系统已存在");
                out.printInfo("📁 文档位置：" + wikiPath.toAbsolutePath());
                out.printInfo("💡 提示：如需重新生成，请先执行 /wiki delete");
                out.println();
                return;
            }

            out.println();
            out.printStatus("🔍 正在分析项目并生成Wiki文档...");
            out.printInfo("📁 输出目录：" + wikiPath.toAbsolutePath());

            // 创建Wiki目录
            Files.createDirectories(wikiPath);
            log.info("Created wiki directory: {}", wikiPath);

            // 使用 WikiGenerator + Agent 引擎生成（如果可用）
            if (wikiGenerator != null) {
                out.printInfo("⚡ 使用 Agent 引擎生成...");
                out.println();

                long startTime = System.currentTimeMillis();

                // 通过 EngineClient 驱动 Agent 引擎生成
                WikiGenerator.GenerationResult result = wikiGenerator
                        .generateWiki(wikiPath, workDir, context.getEngineClient())
                        .join();

                long duration = System.currentTimeMillis() - startTime;

                if (result.isSuccess()) {
                    out.println();
                    out.printSuccess("✅ Wiki文档生成完成！");
                    out.printInfo(String.format("📊 统计: 生成 %d 个文档, 缓存 %d 个, 耗时 %d ms",
                            result.getGeneratedDocs(), result.getCachedDocs(), duration));
                    out.printInfo("📁 文档位置：" + wikiPath.toAbsolutePath());
                    out.printInfo("💡 查看 README.md 开始浏览Wiki");
                } else {
                    out.println();
                    out.printError("❌ Wiki生成失败: " + result.getErrorMessage());
                    out.printInfo("💡 尝试使用 Agent 引擎直接生成...");
                }
            }

            // 更新时间戳
            updateTimestamp(wikiPath);

            out.println();

        } catch (IOException e) {
            log.error("Failed to create wiki directory", e);
            out.println();
            out.printError("创建Wiki目录失败: " + e.getMessage());
            out.printInfo("请检查文件系统权限");
            out.println();
        } catch (Exception e) {
            log.error("Failed to initialize wiki", e);
            out.println();
            out.printError("Wiki初始化失败: " + e.getMessage());
            out.println();
        }
    }


    /**
     * 执行delete子命令 - 删除Wiki文档系统
     */
    private void executeDelete(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        try {
            // 获取工作目录
            String workDir = context.getEngineClient().getWorkDir().toString();
            Path wikiPath = Path.of(workDir, WIKI_DIR_NAME);

            // 检查Wiki是否存在
            if (!checkWikiExists(wikiPath)) {
                out.println();
                out.printWarning("⚠️  Wiki文档系统不存在");
                out.println();
                return;
            }

            // 请求用户确认（如果不在YOLO模式）
            if (!context.getEngineClient().isYoloMode()) {
                out.println();
                out.printWarning("⚠️  即将删除Wiki文档系统");
                out.printInfo("📁 目录：" + wikiPath.toAbsolutePath());
                out.println();

                String confirmation = context.getLineReader()
                        .readLine("确认删除? (y/n): ");

                if (!"y".equalsIgnoreCase(confirmation.trim())) {
                    out.println();
                    out.printInfo("已取消删除");
                    out.println();
                    return;
                }
            }

            out.println();
            out.printStatus("🗑️  正在删除Wiki文档系统...");

            // 递归删除目录
            deleteDirectory(wikiPath);

            out.println();
            out.printSuccess("✅ Wiki文档系统已删除");
            out.println();

        } catch (Exception e) {
            log.error("Failed to delete wiki", e);
            out.println();
            out.printError("删除Wiki失败: " + e.getMessage());
            out.println();
        }
    }


    /**
     * 执行validate子命令 - 验证Wiki文档质量
     */
    private void executeValidate(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        if (wikiValidator == null) {
            out.println();
            out.printWarning("⚠️  Wiki验证器未初始化");
            out.println();
            return;
        }

        try {
            // 获取工作目录
            String workDir = context.getEngineClient().getWorkDir().toString();
            Path wikiPath = Path.of(workDir, WIKI_DIR_NAME);

            // 检查Wiki是否存在
            if (!checkWikiExists(wikiPath)) {
                out.println();
                out.printWarning("⚠️  Wiki文档系统不存在");
                out.printInfo("请先执行 /wiki init 初始化Wiki");
                out.println();
                return;
            }

            out.println();
            out.printStatus("🔍 正在验证Wiki文档...");
            out.println();

            // 执行验证
            WikiValidator.ValidationReport report = wikiValidator.validate(wikiPath);

            // 显示结果
            out.printInfo("=".repeat(60));
            out.printInfo(report.getSummary());
            out.printInfo("=".repeat(60));
            out.println();

            // 显示错误
            if (!report.getErrors().isEmpty()) {
                out.printError("❌ 错误 (" + report.getErrors().size() + ")：");
                for (WikiValidator.ValidationIssue issue : report.getErrors()) {
                    out.println("  - " + issue.getMessage());
                }
                out.println();
            }

            // 显示警告
            if (!report.getWarnings().isEmpty()) {
                out.printWarning("⚠️  警告 (" + report.getWarnings().size() + ")：");
                for (WikiValidator.ValidationIssue issue : report.getWarnings()) {
                    out.println("  - " + issue.getMessage());
                }
                out.println();
            }

            // 显示提示
            if (!report.getInfos().isEmpty() && report.getInfos().size() <= 10) {
                out.printInfo("💡 提示 (" + report.getInfos().size() + ")：");
                for (WikiValidator.ValidationIssue issue : report.getInfos()) {
                    out.println("  - " + issue.getMessage());
                }
                out.println();
            }

            // 总结
            if (report.isClean()) {
                out.printSuccess("✅ Wiki文档验证通过，质量良好！");
            } else if (report.hasErrors()) {
                out.printError("❌ Wiki文档存在错误，请修复");
            } else {
                out.printWarning("⚠️  Wiki文档存在一些问题，建议优化");
            }

            out.println();

        } catch (Exception e) {
            log.error("Failed to validate wiki", e);
            out.println();
            out.printError("验证失败: " + e.getMessage());
            out.println();
        }
    }

    /**
     * 显示用法帮助
     */
    private void showUsageHelp(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        out.println();
        out.printSuccess("Wiki 命令用法:");
        out.println();
        out.printInfo("子命令:");
        out.println("  /wiki init      - 初始化Wiki文档系统（幂等操作）");
        out.println("  /wiki validate  - 验证Wiki文档质量");
        out.println("  /wiki delete    - 删除Wiki文档系统");
        out.println();
        out.printInfo("示例:");
        out.println("  /wiki init                     # 初始化Wiki");
        out.println("  /wiki validate                 # 验证文档");
        out.println("  /wiki delete                   # 删除Wiki");
        out.println();
    }

    /**
     * 检查Wiki目录是否存在
     */
    private boolean checkWikiExists(Path wikiPath) {
        if (!Files.exists(wikiPath)) {
            return false;
        }

        if (!Files.isDirectory(wikiPath)) {
            log.warn("Wiki path exists but is not a directory: {}", wikiPath);
            return false;
        }

        // 检查是否包含至少一个有效的Wiki文件
        Path readmePath = wikiPath.resolve("README.md");
        return Files.exists(readmePath);
    }


    /**
     * 更新时间戳文件
     */
    private void updateTimestamp(Path wikiPath) {
        Path timestampFile = wikiPath.resolve(TIMESTAMP_FILE);

        try {
            long currentTime = System.currentTimeMillis();
            Files.writeString(timestampFile, String.valueOf(currentTime));
            log.debug("Updated wiki timestamp: {}", currentTime);
        } catch (IOException e) {
            log.warn("Failed to update timestamp file", e);
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.error("Failed to delete: {}", path, e);
                            }
                        });
            }
        }
    }
}
