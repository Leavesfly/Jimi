package io.leavesfly.jimi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.approval.Approval;
import io.leavesfly.jimi.core.engine.context.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.core.sandbox.SandboxValidator;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.tool.core.BashTool;
import io.leavesfly.jimi.tool.core.MemoryTool;
import io.leavesfly.jimi.tool.core.file.*;
import io.leavesfly.jimi.tool.core.SkillsTool;
import io.leavesfly.jimi.tool.core.SetTodoList;
import io.leavesfly.jimi.tool.core.web.FetchURL;
import io.leavesfly.jimi.tool.core.web.WebSearch;
import io.leavesfly.jimi.memory.MemoryManager;
import io.leavesfly.jimi.tool.provider.MCPToolProvider;
import io.leavesfly.jimi.tool.provider.MetaToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * ToolRegistry 工厂类
 * 负责创建配置好的 ToolRegistry 实例
 * 使用 Spring 容器获取 Tool 原型 Bean
 */
@Slf4j
@Service
public class ToolRegistryFactory {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final List<ToolProvider> toolProviders;
    private final MemoryManager memoryManager;

    @Autowired
    public ToolRegistryFactory(
            ApplicationContext applicationContext,
            ObjectMapper objectMapper,
            List<ToolProvider> toolProviders,
            MemoryManager memoryManager) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.toolProviders = toolProviders;
        this.memoryManager = memoryManager;
    }

    /**
     * 创建完整的工具注册表（包含所有 ToolProvider）
     * <p>
     * 这是推荐的工厂方法，将所有工具创建逻辑内聚在此
     *
     * @param builtinArgs    内置系统提示词参数
     * @param approval       审批对象
     * @param agentSpec      Agent 规范
     * @param jimiRuntime        运行时对象
     * @param mcpConfigFiles MCP 配置文件列表（可选）
     * @return 配置好的 ToolRegistry 实例
     */
    public ToolRegistry create(
            BuiltinSystemPromptArgs builtinArgs,
            Approval approval,
            AgentSpec agentSpec,
            JimiRuntime jimiRuntime,
            List<Path> mcpConfigFiles) {

        // 1. 创建基础工具注册表
        ToolRegistry registry = createStandardRegistry(builtinArgs, approval, jimiRuntime.getSession());

        // 2. 应用 ToolProvider SPI 机制加载额外工具
        applyToolProviders(registry, agentSpec, jimiRuntime, mcpConfigFiles);

        log.info("Created complete tool registry with {} tools", registry.getToolNames().size());
        return registry;
    }

    /**
     * 应用所有 ToolProvider
     */
    private void applyToolProviders(
            ToolRegistry registry,
            AgentSpec agentSpec,
            JimiRuntime jimiRuntime,
            List<Path> mcpConfigFiles) {

        log.debug("Applying {} tool providers", toolProviders.size());

        // 对于 MCP 提供者，需要设置配置文件
        toolProviders.stream()
                .filter(p -> p instanceof MCPToolProvider)
                .forEach(p -> ((MCPToolProvider) p).setMcpConfigFiles(mcpConfigFiles));

        // 对于 MetaToolProvider，需要提前注入 ToolRegistry
        toolProviders.stream()
                .filter(p -> p instanceof MetaToolProvider)
                .forEach(p -> ((MetaToolProvider) p).setToolRegistry(registry));

        // 按顺序应用所有工具提供者
        toolProviders.stream()
                .sorted(Comparator.comparingInt(ToolProvider::getOrder))
                .filter(provider -> provider.supports(agentSpec, jimiRuntime))
                .forEach(provider -> {
                    log.info("Applying tool provider: {} (order={})",
                            provider.getName(), provider.getOrder());
                    List<Tool<?>> tools = provider.createTools(agentSpec, jimiRuntime);
                    tools.forEach(registry::register);
                    log.debug("  Registered {} tools from {}", tools.size(), provider.getName());
                });
    }

    /**
     * 创建标准工具注册表
     * 包含所有内置工具
     *
     * @param builtinArgs 内置系统提示词参数
     * @param approval    审批对象
     * @return 配置好的 ToolRegistry 实例
     */
    public ToolRegistry createStandardRegistry(BuiltinSystemPromptArgs builtinArgs, Approval approval) {
        return createStandardRegistry(builtinArgs, approval, null);
    }

    /**
     * 内置工具类型列表（新增内置工具只需在此添加）
     */
    private static final List<Class<? extends Tool<?>>> BUILTIN_TOOL_TYPES = List.of(
            ReadFile.class,
            WriteFile.class,
            StrReplaceFile.class,
            Glob.class,
            Grep.class,
            BashTool.class,
            FetchURL.class,
            WebSearch.class,
            SetTodoList.class,
            SkillsTool.class,  // 技能管理工具（渐进式披露）
            MemoryTool.class   // 记忆管理工具
    );

    /**
     * 创建标准工具注册表（带 Session）
     * 包含所有内置工具
     *
     * @param builtinArgs 内置系统提示词参数
     * @param approval    审批对象
     * @param session     会话对象（用于 Todo 持久化）
     * @return 配置好的 ToolRegistry 实例
     */
    private ToolRegistry createStandardRegistry(BuiltinSystemPromptArgs builtinArgs, Approval approval, Session session) {
        ToolRegistry registry = new ToolRegistry(objectMapper);

        // 获取 SandboxValidator（如果存在）
        io.leavesfly.jimi.core.sandbox.SandboxValidator sandboxValidator = null;
        try {
            sandboxValidator = applicationContext.getBean(io.leavesfly.jimi.core.sandbox.SandboxValidator.class);
        } catch (Exception e) {
            log.debug("SandboxValidator not available, tools will run without sandbox validation");
        }

        // 统一创建并注册所有内置工具
        for (Class<? extends Tool<?>> toolType : BUILTIN_TOOL_TYPES) {
            Tool<?> tool = createAndInitializeTool(toolType, builtinArgs, approval, sandboxValidator, session);
            registry.register(tool);
        }

        log.info("Created standard tool registry with {} tools", registry.getToolNames().size());
        return registry;
    }

    /**
     * 统一创建并初始化工具实例
     * 从 Spring 容器获取原型 Bean，然后根据工具类型注入运行时依赖
     */
    private Tool<?> createAndInitializeTool(
            Class<? extends Tool<?>> toolType,
            BuiltinSystemPromptArgs builtinArgs,
            Approval approval,
            SandboxValidator sandboxValidator,
            Session session) {

        Tool<?> tool = applicationContext.getBean(toolType);

        // 注入 builtinArgs（文件类工具需要工作目录）
        if (tool instanceof ReadFile readFile) {
            readFile.setBuiltinArgs(builtinArgs);
        } else if (tool instanceof WriteFile writeFile) {
            writeFile.setBuiltinArgs(builtinArgs);
            writeFile.setApproval(approval);
            if (sandboxValidator != null) writeFile.setSandboxValidator(sandboxValidator);
        } else if (tool instanceof StrReplaceFile strReplaceFile) {
            strReplaceFile.setBuiltinArgs(builtinArgs);
            strReplaceFile.setApproval(approval);
            if (sandboxValidator != null) strReplaceFile.setSandboxValidator(sandboxValidator);
        } else if (tool instanceof Glob glob) {
            glob.setBuiltinArgs(builtinArgs);
        } else if (tool instanceof Grep grep) {
            grep.setBuiltinArgs(builtinArgs);
        } else if (tool instanceof BashTool bashTool) {
            bashTool.setApproval(approval);
            if (sandboxValidator != null) bashTool.setSandboxValidator(sandboxValidator);
        } else if (tool instanceof SetTodoList todoList && session != null) {
            todoList.setSession(session);
        } else if (tool instanceof MemoryTool memoryTool) {
            memoryTool.setMemoryManager(memoryManager);
            if (builtinArgs != null && builtinArgs.getJimiWorkDir() != null) {
                String workDir = builtinArgs.getJimiWorkDir().toAbsolutePath().toString();
                memoryTool.setWorkDirPath(workDir);
                // 设置 sessionsDir（Layer 3: 会话记录搜索）
                String dirHash = Integer.toHexString(workDir.hashCode());
                memoryTool.setSessionsDir(
                        java.nio.file.Paths.get(System.getProperty("user.home"), ".jimi", "sessions", dirHash));
            }
        }
        // FetchURL、WebSearch 无需额外初始化

        return tool;
    }

}
