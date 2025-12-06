package io.leavesfly.jimi.engine.runtime;

import lombok.Data;

/**
 * 父级上下文（栈元素）
 * 用于递归/Subagent 场景的上下文恢复
 * 
 * 核心机制（ReCAP）：
 * 1. 在启动 Subagent 前，Push 父级上下文到栈
 * 2. Subagent 完成后，Pop 栈并结构化恢复父级状态
 * 3. 注入子目标完成摘要，保持语义连续性
 * 
 * @see <a href="https://github.com/ReCAP-Stanford/ReCAP">ReCAP: Recursive Context-Aware Reasoning and Planning</a>
 */
@Data
public class ParentContext {
    
    /**
     * 父级检查点 ID
     * 用于回退到父级上下文状态
     */
    private final int checkpointId;
    
    /**
     * 最近的思考内容
     * 从最后一条 assistant 消息中提取
     */
    private final String latestThought;
    
    /**
     * 递归深度
     * 用于控制最大递归层数
     */
    private final int depth;
    
    /**
     * 子目标描述
     * 传递给 Subagent 的任务描述
     */
    private final String subGoalDescription;
    
    /**
     * 保存时间戳（用于调试）
     */
    private final long timestamp;
    
    /**
     * 构造函数
     * 
     * @param checkpointId 检查点 ID
     * @param latestThought 最近思考
     * @param depth 递归深度
     * @param subGoalDescription 子目标描述
     */
    public ParentContext(int checkpointId, String latestThought, 
                        int depth, String subGoalDescription) {
        this.checkpointId = checkpointId;
        this.latestThought = latestThought;
        this.depth = depth;
        this.subGoalDescription = subGoalDescription;
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * 格式化为结构化注入文本
     * 用于恢复父级上下文时注入到消息历史
     * 
     * @return 结构化注入文本
     */
    public String formatForInjection() {
        return String.format("""
                === 父级上下文恢复 (深度: %d) ===
                子目标: %s
                之前的思考: %s
                """, depth, subGoalDescription, latestThought);
    }
}
