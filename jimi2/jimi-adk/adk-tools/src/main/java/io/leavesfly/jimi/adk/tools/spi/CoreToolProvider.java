package io.leavesfly.jimi.adk.tools.spi;

import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import io.leavesfly.jimi.adk.tools.file.GlobTool;
import io.leavesfly.jimi.adk.tools.file.ListDirTool;
import io.leavesfly.jimi.adk.tools.file.ReadFileTool;
import io.leavesfly.jimi.adk.tools.file.WriteFileTool;
import io.leavesfly.jimi.adk.tools.shell.BashTool;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 核心工具提供者
 * 提供基础的文件操作和 Shell 执行工具
 */
@Slf4j
public class CoreToolProvider implements ToolProvider {
    
    @Override
    public boolean supports(AgentSpec agentSpec, Runtime runtime) {
        // 核心工具始终可用
        return true;
    }
    
    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, Runtime runtime) {
        Path workDir = runtime.getWorkDir();
        List<Tool<?>> tools = new ArrayList<>();
        
        // 文件操作工具
        tools.add(new ReadFileTool(workDir));
        tools.add(new WriteFileTool(workDir));
        tools.add(new ListDirTool(workDir));
        tools.add(new GlobTool(workDir));
        
        // Shell 执行工具
        tools.add(new BashTool(workDir));
        
        log.info("CoreToolProvider: 创建了 {} 个核心工具", tools.size());
        return tools;
    }
    
    @Override
    public int getOrder() {
        return 0;  // 最高优先级，首先加载
    }
    
    @Override
    public String getName() {
        return "CoreToolProvider";
    }
}
