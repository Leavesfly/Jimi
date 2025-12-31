package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.knowledge.rag.CodeChunk;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import io.leavesfly.jimi.knowledge.wiki.ChangeDetector;

import io.leavesfly.jimi.knowledge.wiki.FileChange;
import io.leavesfly.jimi.knowledge.wiki.WikiGenerator;
import io.leavesfly.jimi.knowledge.wiki.WikiIndexManager;
import io.leavesfly.jimi.knowledge.wiki.WikiValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.time.LocalDateTime;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
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
    private ChangeDetector changeDetector;

    @Autowired(required = false)
    private WikiIndexManager wikiIndexManager;


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
        return "/wiki [init|update|search|ask|validate|delete]";
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
                case "update":
                    executeUpdate(context);
                    break;
                case "search":
                    executeSearch(context);
                    break;
                case "ask":
                    executeAsk(context);
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
            String workDir = context.getSoul().getRuntime().getWorkDir().toString();
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

            // 使用 WikiGenerator 并发生成（如果可用）
            if (wikiGenerator != null) {
                out.printInfo("⚡ 使用并发生成引擎...");
                out.println();

                long startTime = System.currentTimeMillis();

                // 异步生成
                WikiGenerator.GenerationResult result = wikiGenerator
                        .generateWiki(wikiPath, workDir, context.getSoul().getRuntime().getLlm())
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
                    out.printInfo("💡 尝试使用传统模式...");

                    // Fallback 到传统模式
                    generateWithPrompt(context, workDir, out);
                }
            } else {
                // 使用传统 Prompt 生成模式
                out.printInfo("📝 使用 LLM 生成模式...");
                out.println();
                generateWithPrompt(context, workDir, out);
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
     * 使用 Prompt 生成模式（传统方式）
     */
    private void generateWithPrompt(CommandContext context, String workDir, OutputFormatter out) {
        try {
            // 构建初始化提示词
            String initPrompt = buildInitPrompt(workDir);

            // 调用Engine执行分析任务
            context.getSoul().run(initPrompt).block();

            out.println();
            out.printSuccess("✅ Wiki文档生成完成！");
            out.printInfo("📁 文档位置：" + Path.of(workDir, WIKI_DIR_NAME).toAbsolutePath());
            out.printInfo("💡 查看 README.md 开始浏览Wiki");

        } catch (Exception e) {
            log.error("Failed to generate wiki with prompt", e);
            out.println();
            out.printError("Wiki生成失败: " + e.getMessage());
        }
    }

    /**
     * 执行update子命令 - 检测代码变更并更新Wiki
     */
    private void executeUpdate(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        try {
            // 获取工作目录
            String workDir = context.getSoul().getRuntime().getWorkDir().toString();
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
            out.printStatus("🔍 正在检测代码变更...");

            // 使用 ChangeDetector 检测变更
            List<FileChange> changes = changeDetector != null
                    ? changeDetector.detectChanges(wikiPath)
                    : detectCodeChanges(wikiPath).stream()
                    .map(file -> FileChange.builder()
                            .filePath(file)
                            .changeType(FileChange.ChangeType.MODIFIED)
                            .importance(FileChange.ChangeImportance.MINOR)
                            .build())
                    .collect(Collectors.toList());

            if (changes.isEmpty()) {
                out.println();
                out.printSuccess("✅ 代码无变更，无需更新Wiki");
                out.println();
                return;
            }

            // 按重要性分类显示
            long criticalCount = changes.stream()
                    .filter(c -> c.getImportance() == FileChange.ChangeImportance.CRITICAL).count();
            long majorCount = changes.stream()
                    .filter(c -> c.getImportance() == FileChange.ChangeImportance.MAJOR).count();
            long minorCount = changes.stream()
                    .filter(c -> c.getImportance() == FileChange.ChangeImportance.MINOR).count();

            out.printInfo(String.format("📝 检测到变更: 关键 %d, 重要 %d, 一般 %d",
                    criticalCount, majorCount, minorCount));
            out.printStatus("🔄 正在更新Wiki文档...");
            out.println();

            // 构建更新提示词（集成检索增强）
            String updatePrompt = buildUpdatePromptWithRetrieval(workDir, changes);

            // 调用Engine执行更新任务
            context.getSoul().run(updatePrompt).block();

            // 更新时间戳
            updateTimestamp(wikiPath);

            out.println();
            out.printSuccess("✅ Wiki文档更新完成！");
            out.printInfo("📁 文档位置：" + wikiPath.toAbsolutePath());
            out.println();

        } catch (Exception e) {
            log.error("Failed to update wiki", e);
            out.println();
            out.printError("Wiki更新失败: " + e.getMessage());
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
            String workDir = context.getSoul().getRuntime().getWorkDir().toString();
            Path wikiPath = Path.of(workDir, WIKI_DIR_NAME);

            // 检查Wiki是否存在
            if (!checkWikiExists(wikiPath)) {
                out.println();
                out.printWarning("⚠️  Wiki文档系统不存在");
                out.println();
                return;
            }

            // 请求用户确认（如果不在YOLO模式）
            if (!context.getSoul().getRuntime().isYoloMode()) {
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
     * 执行search子命令 - 语义搜索Wiki
     */
    private void executeSearch(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        if (context.getArgCount() < 2) {
            out.println();
            out.printWarning("⚠️  请提供搜索关键词");
            out.printInfo("用法: /wiki search <关键词>");
            out.println();
            return;
        }

        if (wikiIndexManager == null || !wikiIndexManager.isAvailable()) {
            out.println();
            out.printWarning("⚠️  向量索引未启用，无法进行语义搜索");
            out.printInfo("提示：请配置 vector_index 并执行 /index build");
            out.println();
            return;
        }

        try {
            // 构建查询
            String query = String.join(" ", Arrays.copyOfRange(context.getArgs(), 1, context.getArgs().length));

            out.println();
            out.printStatus("🔍 正在搜索: " + query);
            out.println();

            // 搜索Wiki
            List<WikiIndexManager.WikiSearchResult> results = wikiIndexManager.searchWiki(query, 5);

            if (results.isEmpty()) {
                out.printWarning("⚠️  未找到相关内容");
            } else {
                out.printSuccess("✅ 找到 " + results.size() + " 个相关结果:");
                out.println();

                for (int i = 0; i < results.size(); i++) {
                    WikiIndexManager.WikiSearchResult result = results.get(i);
                    out.printInfo(String.format("%d. %s (相似度: %.2f)",
                            i + 1, result.getDocPath(), result.getScore()));

                    // 显示内容片段
                    String content = result.getContent();
                    if (content.length() > 200) {
                        content = content.substring(0, 200) + "...";
                    }
                    out.println("   " + content.replaceAll("\n", "\n   "));
                    out.println();
                }
            }

            out.println();

        } catch (Exception e) {
            log.error("Failed to search wiki", e);
            out.println();
            out.printError("搜索失败: " + e.getMessage());
            out.println();
        }
    }

    /**
     * 执行ask子命令 - 基于Wiki问答
     */
    private void executeAsk(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        if (context.getArgCount() < 2) {
            out.println();
            out.printWarning("⚠️  请提供问题");
            out.printInfo("用法: /wiki ask <问题>");
            out.println();
            return;
        }

        if (wikiIndexManager == null || !wikiIndexManager.isAvailable()) {
            out.println();
            out.printWarning("⚠️  向量索引未启用，无法进行问答");
            out.printInfo("提示：请配置 vector_index 并执行 /index build");
            out.println();
            return;
        }

        try {
            // 构建问题
            String question = String.join(" ", Arrays.copyOfRange(context.getArgs(), 1, context.getArgs().length));

            out.println();
            out.printStatus("🤔 问题: " + question);
            out.println();

            // 搜索相关Wiki内容
            List<WikiIndexManager.WikiSearchResult> results = wikiIndexManager.searchWiki(question, 3);

            if (results.isEmpty()) {
                out.printWarning("⚠️  未找到相关Wiki内容，无法回答");
                out.println();
                return;
            }

            // 构建基于Wiki的问答Prompt
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("你是项目 Wiki 文档助手，请基于以下 Wiki 内容回答用户问题。\n\n");
            promptBuilder.append("## 📚 Wiki 参考内容\n\n");

            for (WikiIndexManager.WikiSearchResult result : results) {
                promptBuilder.append(String.format("### %s\n", result.getDocPath()));
                promptBuilder.append(result.getContent());
                promptBuilder.append("\n\n");
            }

            promptBuilder.append("## ❓ 用户问题\n\n");
            promptBuilder.append(question);
            promptBuilder.append("\n\n");
            promptBuilder.append("## 💬 回答要求\n\n");
            promptBuilder.append("1. 基于上述 Wiki 内容回答问题\n");
            promptBuilder.append("2. 如果 Wiki 内容不足以回答，请明确说明\n");
            promptBuilder.append("3. 回答要简洁明确，包含具体示例\n");

            // 调用Engine执行问答
            context.getSoul().run(promptBuilder.toString()).block();

        } catch (Exception e) {
            log.error("Failed to answer question", e);
            out.println();
            out.printError("问答失败: " + e.getMessage());
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
            String workDir = context.getSoul().getRuntime().getWorkDir().toString();
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
        out.println("  /wiki update    - 检测代码变更并更新Wiki文档");
        out.println("  /wiki search    - 语义搜索Wiki内容");
        out.println("  /wiki ask       - 基于Wiki问答问题");
        out.println("  /wiki validate  - 验证Wiki文档质量");
        out.println("  /wiki delete    - 删除Wiki文档系统");
        out.println();
        out.printInfo("示例:");
        out.println("  /wiki init                     # 初始化Wiki");
        out.println("  /wiki update                   # 更新Wiki");
        out.println("  /wiki search 架构设计       # 搜索相关内容");
        out.println("  /wiki ask 如何配置         # 提问");
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
     * 检测代码变更
     * 通过比较文件最后修改时间和上次Wiki更新时间来判断
     */
    private List<String> detectCodeChanges(Path wikiPath) {
        List<String> changedFiles = new ArrayList<>();

        try {
            // 获取上次更新时间戳
            long lastUpdateTime = getLastUpdateTimestamp(wikiPath);

            // 获取工作目录
            Path workDir = wikiPath.getParent().getParent();

            // 遍历源代码目录
            Path srcPath = workDir.resolve("src");
            if (Files.exists(srcPath)) {
                try (Stream<Path> paths = Files.walk(srcPath)) {
                    paths.filter(Files::isRegularFile)
                            .filter(p -> {
                                String name = p.getFileName().toString();
                                return name.endsWith(".java") ||
                                        name.endsWith(".xml") ||
                                        name.endsWith(".yml") ||
                                        name.endsWith(".yaml") ||
                                        name.endsWith(".properties") ||
                                        name.endsWith(".md");
                            })
                            .filter(p -> {
                                try {
                                    long lastModified = Files.getLastModifiedTime(p).toMillis();
                                    return lastModified > lastUpdateTime;
                                } catch (IOException e) {
                                    return false;
                                }
                            })
                            .forEach(p -> changedFiles.add(workDir.relativize(p).toString()));
                }
            }

        } catch (Exception e) {
            log.error("Failed to detect code changes", e);
        }

        return changedFiles;
    }

    /**
     * 获取上次更新时间戳
     */
    private long getLastUpdateTimestamp(Path wikiPath) {
        Path timestampFile = wikiPath.resolve(TIMESTAMP_FILE);

        try {
            if (Files.exists(timestampFile)) {
                String content = Files.readString(timestampFile).trim();
                return Long.parseLong(content);
            }
        } catch (Exception e) {
            log.warn("Failed to read timestamp file", e);
        }

        // 如果无法读取时间戳，返回0（所有文件都被认为是变更的）
        return 0;
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

    /**
     * 构建Wiki初始化提示词
     */
    private String buildInitPrompt(String workDir) {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return "你是资深架构师和技术文档专家，请深入分析当前项目并生成完整的中文Wiki文档系统。\n" +
                "\n" +
                "## 📋 任务目标\n" +
                "\n" +
                "在 `" + workDir + "/.jimi/wiki/` 目录下生成结构化的项目Wiki文档，为开发者提供全面的项目知识库。\n" +
                "\n" +
                "## 📁 文档结构要求\n" +
                "\n" +
                "请按照以下目录结构组织Wiki文档：\n" +
                "\n" +
                "```\n" +
                ".jimi/wiki/\n" +
                "├── README.md                    # Wiki首页和导航\n" +
                "├── architecture/                # 架构设计\n" +
                "│   ├── overview.md             # 系统架构概览\n" +
                "│   ├── module-design.md        # 模块划分设计\n" +
                "│   └── data-flow.md            # 数据流向设计\n" +
                "├── api/                         # API文档\n" +
                "│   ├── interfaces.md           # 核心接口\n" +
                "│   └── data-models.md          # 数据模型\n" +
                "├── guides/                      # 使用指南\n" +
                "│   ├── getting-started.md      # 快速开始\n" +
                "│   ├── configuration.md        # 配置指南\n" +
                "│   └── best-practices.md       # 最佳实践\n" +
                "├── development/                 # 开发文档\n" +
                "│   ├── setup.md                # 环境搭建\n" +
                "│   ├── coding-standards.md     # 编码规范\n" +
                "│   └── testing.md              # 测试指南\n" +
                "└── reference/                   # 参考文档\n" +
                "    ├── tech-stack.md           # 技术栈说明\n" +
                "    ├── dependencies.md         # 依赖清单\n" +
                "    └── troubleshooting.md      # 故障排查\n" +
                "```\n" +
                "\n" +
                "## 🔍 分析维度\n" +
                "\n" +
                "### 1. 项目概览（README.md）\n" +
                "- 项目类型和定位\n" +
                "- 核心功能清单\n" +
                "- 业务价值说明\n" +
                "- 使用场景描述\n" +
                "- 文档导航链接\n" +
                "\n" +
                "### 2. 系统架构（architecture/）\n" +
                "- **overview.md**: 整体架构风格、技术选型、核心设计原则\n" +
                "- **module-design.md**: 核心模块划分、模块职责、模块依赖关系\n" +
                "- **data-flow.md**: 数据流向分析、关键流程、生命周期管理\n" +
                "\n" +
                "### 3. API参考（api/）\n" +
                "- **interfaces.md**: 核心接口定义、接口职责、使用示例\n" +
                "- **data-models.md**: 数据模型说明、字段定义、关系说明\n" +
                "\n" +
                "### 4. 使用指南（guides/）\n" +
                "- **getting-started.md**: 快速开始、安装步骤、基本使用\n" +
                "- **configuration.md**: 配置参数说明、环境变量、配置示例\n" +
                "- **best-practices.md**: 使用建议、常见模式、注意事项\n" +
                "\n" +
                "### 5. 开发文档（development/）\n" +
                "- **setup.md**: 环境搭建步骤、工具要求、依赖安装\n" +
                "- **coding-standards.md**: 代码规范、命名约定、设计模式\n" +
                "- **testing.md**: 测试策略、测试框架、测试示例\n" +
                "\n" +
                "### 6. 参考文档（reference/）\n" +
                "- **tech-stack.md**: 技术栈详解、框架版本、选型理由\n" +
                "- **dependencies.md**: 依赖清单、版本信息、用途说明\n" +
                "- **troubleshooting.md**: 常见问题、解决方案、调试技巧\n" +
                "\n" +
                "## 📝 输出要求\n" +
                "\n" +
                "1. **使用WriteFile工具**创建各个文档文件，路径必须是绝对路径\n" +
                "2. **中文撰写**，代码和命令保持原样\n" +
                "3. **基于实际代码分析**，不做臆测\n" +
                "4. **Markdown格式**，层次分明\n" +
                "5. **包含具体示例**：命令、配置、代码片段\n" +
                "6. **使用Mermaid图表**：架构图、流程图、时序图、类图\n" +
                "7. **文档间建立导航链接**：使用相对路径链接其他文档\n" +
                "8. **添加文档元信息**：在每个文档开头添加生成时间（" + dateStr + "）\n" +
                "\n" +
                "## 🎯 特别提示\n" +
                "\n" +
                "- 深入分析源代码，理解设计意图\n" +
                "- 说明核心类/接口的协作关系\n" +
                "- 绘制关键流程的执行路径（使用Mermaid）\n" +
                "- 解释技术选型的合理性\n" +
                "- 提供实用的示例代码和命令\n" +
                "- 每个目录下的文档要相互关联，形成完整的知识体系\n" +
                "\n" +
                "## ⚠️ 重要说明\n" +
                "\n" +
                "1. **必须使用WriteFile工具**创建文件，不要使用其他方式\n" +
                "2. **路径必须是绝对路径**，例如：`" + workDir + "/.jimi/wiki/README.md`\n" +
                "3. **先创建目录结构**，按顺序生成各个文档文件\n" +
                "4. **README.md是入口文档**，包含完整的文档导航\n" +
                "5. **确保所有文档成功写入**，检查每个WriteFile调用的结果\n" +
                "\n" +
                "开始分析并生成Wiki文档系统！";
    }

    /**
     * 构建Wiki更新提示词
     */
    private String buildUpdatePrompt(String workDir, List<String> changedFiles) {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 构建变更文件列表
        StringBuilder changedFilesList = new StringBuilder();
        for (int i = 0; i < Math.min(changedFiles.size(), 20); i++) {
            changedFilesList.append("- ").append(changedFiles.get(i)).append("\n");
        }
        if (changedFiles.size() > 20) {
            changedFilesList.append("- ... (共 ").append(changedFiles.size()).append(" 个文件发生变更)\n");
        }

        return "你是资深架构师和技术文档专家，请分析代码变更并更新Wiki文档系统。\n" +
                "\n" +
                "## 📋 任务目标\n" +
                "\n" +
                "分析以下代码变更，确定需要更新的Wiki文档，并使用StrReplaceFile工具精确更新相关内容。\n" +
                "\n" +
                "## 📝 变更文件列表\n" +
                "\n" +
                changedFilesList.toString() +
                "\n" +
                "## 🔍 分析步骤\n" +
                "\n" +
                "### 1. 分析变更影响范围\n" +
                "- 使用ReadFile工具读取变更的文件，理解修改内容\n" +
                "- 确定这些变更影响的功能模块\n" +
                "- 识别需要更新的Wiki文档\n" +
                "\n" +
                "### 2. 更新相关文档\n" +
                "- 使用ReadFile读取现有Wiki文档内容\n" +
                "- 使用StrReplaceFile工具更新过期的内容\n" +
                "- 保持文档结构和格式一致\n" +
                "- 更新文档的最后更新时间为：" + dateStr + "\n" +
                "\n" +
                "### 3. 更新文档类型\n" +
                "根据变更类型，更新对应的文档：\n" +
                "- **架构变更** → 更新 architecture/ 下的文档\n" +
                "- **API变更** → 更新 api/ 下的文档\n" +
                "- **配置变更** → 更新 guides/configuration.md\n" +
                "- **依赖变更** → 更新 reference/dependencies.md 和 reference/tech-stack.md\n" +
                "- **测试变更** → 更新 development/testing.md\n" +
                "- **功能变更** → 更新 README.md 和相关功能文档\n" +
                "\n" +
                "## 📝 更新要求\n" +
                "\n" +
                "1. **使用StrReplaceFile工具**进行精确更新，不要使用WriteFile覆盖\n" +
                "2. **保留现有格式**：保持Markdown格式和文档结构\n" +
                "3. **增量更新**：只更新受变更影响的部分，不修改无关内容\n" +
                "4. **更新时间戳**：在修改的文档开头更新\"最后更新时间\"\n" +
                "5. **验证一致性**：确保更新后的文档逻辑连贯\n" +
                "6. **路径使用绝对路径**：例如 `" + workDir + "/.jimi/wiki/README.md`\n" +
                "\n" +
                "## 🎯 特别提示\n" +
                "\n" +
                "- 仔细阅读变更文件，理解修改意图\n" +
                "- 只更新真正受影响的文档部分\n" +
                "- 如果变更较小，可能只需更新一两个文档\n" +
                "- 保持文档的专业性和准确性\n" +
                "- 使用Mermaid图表时，确保语法正确\n" +
                "\n" +
                "## ⚠️ 重要说明\n" +
                "\n" +
                "1. **必须先ReadFile读取现有内容**，再使用StrReplaceFile更新\n" +
                "2. **不要覆盖整个文件**，只替换需要更新的部分\n" +
                "3. **确保old字符串唯一匹配**，避免误替换\n" +
                "4. **检查每次替换的结果**，确保更新成功\n" +
                "\n" +
                "开始分析变更并更新Wiki文档！";
    }

    /**
     * 构建带检索增强的更新提示词
     */
    private String buildUpdatePromptWithRetrieval(String workDir, List<FileChange> changes) {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 按重要性排序
        List<FileChange> sortedChanges = changes.stream()
                .sorted((a, b) -> b.getImportance().compareTo(a.getImportance()))
                .collect(Collectors.toList());

        // 构建变更文件列表
        StringBuilder changedFilesList = new StringBuilder();
        for (int i = 0; i < Math.min(sortedChanges.size(), 20); i++) {
            FileChange change = sortedChanges.get(i);
            String importance = getImportanceLabel(change.getImportance());
            changedFilesList.append(String.format("- [%s] %s (%d行变更)\n",
                    importance, change.getFilePath(), change.getChangedLines()));
        }
        if (sortedChanges.size() > 20) {
            changedFilesList.append("- ... (共 ").append(sortedChanges.size()).append(" 个文件发生变更)\n");
        }

        // 检索增强：获取相关代码示例
        StringBuilder retrievalContext = new StringBuilder();
        if (wikiIndexManager != null && wikiIndexManager.isAvailable()) {
            // 为关键变更检索相关代码
            List<FileChange> criticalChanges = sortedChanges.stream()
                    .filter(c -> c.getImportance() == FileChange.ChangeImportance.CRITICAL ||
                            c.getImportance() == FileChange.ChangeImportance.MAJOR)
                    .limit(3)
                    .collect(Collectors.toList());

            for (FileChange change : criticalChanges) {
                String query = "相关代码: " + change.getFilePath();
                List<CodeChunk> relevantCode = wikiIndexManager.retrieveRelevantCode(query, 3);
                if (!relevantCode.isEmpty()) {
                    retrievalContext.append("\n### 🔍 ").append(change.getFilePath()).append(" 的相关代码\n");
                    retrievalContext.append(wikiIndexManager.formatCodeChunks(relevantCode));
                }
            }
        }

        String prompt = "你是资深架构师和技术文档专家，请分析代码变更并更新Wiki文档系统。\n" +
                "\n" +
                "## 📋 任务目标\n" +
                "\n" +
                "分析以下代码变更，确定需要更新的Wiki文档，并使用StrReplaceFile工具精确更新相关内容。\n" +
                "\n" +
                "## 📝 变更文件列表\n" +
                "\n" +
                changedFilesList.toString() +
                "\n";

        // 如果有检索增强内容，添加到提示词
        if (retrievalContext.length() > 0) {
            prompt += "## 🔍 检索增强上下文\n" +
                    "\n" +
                    "以下是与关键变更相关的代码片段，可帮助你更好地理解变更影响：\n" +
                    retrievalContext.toString() +
                    "\n";
        }

        prompt += "## 🔍 分析步骤\n" +
                "\n" +
                "### 1. 分析变更影响范围\n" +
                "- 使用ReadFile工具读取变更的文件，理解修改内容\n" +
                "- 确定这些变更影响的功能模块\n" +
                "- 识别需要更新的Wiki文档\n" +
                "\n" +
                "### 2. 更新相关文档\n" +
                "- 使用ReadFile读取现有Wiki文档内容\n" +
                "- 使用StrReplaceFile工具更新过期的内容\n" +
                "- 保持文档结构和格式一致\n" +
                "- 更新文档的最后更新时间为：" + dateStr + "\n" +
                "\n" +
                "### 3. 更新文档类型\n" +
                "根据变更类型，更新对应的文档：\n" +
                "- **架构变更** → 更新 architecture/ 下的文档\n" +
                "- **API变更** → 更新 api/ 下的文档\n" +
                "- **配置变更** → 更新 guides/configuration.md\n" +
                "- **依赖变更** → 更新 reference/dependencies.md 和 reference/tech-stack.md\n" +
                "- **测试变更** → 更新 development/testing.md\n" +
                "- **功能变更** → 更新 README.md 和相关功能文档\n" +
                "\n" +
                "## 📝 更新要求\n" +
                "\n" +
                "1. **使用StrReplaceFile工具**进行精确更新，不要使用WriteFile覆盖\n" +
                "2. **保留现有格式**：保持Markdown格式和文档结构\n" +
                "3. **增量更新**：只更新受变更影响的部分，不修改无关内容\n" +
                "4. **更新时间戳**：在修改的文档开头更新\"最后更新时间\"\n" +
                "5. **验证一致性**：确保更新后的文档逻辑连贯\n" +
                "6. **路径使用绝对路径**：例如 `" + workDir + "/.jimi/wiki/README.md`\n" +
                "\n" +
                "## 🎯 特别提示\n" +
                "\n" +
                "- 仔细阅读变更文件，理解修改意图\n" +
                "- 只更新真正受影响的文档部分\n" +
                "- 如果变更较小，可能只需更新一两个文档\n" +
                "- 保持文档的专业性和准确性\n" +
                "- 使用Mermaid图表时，确保语法正确\n" +
                "\n" +
                "## ⚠️ 重要说明\n" +
                "\n" +
                "1. **必须先ReadFile读取现有内容**，再使用StrReplaceFile更新\n" +
                "2. **不要覆盖整个文件**，只替换需要更新的部分\n" +
                "3. **确保old字符串唯一匹配**，避免误替换\n" +
                "4. **检查每次替换的结果**，确保更新成功\n" +
                "\n" +
                "开始分析变更并更新Wiki文档！";

        return prompt;
    }

    /**
     * 获取重要性标签
     */
    private String getImportanceLabel(FileChange.ChangeImportance importance) {
        switch (importance) {
            case CRITICAL:
                return "关键";
            case MAJOR:
                return "重要";
            case MINOR:
                return "一般";
            case TRIVIAL:
                return "微小";
            default:
                return "未知";
        }
    }
}
