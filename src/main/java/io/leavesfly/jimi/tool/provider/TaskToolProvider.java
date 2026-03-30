package io.leavesfly.jimi.tool.provider;

import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolProvider;

import io.leavesfly.jimi.tool.core.SubAgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * SubAgentTool 工具提供者
 * 
 * 职责：
 * - 检测 Agent 是否配置了 subagents
 * - 创建 SubAgentTool 工具实例
 * 
 * 加载条件：
 * - Agent 的 subagents 配置不为空
 */
@Slf4j
@Component
public class TaskToolProvider implements ToolProvider {
    
    private final ApplicationContext applicationContext;
    
    @Autowired
    public TaskToolProvider(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    
    @Override
    public boolean supports(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        return agentSpec.getSubagents() != null && !agentSpec.getSubagents().isEmpty();
    }
    
    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        log.info("Creating SubAgentTool tool with {} subagents", agentSpec.getSubagents().size());
        
        // 从 Spring 容器获取 SubAgentTool 原型实例
        SubAgentTool subAgentTool = applicationContext.getBean(SubAgentTool.class);
        subAgentTool.setRuntimeParams(agentSpec, jimiRuntime);
        
        return Collections.singletonList(subAgentTool);
    }
    
    @Override
    public int getOrder() {
        return 50;  // 中等优先级，在标准工具之后加载
    }
}
