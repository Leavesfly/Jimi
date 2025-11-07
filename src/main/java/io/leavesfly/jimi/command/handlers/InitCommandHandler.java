package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * /init 命令处理器
 * 初始化代码库（分析并生成 AGENTS.md）
 */
@Slf4j
@Component
public class InitCommandHandler implements CommandHandler {
    
    @Override
    public String getName() {
        return "init";
    }
    
    @Override
    public String getDescription() {
        return "分析代码库并生成 AGENTS.md";
    }
    
    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        try {
            out.printStatus("🔍 正在分析代码库...");
            
            // 构建 INIT 提示词
            String initPrompt = buildInitPrompt();
            
            // 直接使用当前 Soul 运行分析任务
            context.getSoul().run(initPrompt).block();
            
            out.printSuccess("✅ 代码库分析完成！");
            out.printInfo("已生成 AGENTS.md 文件");
            
        } catch (Exception e) {
            log.error("Failed to init codebase", e);
            out.printError("代码库分析失败: " + e.getMessage());
        }
    }
    
    /**
     * 构建 INIT 提示词
     */
    private String buildInitPrompt() {
        return "你是一位拥有多年编程经验的软件工程专家。\n" +
            "请探索当前项目目录，了解项目的架构和主要细节。\n" +
            "\n" +
            "任务要求：\n" +
            "1. 分析项目结构，识别关键配置文件（如 pom.xml、build.gradle、package.json 等）。\n" +
            "2. 理解项目的技术栈、构建过程和运行时架构。\n" +
            "3. 识别代码的组织方式和主要模块划分。\n" +
            "4. 发现项目特有的开发规范、测试策略和部署流程。\n" +
            "\n" +
            "探索完成后，你**必须**对你的发现做一个全面的总结，并**使用文件写入工具**将其覆盖写入项目根目录下的 `AGENTS.md` 文件。\n" +
            "如果 AGENTS.md 文件已存在，在编写时需要参考文件中已有的内容。\n" +
            "\n" +
            "需要注意的是，`AGENTS.md` 文件是专门供 AI 编码代理阅读的。\n" +
            "假设该文件的读者对项目一无所知。\n" +
            "\n" +
            "你应该根据实际的项目内容来编写此文件。\n" +
            "不要做任何假设或泛化。确保信息准确且有用。\n" +
            "\n" +
            "人们通常在 `AGENTS.md` 中编写的常见章节包括：\n" +
            "- 项目概述\n" +
            "- 构建和测试命令\n" +
            "- 代码风格指南\n" +
            "- 测试说明\n" +
            "- 安全注意事项\n" +
            "\n" +
            "重要：请务必确保 AGENTS.md 文件被成功创建或更新。";
    }
}
