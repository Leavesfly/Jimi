package io.leavesfly.jimi.tool;

import io.leavesfly.jimi.agent.AgentSpec;
import io.leavesfly.jimi.soul.runtime.Runtime;

import java.util.List;

/**
 * 工具提供者接口（SPI）
 * 
 * 职责：
 * - 判断是否支持为给定的 Agent 提供工具
 * - 创建工具实例
 * - 定义加载顺序
 * 
 * 设计模式：SPI (Service Provider Interface)
 * - 支持动态发现和加载工具
 * - 松耦合，可扩展
 * - Spring 自动注入所有实现类
 * 
 * 使用场景：
 * - 根据 Agent 配置动态加载工具
 * - 插件化工具扩展
 * - 条件化工具注册
 */
public interface ToolProvider {
    
    /**
     * 判断是否支持为该 Agent 提供工具
     * 
     * @param agentSpec Agent 规范
     * @param runtime 运行时上下文
     * @return 如果支持返回 true
     */
    boolean supports(AgentSpec agentSpec, Runtime runtime);
    
    /**
     * 创建工具实例列表
     * 
     * @param agentSpec Agent 规范
     * @param runtime 运行时上下文
     * @return 工具实例列表
     */
    List<Tool<?>> createTools(AgentSpec agentSpec, Runtime runtime);
    
    /**
     * 获取加载顺序
     * 数值越小越先加载
     * 
     * @return 加载顺序
     */
    default int getOrder() {
        return 100;
    }
    
    /**
     * 获取提供者名称（用于日志）
     * 
     * @return 提供者名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
