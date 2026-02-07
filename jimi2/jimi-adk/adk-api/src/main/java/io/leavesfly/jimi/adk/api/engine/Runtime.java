package io.leavesfly.jimi.adk.api.engine;

import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.session.Session;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 运行时环境 - 为 Agent 执行提供运行时上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Runtime {
    
    /**
     * LLM 实例
     */
    private LLM llm;
    
    /**
     * 当前会话
     */
    private Session session;
    
    /**
     * 工作目录
     */
    private Path workDir;
    
    /**
     * 是否启用 YOLO 模式（跳过审批）
     */
    @Builder.Default
    private boolean yoloMode = false;
    
    /**
     * 上下文最大 Token 数
     */
    @Builder.Default
    private int maxContextTokens = 100000;
    
    /**
     * 获取会话 ID
     */
    public String getSessionId() {
        return session != null ? session.getId() : null;
    }
}
