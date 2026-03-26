package io.leavesfly.jimi.tool.provider;

import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.config.info.MetaToolConfig;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolProvider;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.core.meta.MetaTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * MetaTool 提供者
 * 
 * 职责：
 * - 检查是否启用 MetaTool 功能
 * - 检查 Agent 是否配置了 MetaTool
 * - 创建 MetaTool 实例
 * 
 * 加载条件：
 * - 配置文件中 meta-tool.enabled=true
 * - Agent 的 tools 配置包含 "MetaTool"
 */
@Slf4j
@Component
public class MetaToolProvider implements ToolProvider {
    
    private final ApplicationContext applicationContext;
    private final MetaToolConfig metaToolConfig;
    
    // 运行时设置的工具注册表（用于注入到 MetaTool）
    private ToolRegistry toolRegistry;
    
    @Autowired
    public MetaToolProvider(ApplicationContext applicationContext, MetaToolConfig metaToolConfig) {
        this.applicationContext = applicationContext;
        this.metaToolConfig = metaToolConfig;
    }
    
    /**
     * 设置工具注册表
     * 注意：这个方法会在 createTools 之前被调用
     */
    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }
    
    @Override
    public boolean supports(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        // 仅检查配置是否启用
        // 注意：MetaTool 通过 SPI 自动注册，不应在 Agent 的 tools 列表中手动声明
        // 否则会因时序问题导致 'Tool not found in registry' 警告
        if (!metaToolConfig.isEnabled()) {
            log.info("MetaToolProvider: MetaTool is disabled in configuration (jimi.meta-tool.enabled=false)");
            return false;
        }
        
        log.debug("MetaToolProvider: Will provide MetaTool for agent '{}'", agentSpec.getName());
        return true;
    }
    
    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        log.info("MetaToolProvider: Creating MetaTool");
        
        // 从 Spring 容器获取 MetaTool 原型实例
        MetaTool metaTool = applicationContext.getBean(MetaTool.class);
        
        // 注入工具注册表
        // 注意：此时 toolRegistry 应该已经由 JimiFactory 设置
        if (toolRegistry != null) {
            metaTool.setToolRegistry(toolRegistry);
            log.debug("MetaToolProvider: Injected ToolRegistry into MetaTool");
        } else {
            log.warn("MetaToolProvider: ToolRegistry not set, MetaTool may not work properly");
        }
        
        return Collections.singletonList(metaTool);
    }
    
    @Override
    public int getOrder() {
        // 在标准工具之后加载，确保其他工具已经注册
        return 200;
    }
    
    @Override
    public String getName() {
        return "MetaToolProvider";
    }
}
