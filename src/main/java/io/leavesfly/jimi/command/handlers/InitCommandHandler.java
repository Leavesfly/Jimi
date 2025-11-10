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
        return "你是一位拥有多年编程经验的资深软件架构师和技术文档专家。\n" +
            "请深入分析当前项目目录，全面了解项目的架构设计、技术实现和工程实践。\n" +
            "\n" +
            "## 📋 分析任务要求\n" +
            "\n" +
            "请按照以下维度对项目进行系统性分析：\n" +
            "\n" +
            "### 1️⃣ 项目结构分析\n" +
            "- 识别项目类型（Web应用、CLI工具、库/框架、微服务等）\n" +
            "- 分析目录结构和模块划分（src、test、resources、config等）\n" +
            "- 识别关键配置文件（pom.xml、build.gradle、package.json、application.yml等）\n" +
            "- 梳理核心代码包的组织方式和职责划分\n" +
            "\n" +
            "### 2️⃣ 技术栈分析\n" +
            "- 编程语言及版本（Java、Python、Node.js等）\n" +
            "- 核心框架（Spring Boot、React、Express等）\n" +
            "- 数据存储方案（数据库类型、ORM框架、缓存方案）\n" +
            "- 依赖管理工具（Maven、Gradle、npm、pip等）\n" +
            "- 第三方库和关键依赖项\n" +
            "\n" +
            "### 3️⃣ 构建与运行流程\n" +
            "- 项目构建命令（编译、打包、安装）\n" +
            "- 运行启动命令（开发环境、生产环境）\n" +
            "- 环境变量和配置要求（JDK版本、Node版本、数据库配置等）\n" +
            "- 脚本和工具链（Makefile、Shell脚本、npm scripts等）\n" +
            "\n" +
            "### 4️⃣ 代码规范与设计模式\n" +
            "- 代码组织原则（分层架构、DDD、Clean Architecture等）\n" +
            "- 命名规范（类名、方法名、变量名、包名）\n" +
            "- 注释和文档规范（Javadoc、JSDoc、注释风格）\n" +
            "- 常用设计模式（工厂模式、策略模式、观察者模式等）\n" +
            "- 异常处理和错误管理策略\n" +
            "\n" +
            "### 5️⃣ 测试策略\n" +
            "- 测试框架（JUnit、Mockito、Jest、Pytest等）\n" +
            "- 测试类型（单元测试、集成测试、E2E测试）\n" +
            "- 测试运行命令\n" +
            "- 测试覆盖率要求和检查方式\n" +
            "- Mock和测试数据管理\n" +
            "\n" +
            "### 6️⃣ 部署与运维\n" +
            "- 部署方式（Docker、K8s、传统部署、Serverless等）\n" +
            "- 环境配置（开发、测试、生产环境差异）\n" +
            "- 日志管理（日志框架、日志级别、日志输出）\n" +
            "- 监控和性能优化\n" +
            "\n" +
            "### 7️⃣ 安全与最佳实践\n" +
            "- 敏感信息管理（API密钥、数据库密码等）\n" +
            "- 安全编码规范\n" +
            "- 权限和认证机制\n" +
            "- 数据验证和输入过滤\n" +
            "\n" +
            "### 8️⃣ 特殊注意事项\n" +
            "- 项目特有的约定和限制\n" +
            "- 常见陷阱和问题（Known Issues）\n" +
            "- 开发调试技巧\n" +
            "- 贡献指南（如果是开源项目）\n" +
            "\n" +
            "## 📝 输出要求\n" +
            "\n" +
            "探索和分析完成后，你**必须**将发现的内容整理成结构化文档，并**使用 WriteFile 工具**将内容写入项目根目录下的 `AGENTS.md` 文件。\n" +
            "\n" +
            "**重要说明：**\n" +
            "1. 如果 `AGENTS.md` 文件已存在，请先阅读其内容，在现有基础上进行补充和完善，而不是完全覆盖。\n" +
            "2. `AGENTS.md` 文件的目标读者是**AI 编码代理**，假设读者对项目完全陌生。\n" +
            "3. 内容必须基于**实际项目代码和配置**，不要做假设或泛化描述。\n" +
            "4. 使用 Markdown 格式，保持结构清晰、层次分明。\n" +
            "5. 包含具体的命令、配置示例和代码片段，确保信息准确且可操作。\n" +
            "6. 每个章节应简洁明了，避免冗长的理论描述。\n" +
            "\n" +
            "## 📂 建议的文档结构\n" +
            "\n" +
            "```markdown\n" +
            "# 项目名称\n" +
            "\n" +
            "## 项目概述\n" +
            "- 项目简介和主要功能\n" +
            "- 技术栈总览\n" +
            "- 核心特性\n" +
            "\n" +
            "## 项目结构\n" +
            "- 目录结构说明\n" +
            "- 核心模块介绍\n" +
            "- 关键文件说明\n" +
            "\n" +
            "## 技术栈\n" +
            "- 编程语言和版本\n" +
            "- 核心框架\n" +
            "- 主要依赖库\n" +
            "\n" +
            "## 构建与运行\n" +
            "- 环境要求\n" +
            "- 构建命令\n" +
            "- 运行命令\n" +
            "- 配置说明\n" +
            "\n" +
            "## 开发规范\n" +
            "- 代码组织规范\n" +
            "- 命名约定\n" +
            "- 注释规范\n" +
            "- 设计模式\n" +
            "\n" +
            "## 测试\n" +
            "- 测试框架\n" +
            "- 运行测试\n" +
            "- 测试覆盖率\n" +
            "\n" +
            "## 部署\n" +
            "- 部署方式\n" +
            "- 环境配置\n" +
            "- 日志和监控\n" +
            "\n" +
            "## 安全注意事项\n" +
            "- 敏感信息管理\n" +
            "- 安全最佳实践\n" +
            "\n" +
            "## 常见问题\n" +
            "- 已知问题\n" +
            "- 调试技巧\n" +
            "- 故障排查\n" +
            "```\n" +
            "\n" +
            "**最后提醒：** 请务必确保使用 WriteFile 工具成功创建或更新 AGENTS.md 文件！";
    }
}
