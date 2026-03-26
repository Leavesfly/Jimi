package io.leavesfly.jimi.tool.provider;

import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolProvider;

import io.leavesfly.jimi.tool.core.async.AsyncTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * AsyncTask 工具提供者
 * 
 * 职责：
 * - 检测 Agent 是否配置了 subagents
 * - 创建 AsyncTask 工具实例
 * 
 * 加载条件：
 * - Agent 的 subagents 配置不为空
 * 
 * 与 TaskToolProvider 的区别：
 * - TaskToolProvider 提供同步的 Task 工具
 * - AsyncTaskToolProvider 提供异步的 AsyncTask 工具
 * - 两者可以共存，由 LLM 根据场景选择使用
 * 
 * @author Jimi
 */
@Slf4j
@Component
public class AsyncTaskToolProvider implements ToolProvider {
    
    private final ApplicationContext applicationContext;
    
    @Autowired
    public AsyncTaskToolProvider(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    
    @Override
    public boolean supports(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        // 只有配置了子代理的 Agent 才需要 AsyncTask 工具
        return agentSpec.getSubagents() != null && !agentSpec.getSubagents().isEmpty();
    }
    
    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        log.info("Creating AsyncTask tool with {} subagents", agentSpec.getSubagents().size());
        
        // 从 Spring 容器获取 AsyncTask 原型实例
        AsyncTask asyncTask = applicationContext.getBean(AsyncTask.class);
        asyncTask.setRuntimeParams(agentSpec, jimiRuntime);
        
        return Collections.singletonList(asyncTask);
    }
    
    @Override
    public int getOrder() {
        return 51;  // 略低于 TaskToolProvider (50)，确保两者都被加载
    }
    
    @Override
    public String getName() {
        return "AsyncTaskToolProvider";
    }
}
