package io.leavesfly.jimi.adk.tools.base;

import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 工具基类
 * 提供通用的工具实现模板
 */
public abstract class AbstractTool<P> implements Tool<P> {
    
    /**
     * 工具名称
     */
    private final String name;
    
    /**
     * 工具描述
     */
    private final String description;
    
    /**
     * 参数类型
     */
    private final Class<P> paramsType;
    
    protected AbstractTool(String name, String description, Class<P> paramsType) {
        this.name = name;
        this.description = description;
        this.paramsType = paramsType;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    @Override
    public Class<P> getParamsType() {
        return paramsType;
    }
    
    @Override
    public Mono<ToolResult> execute(P params) {
        return Mono.defer(() -> {
            try {
                // 参数验证
                if (!validateParams(params)) {
                    return Mono.just(ToolResult.error("参数验证失败"));
                }
                
                // 执行工具逻辑
                return doExecute(params);
                
            } catch (Exception e) {
                return Mono.just(ToolResult.error("执行失败: " + e.getMessage()));
            }
        });
    }
    
    /**
     * 执行工具的具体逻辑
     * 子类必须实现此方法
     */
    protected abstract Mono<ToolResult> doExecute(P params);
}
