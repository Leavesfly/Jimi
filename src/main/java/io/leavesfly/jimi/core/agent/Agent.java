package io.leavesfly.jimi.core.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 实体
 * 表示一个完整的 Agent，包含名称、系统提示词和工具集
 * 
 * 设计特性：
 * 1. **无状态配置对象**：只包含配置数据，不包含运行时状态或会话信息
 * 2. **不可变性**：一旦创建，字段值不应被修改（虽然 @Data 提供了 setter，但应避免使用）
 * 3. **缓存安全**：可安全地在 AgentRegistry 中缓存，在多个 Session/JimiRuntime 之间共享
 * 4. **线程安全**：不包含可变状态，多线程环境下只读访问安全
 * 
 * 注意事项：
 * - 请使用 @Builder 构造 Agent 实例
 * - 创建后不应修改字段值，保持不可变性
 * - tools 列表虽然可变，但建议在构造时一次性设置
 * 
 * @see AgentRegistry Agent 的加载和缓存管理
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent {
    
    /**
     * Agent 名称
     */
    private String name;
    
    /**
     * 系统提示词
     */
    private String systemPrompt;
    
    /**
     * 指定的模型名称（可选）
     * 如果设置，则优先使用此模型，否则使用全局配置的默认模型
     */
    private String model;
    
    /**
     * 工具列表（工具类的完整类名）
     */
    @Builder.Default
    private List<String> tools = new ArrayList<>();
}
