package io.leavesfly.jimi.tool.graph;

import io.leavesfly.jimi.agent.AgentSpec;
import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.config.VectorIndexConfig;
import io.leavesfly.jimi.engine.runtime.Runtime;
import io.leavesfly.jimi.graph.GraphManager;
import io.leavesfly.jimi.graph.navigator.GraphNavigator;
import io.leavesfly.jimi.graph.navigator.ImpactAnalyzer;
import io.leavesfly.jimi.graph.search.GraphSearchEngine;
import io.leavesfly.jimi.graph.search.HybridSearchEngine;
import io.leavesfly.jimi.graph.store.CodeGraphStore;
import io.leavesfly.jimi.graph.visualization.GraphVisualizer;
import io.leavesfly.jimi.retrieval.EmbeddingProvider;
import io.leavesfly.jimi.retrieval.VectorStore;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 图工具提供者
 * <p>
 * 提供代码图相关的工具集合
 */
@Slf4j
@Component
public class GraphToolProvider implements ToolProvider {
    
    @Autowired(required = false)
    private CodeGraphStore codeGraphStore;
    
    @Autowired(required = false)
    private GraphManager graphManager;
    
    @Autowired(required = false)
    private VectorStore vectorStore;
    
    @Autowired(required = false)
    private EmbeddingProvider embeddingProvider;
    
    @Autowired(required = false)
    private JimiConfig jimiConfig;
    
    @Override
    public String getName() {
        return "Graph Tool Provider";
    }
    
    @Override
    public int getOrder() {
        return 100; // 较低优先级，允许其他工具先加载
    }
    
    @Override
    public boolean supports(AgentSpec agentSpec, Runtime runtime) {
        // 只有在代码图存储可用时才提供工具
        boolean isAvailable = codeGraphStore != null;
        
        if (!isAvailable) {
            log.debug("CodeGraphStore not available, graph tools will not be provided");
        }
        
        return isAvailable;
    }
    
    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, Runtime runtime) {
        List<Tool<?>> tools = new ArrayList<>();
        
        log.info("Creating graph tools...");
        
        // 设置工作目录（从 Runtime 获取）
        if (runtime != null) {
            Path workDir = runtime.getWorkDir();
            
            // 设置 GraphManager 的工作目录
            if (graphManager != null) {
                graphManager.setWorkDir(workDir);
            }
            
            // 设置 VectorStore 的工作目录并触发自动加载
            if (vectorStore != null && vectorStore instanceof io.leavesfly.jimi.retrieval.InMemoryVectorStore) {
                io.leavesfly.jimi.retrieval.InMemoryVectorStore inMemoryStore = 
                    (io.leavesfly.jimi.retrieval.InMemoryVectorStore) vectorStore;
                inMemoryStore.setWorkDir(workDir);
                
                // 如果配置了自动加载，触发加载
                if (jimiConfig != null && jimiConfig.getVectorIndex() != null) {
                    VectorIndexConfig vectorConfig = jimiConfig.getVectorIndex();
                    if (vectorConfig.isAutoLoad()) {
                        Path indexPath = workDir.resolve(vectorConfig.getIndexPath());
                        if (java.nio.file.Files.exists(indexPath)) {
                            vectorStore.load(indexPath)
                                .doOnSuccess(success -> {
                                    if (success) {
                                        log.info("Auto-loaded vector index from: {}", indexPath);
                                    } else {
                                        log.debug("No existing vector index found at: {}", indexPath);
                                    }
                                })
                                .doOnError(e -> log.warn("Failed to auto-load vector index: {}", e.getMessage()))
                                .onErrorResume(e -> reactor.core.publisher.Mono.just(false))
                                .subscribe();
                        }
                    }
                }
            }
        }
        
        try {
            // 创建核心组件
            GraphNavigator navigator = new GraphNavigator(codeGraphStore);
            ImpactAnalyzer impactAnalyzer = new ImpactAnalyzer(codeGraphStore);
            GraphVisualizer visualizer = new GraphVisualizer(codeGraphStore);
            
            // 1. CodeLocateTool - 代码定位工具
            if (vectorStore != null && embeddingProvider != null) {
                GraphSearchEngine graphSearchEngine = new GraphSearchEngine(
                    codeGraphStore, navigator, impactAnalyzer
                );
                
                HybridSearchEngine hybridSearchEngine = new HybridSearchEngine(
                    graphSearchEngine, vectorStore, embeddingProvider
                );
                
                tools.add(new CodeLocateTool(hybridSearchEngine, graphManager));
                log.info("Registered CodeLocateTool (with hybrid search)");
            } else {
                log.warn("VectorStore or EmbeddingProvider not available, CodeLocateTool not registered");
            }
            
            // 2. ImpactAnalysisTool - 影响分析工具
            tools.add(new ImpactAnalysisTool(impactAnalyzer));
            log.info("Registered ImpactAnalysisTool");
            
            // 3. CallGraphTool - 调用图查询工具
            tools.add(new CallGraphTool(navigator, visualizer));
            log.info("Registered CallGraphTool");
            
            log.info("Created {} graph tools", tools.size());
            
        } catch (Exception e) {
            log.error("Failed to create graph tools", e);
        }
        
        return tools;
    }
}
