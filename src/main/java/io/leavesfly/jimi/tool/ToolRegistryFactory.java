package io.leavesfly.jimi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.interaction.approval.Approval;
import io.leavesfly.jimi.core.engine.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.tool.core.bash.Bash;
import io.leavesfly.jimi.tool.core.file.*;
import io.leavesfly.jimi.tool.core.todo.SetTodoList;
import io.leavesfly.jimi.tool.core.web.FetchURL;
import io.leavesfly.jimi.tool.core.web.WebSearch;
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

    @Autowired
    public ToolRegistryFactory(
            ApplicationContext applicationContext,
            ObjectMapper objectMapper,
            List<ToolProvider> toolProviders) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.toolProviders = toolProviders;
    }

    /**
     * 创建完整的工具注册表（包含所有 ToolProvider）
     * <p>
     * 这是推荐的工厂方法，将所有工具创建逻辑内聚在此
     *
     * @param builtinArgs    内置系统提示词参数
     * @param approval       审批对象
     * @param agentSpec      Agent 规范
     * @param runtime        运行时对象
     * @param mcpConfigFiles MCP 配置文件列表（可选）
     * @return 配置好的 ToolRegistry 实例
     */
    public ToolRegistry create(
            BuiltinSystemPromptArgs builtinArgs,
            Approval approval,
            AgentSpec agentSpec,
            Runtime runtime,
            List<Path> mcpConfigFiles) {

        // 1. 创建基础工具注册表
        ToolRegistry registry = createStandardRegistry(builtinArgs, approval, runtime.getSession());

        // 2. 应用 ToolProvider SPI 机制加载额外工具
        applyToolProviders(registry, agentSpec, runtime, mcpConfigFiles);

        log.info("Created complete tool registry with {} tools", registry.getToolNames().size());
        return registry;
    }

    /**
     * 应用所有 ToolProvider
     */
    private void applyToolProviders(
            ToolRegistry registry,
            AgentSpec agentSpec,
            Runtime runtime,
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
                .filter(provider -> provider.supports(agentSpec, runtime))
                .forEach(provider -> {
                    log.info("Applying tool provider: {} (order={})",
                            provider.getName(), provider.getOrder());
                    List<Tool<?>> tools = provider.createTools(agentSpec, runtime);
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

        // 注册文件工具
        registry.register(createReadFile(builtinArgs));
        registry.register(createWriteFile(builtinArgs, approval));
        registry.register(createStrReplaceFile(builtinArgs, approval));
        registry.register(createGlob(builtinArgs));
        registry.register(createGrep(builtinArgs));
        // PatchFile 已弃用，统一使用 StrReplaceFile（参数更简单，LLM 更容易生成正确格式）

        // 注册 Bash 工具
        registry.register(createBash(approval));

        // 注册 Web 工具
        registry.register(createFetchURL());

        registry.register(createWebSearch());

        // 注册 Todo 工具
        registry.register(createSetTodoList(session));

        log.info("Created standard tool registry with {} tools", registry.getToolNames().size());
        return registry;
    }

    /**
     * 创建 ReadFile 工具实例
     */
    private ReadFile createReadFile(BuiltinSystemPromptArgs builtinArgs) {
        ReadFile tool = applicationContext.getBean(ReadFile.class);
        tool.setBuiltinArgs(builtinArgs);
        return tool;
    }

    /**
     * 创建 WriteFile 工具实例
     */
    private WriteFile createWriteFile(BuiltinSystemPromptArgs builtinArgs, Approval approval) {
        WriteFile tool = applicationContext.getBean(WriteFile.class);
        tool.setBuiltinArgs(builtinArgs);
        tool.setApproval(approval);
        return tool;
    }

    /**
     * 创建 StrReplaceFile 工具实例
     */
    private StrReplaceFile createStrReplaceFile(BuiltinSystemPromptArgs builtinArgs, Approval approval) {
        StrReplaceFile tool = applicationContext.getBean(StrReplaceFile.class);
        tool.setBuiltinArgs(builtinArgs);
        tool.setApproval(approval);
        return tool;
    }

    /**
     * 创建 Glob 工具实例
     */
    private Glob createGlob(BuiltinSystemPromptArgs builtinArgs) {
        Glob tool = applicationContext.getBean(Glob.class);
        tool.setBuiltinArgs(builtinArgs);
        return tool;
    }

    /**
     * 创建 Grep 工具实例
     */
    private Grep createGrep(BuiltinSystemPromptArgs builtinArgs) {
        Grep tool = applicationContext.getBean(Grep.class);
        tool.setBuiltinArgs(builtinArgs);
        return tool;
    }

    /**
     * 创建 Bash 工具实例
     */
    private Bash createBash(Approval approval) {
        Bash tool = applicationContext.getBean(Bash.class);
        tool.setApproval(approval);
        return tool;
    }

    /**
     * 创建 SetTodoList 工具实例
     */
    private SetTodoList createSetTodoList(Session session) {
        SetTodoList tool = applicationContext.getBean(SetTodoList.class);
        if (session != null) {
            tool.setSession(session);
        }
        return tool;
    }

    /**
     * 创建 FetchURL 工具实例
     */
    private FetchURL createFetchURL() {
        return applicationContext.getBean(FetchURL.class);
    }

    /**
     * 创建 WebSearch 工具实例
     * WebSearch 需要搜索服务配置，如果未配置则使用空参数创建
     * 工具在执行时会检查配置并返回相应错误
     */
    private WebSearch createWebSearch() {
        WebSearch tool = applicationContext.getBean(WebSearch.class);
        return tool;
    }

}
