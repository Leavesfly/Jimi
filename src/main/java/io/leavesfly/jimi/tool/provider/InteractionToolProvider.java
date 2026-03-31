package io.leavesfly.jimi.tool.provider;

import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolProvider;
import io.leavesfly.jimi.tool.core.ask.AskHuman;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 人工交互工具提供者
 * 提供 ask_human 工具，允许Agent与用户进行交互
 * 
 * 加载条件：
 * - 默认启用（所有Agent都可以使用人工交互工具）
 * - 可通过Agent配置中的exclude_tools排除
 */
@Slf4j
@Component
public class InteractionToolProvider implements ToolProvider {

    @Autowired
    private final AskHuman askHuman;

    public InteractionToolProvider(AskHuman askHuman) {
        this.askHuman = askHuman;
    }

    @Override
    public String getName() {
        return "Human Interaction Tool Provider";
    }
    
    @Override
    public int getOrder() {
        return 30;  // 较高优先级，确保人工交互工具可用
    }
    
    @Override
    public boolean supports(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        // 检查是否通过exclude_tools禁用
        if (agentSpec.getExcludeTools() != null && 
                agentSpec.getExcludeTools().contains("ask_human")) {
            log.debug("Human interaction disabled for agent via exclude_tools: {}", agentSpec.getName());
            return false;
        }
        
        // 默认启用
        return true;
    }
    
    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        log.info("Creating AskHuman tool for agent: {}", agentSpec.getName());
        
        // 使用Spring管理的AskHuman Bean，确保依赖注入正常
        return Collections.singletonList(askHuman);
    }
}
