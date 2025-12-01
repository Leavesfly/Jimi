package io.leavesfly.jimi.tool.graph;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.graph.navigator.GraphNavigator;
import io.leavesfly.jimi.graph.visualization.GraphVisualizer;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 调用图查询工具
 * <p>
 * 查询方法调用链和调用关系,支持可视化
 */
@Slf4j
public class CallGraphTool extends AbstractTool<CallGraphTool.Params> {
    
    private final GraphNavigator navigator;
    private final GraphVisualizer visualizer;
    
    public CallGraphTool(GraphNavigator navigator, GraphVisualizer visualizer) {
        super(
            "call_graph",
            "查询方法调用图。可查找调用链、调用者、被调用者,并支持 Mermaid 可视化。",
            Params.class
        );
        this.navigator = navigator;
        this.visualizer = visualizer;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("CallGraph tool called: methodId='{}', queryType={}, maxDepth={}", 
                params.getMethodEntityId(), params.getQueryType(), params.getMaxDepth());
        
        try {
            String output;
            
            switch (params.getQueryType()) {
                case "callers":
                    output = findCallers(params);
                    break;
                    
                case "callees":
                    output = findCallees(params);
                    break;
                    
                case "callchain":
                    output = findCallChain(params);
                    break;
                    
                case "visualize":
                default:
                    output = visualizeCallGraph(params);
                    break;
            }
            
            return Mono.just(ToolResult.ok(output, "Call graph query completed"));
            
        } catch (Exception e) {
            log.error("Call graph query failed: {}", e.getMessage(), e);
            return Mono.just(ToolResult.error(
                "Call graph query failed: " + e.getMessage(),
                "Execution error"
            ));
        }
    }
    
    private String findCallers(Params params) {
        var callers = navigator.findCallers(params.getMethodEntityId(), params.getMaxDepth()).block();
        
        StringBuilder sb = new StringBuilder();
        sb.append("# 调用者列表\n\n");
        sb.append(String.format("找到 %d 个调用者:\n\n", callers != null ? callers.size() : 0));
        
        if (callers != null) {
            callers.forEach(caller -> 
                sb.append(String.format("- `%s` (%s:%d)\n", 
                    caller.getName(), 
                    caller.getFilePath(),
                    caller.getStartLine()))
            );
        }
        
        return sb.toString();
    }
    
    private String findCallees(Params params) {
        var callees = navigator.findCallees(params.getMethodEntityId(), params.getMaxDepth()).block();
        
        StringBuilder sb = new StringBuilder();
        sb.append("# 被调用方法列表\n\n");
        sb.append(String.format("找到 %d 个被调用方法:\n\n", callees != null ? callees.size() : 0));
        
        if (callees != null) {
            callees.forEach(callee -> 
                sb.append(String.format("- `%s` (%s:%d)\n", 
                    callee.getName(), 
                    callee.getFilePath(),
                    callee.getStartLine()))
            );
        }
        
        return sb.toString();
    }
    
    private String findCallChain(Params params) {
        if (params.getTargetMethodId() == null) {
            return "错误: targetMethodId 参数为空";
        }
        
        var chains = navigator.findCallChains(
            params.getMethodEntityId(),
            params.getTargetMethodId(),
            params.getMaxDepth()
        ).block();
        
        StringBuilder sb = new StringBuilder();
        sb.append("# 调用链\n\n");
        sb.append(String.format("找到 %d 条调用链:\n\n", chains != null ? chains.size() : 0));
        
        if (chains != null) {
            int index = 1;
            for (var chain : chains) {
                sb.append(String.format("%d. %s (深度: %d)\n", index++, chain.getPathString(), chain.getDepth()));
            }
        }
        
        return sb.toString();
    }
    
    private String visualizeCallGraph(Params params) {
        String mermaid = visualizer.exportCallGraphToMermaid(
            params.getMethodEntityId(),
            params.getMaxDepth()
        ).block();
        
        StringBuilder sb = new StringBuilder();
        sb.append("# 调用图可视化\n\n");
        sb.append(mermaid != null ? mermaid : "生成失败");
        
        return sb.toString();
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        
        @JsonPropertyDescription("方法实体ID,格式如 'METHOD:com.example.UserService.login'")
        private String methodEntityId;
        
        @JsonPropertyDescription("查询类型。可选值: callers(调用者), callees(被调用者), callchain(调用链), visualize(可视化)。默认: visualize")
        @Builder.Default
        private String queryType = "visualize";
        
        @JsonPropertyDescription("查询深度。默认: 3")
        @Builder.Default
        private int maxDepth = 3;
        
        @JsonPropertyDescription("目标方法ID(仅callchain类型需要)")
        private String targetMethodId;
    }
}
