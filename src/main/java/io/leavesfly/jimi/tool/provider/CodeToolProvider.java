package io.leavesfly.jimi.tool.provider;

import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.knowledge.HybridSearch;
import io.leavesfly.jimi.knowledge.graph.GraphManager;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolProvider;
import io.leavesfly.jimi.tool.core.graph.CallGraphTool;
import io.leavesfly.jimi.tool.core.graph.CodeLocateTool;
import io.leavesfly.jimi.tool.core.graph.ImpactAnalysisTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图工具提供者
 * <p>
 * 提供代码图相关的工具集合，只依赖 SPI 接口
 */
@Slf4j
@Component
public class CodeToolProvider implements ToolProvider {
    
    @Autowired(required = false)
    private GraphManager graphManager;
    
    @Autowired(required = false)
    private HybridSearch hybridSearch;
    
    @Override
    public String getName() {
        return "CodeToolProvider";
    }
    
    @Override
    public int getOrder() {
        return 100; // 较低优先级，允许其他工具先加载
    }
    
    @Override
    public boolean supports(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        // 只有在图服务可用时才提供工具
        boolean isAvailable = graphManager != null && graphManager.isEnabled();
        
        if (!isAvailable) {
            log.debug("GraphManager not available, graph tools will not be provided");
        }
        
        return isAvailable;
    }
    
    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        List<Tool<?>> tools = new ArrayList<>();
        
        log.info("Creating graph tools...");
        
        try {
            // 1. CodeLocateTool - 代码定位工具
            if (hybridSearch != null && hybridSearch.isEnabled()) {
                tools.add(new CodeLocateTool(hybridSearch));
                log.info("Registered CodeLocateTool (with hybrid search manager)");
            } else {
                log.info("HybridSearch not available, CodeLocateTool not registered");
            }
            
            // 2. ImpactAnalysisTool - 影响分析工具
            if (graphManager != null && graphManager.isEnabled()) {
                tools.add(new ImpactAnalysisTool(graphManager));
                log.info("Registered ImpactAnalysisTool");
                
                // 3. CallGraphTool - 调用图查询工具
                tools.add(new CallGraphTool(graphManager));
                log.info("Registered CallGraphTool");
            }
            
            log.info("Created {} graph tools", tools.size());
            
        } catch (Exception e) {
            log.error("Failed to create graph tools", e);
        }
        
        return tools;
    }
}
