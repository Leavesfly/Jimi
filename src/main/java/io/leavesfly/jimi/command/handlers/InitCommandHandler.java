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
            
            // 获取工作目录
            String workDir = context.getEngineClient().getWorkDir().toString();
            
            // 构建 INIT 提示词
            String initPrompt = buildInitPrompt(workDir);
            
            // 直接使用当前 Engine 运行分析任务
            context.getEngineClient().runCommand(initPrompt).block();
            
            out.printSuccess("✅ 代码库分析完成！");
            out.printInfo("已生成 AGENTS.md 文件");
            
        } catch (Exception e) {
            log.error("Failed to init codebase", e);
            out.printError("代码库分析失败: " + e.getMessage());
        }
    }
    
    /**
     * 构建 INIT 提示词
     * @param workDir 工作目录路径
     */
    private String buildInitPrompt(String workDir) {
        return "你是资深架构师和技术文档专家，请深入分析当前项目并生成全面的中文技术文档。\n" +
            "\n" +
            "## 📋 核心分析维度\n" +
            "\n" +
            "### 1. 项目概述\n" +
            "- 项目类型（Web应用/CLI工具/库框架/微服务等）\n" +
            "- 核心功能和业务价值\n" +
            "- 主要特性和亮点\n" +
            "- 目标用户和使用场景\n" +
            "\n" +
            "### 2. 技术架构 ⭐️（重点）\n" +
            "- **系统设计**：整体架构风格（分层/微服务/事件驱动等）、核心设计模式\n" +
            "- **模块划分**：主要模块及其职责、模块间依赖关系、边界清晰度\n" +
            "- **数据流向**：请求处理流程、数据流转路径、关键节点说明\n" +
            "- **技术选型**：核心框架/库的选择理由、适用场景、关键配置\n" +
            "- **扩展机制**：插件系统、SPI接口、依赖注入等扩展点\n" +
            "- **性能优化**：缓存策略、异步处理、资源管理等优化手段\n" +
            "\n" +
            "### 3. 项目结构\n" +
            "- 目录组织逻辑和分层\n" +
            "- 关键目录作用说明\n" +
            "- 核心文件清单\n" +
            "- 配置文件位置和用途\n" +
            "\n" +
            "### 4. 技术栈详解\n" +
            "- 编程语言及版本要求\n" +
            "- 核心框架（版本、作用、关键特性）\n" +
            "- 重要依赖库（用途、版本、替代方案）\n" +
            "- 构建工具和插件配置\n" +
            "\n" +
            "### 5. 构建与运行\n" +
            "- 环境准备（JDK/Node版本、环境变量）\n" +
            "- 构建命令和参数说明\n" +
            "- 运行方式（开发/生产模式）\n" +
            "- 配置文件说明（必填项、可选项）\n" +
            "\n" +
            "### 6. 开发规范\n" +
            "- 代码组织原则（包结构、类命名）\n" +
            "- 编码规范（风格、注释、异常处理）\n" +
            "- 常用设计模式及应用场景\n" +
            "- 最佳实践和反模式\n" +
            "\n" +
            "### 7. 测试策略\n" +
            "- 测试框架和工具\n" +
            "- 测试分类（单元/集成/E2E）\n" +
            "- 运行测试的命令\n" +
            "- Mock策略和测试数据\n" +
            "\n" +
            "### 8. 部署运维\n" +
            "- 部署方式和流程\n" +
            "- 环境配置差异\n" +
            "- 日志系统配置\n" +
            "- 监控和告警机制\n" +
            "\n" +
            "### 9. 关键流程\n" +
            "- 核心业务流程说明\n" +
            "- 重要类/方法的调用链\n" +
            "- 生命周期管理\n" +
            "- 错误处理和恢复机制\n" +
            "\n" +
            "### 10. 注意事项\n" +
            "- 项目特有约定和限制\n" +
            "- 常见问题和解决方案\n" +
            "- 调试技巧和工具\n" +
            "- 安全注意事项\n" +
            "\n" +
            "## 📝 输出要求\n" +
            "\n" +
            "**必须使用 WriteFile 工具将分析结果写入：`" + workDir + "/AGENTS.md`**\n" +
            "\n" +
            "文档要求：\n" +
            "1. 使用中文撰写（代码、命令、配置保持原样）\n" +
            "2. 基于实际代码分析，不做臆测\n" +
            "3. Markdown格式，层次分明\n" +
            "4. 包含具体示例（命令、配置、代码片段）\n" +
            "5. **重点突出技术架构**：系统设计、模块关系、数据流向、技术选型\n" +
            "6. 面向AI编码代理，提供充分上下文\n" +
            "7. 如文件已存在，补充完善而非覆盖\n" +
            "\n" +
            "## 🎯 特别提示\n" +
            "\n" +
            "- 对于技术架构部分，务必深入分析代码结构\n" +
            "- 说明核心类/接口的设计意图和协作关系\n" +
            "- 绘制或描述关键流程的执行路径\n" +
            "- 解释技术选型的合理性和适用场景\n" +
            "\n" +
            "**重要：必须确保文件成功写入 `" + workDir + "/AGENTS.md`！**";
    }
}
