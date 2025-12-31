package io.leavesfly.jimi.tool.core.graph;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.knowledge.spi.GraphService;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 调用图查询工具
 * <p>
 * 查询方法调用链和调用关系,支持可视化
 */
@Slf4j
public class CallGraphTool extends AbstractTool<CallGraphTool.Params> {
    
    private final GraphService graphService;
    
    public CallGraphTool(GraphService graphService) {
        super(
            "CallGraphTool",
            "查询方法调用图。可查找调用链、调用者、被调用者,并支持 Mermaid 可视化。",
            Params.class
        );
        this.graphService = graphService;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("CallGraph tool called: methodId='{}', queryType={}, maxDepth={}", 
                params.getMethodEntityId(), params.getQueryType(), params.getMaxDepth());
        
        switch (params.getQueryType()) {
            case "callers":
                return findCallers(params);
            case "callees":
                return findCallees(params);
            case "callchain":
                return findCallChain(params);
            case "visualize":
            default:
                return visualizeCallGraph(params);
        }
    }
    
    private Mono<ToolResult> findCallers(Params params) {
        return graphService.findCallers(params.getMethodEntityId(), params.getMaxDepth())
                .map(callers -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("# 调用者列表\n\n");
                    sb.append(String.format("找到 %d 个调用者:\n\n", callers.size()));
                    
                    for (GraphService.EntityInfo caller : callers) {
                        sb.append(String.format("- `%s` (%s:%d)\n", 
                            caller.getName(), 
                            caller.getFilePath(),
                            caller.getStartLine()));
                    }
                    
                    return ToolResult.ok(sb.toString(), "Call graph query completed");
                })
                .onErrorResume(e -> Mono.just(ToolResult.error(
                    "Call graph query failed: " + e.getMessage(), "Execution error")));
    }
    
    private Mono<ToolResult> findCallees(Params params) {
        return graphService.findCallees(params.getMethodEntityId(), params.getMaxDepth())
                .map(callees -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("# 被调用方法列表\n\n");
                    sb.append(String.format("找到 %d 个被调用方法:\n\n", callees.size()));
                    
                    for (GraphService.EntityInfo callee : callees) {
                        sb.append(String.format("- `%s` (%s:%d)\n", 
                            callee.getName(), 
                            callee.getFilePath(),
                            callee.getStartLine()));
                    }
                    
                    return ToolResult.ok(sb.toString(), "Call graph query completed");
                })
                .onErrorResume(e -> Mono.just(ToolResult.error(
                    "Call graph query failed: " + e.getMessage(), "Execution error")));
    }
    
    private Mono<ToolResult> findCallChain(Params params) {
        if (params.getTargetMethodId() == null) {
            return Mono.just(ToolResult.error("错误: targetMethodId 参数为空", "Missing parameter"));
        }
        
        return graphService.findCallChains(params.getMethodEntityId(), params.getTargetMethodId(), params.getMaxDepth())
                .map(chains -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("# 调用链\n\n");
                    sb.append(String.format("找到 %d 条调用链:\n\n", chains.size()));
                    
                    int index = 1;
                    for (GraphService.CallChainInfo chain : chains) {
                        sb.append(String.format("%d. %s (深度: %d)\n", index++, chain.getPathString(), chain.getDepth()));
                    }
                    
                    return ToolResult.ok(sb.toString(), "Call graph query completed");
                })
                .onErrorResume(e -> Mono.just(ToolResult.error(
                    "Call graph query failed: " + e.getMessage(), "Execution error")));
    }
    
    private Mono<ToolResult> visualizeCallGraph(Params params) {
        return graphService.exportCallGraphToMermaid(params.getMethodEntityId(), params.getMaxDepth())
                .map(mermaid -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("# 调用图可视化\n\n");
                    sb.append(mermaid != null && !mermaid.isEmpty() ? mermaid : "生成失败");
                    return ToolResult.ok(sb.toString(), "Call graph query completed");
                })
                .onErrorResume(e -> Mono.just(ToolResult.error(
                    "Call graph query failed: " + e.getMessage(), "Execution error")));
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
