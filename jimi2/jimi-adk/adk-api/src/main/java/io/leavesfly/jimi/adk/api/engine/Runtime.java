package io.leavesfly.jimi.adk.api.engine;

import io.leavesfly.jimi.adk.api.interaction.Approval;
import io.leavesfly.jimi.adk.api.interaction.HumanInteraction;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.session.Session;
import lombok.Getter;

import java.nio.file.Path;

/**
 * 运行时环境 - 为 Agent 执行提供运行时上下文
 * <p>
 * 组合 {@link RuntimeConfig}（纯配置）和 {@link InteractionContext}（人机交互），
 * 同时保留向后兼容的快捷 getter 方法。
 * </p>
 */
public class Runtime {

    /** LLM 实例 */
    @Getter
    private LLM llm;

    /** 当前会话 */
    @Getter
    private Session session;

    /** 运行时配置（workDir, yoloMode, maxContextTokens） */
    @Getter
    private RuntimeConfig config;

    /** 交互上下文（HumanInteraction, Approval） */
    @Getter
    private InteractionContext interaction;

    public Runtime() {
        this.config = new RuntimeConfig();
        this.interaction = new InteractionContext();
    }

    public Runtime(LLM llm, Session session, RuntimeConfig config, InteractionContext interaction) {
        this.llm = llm;
        this.session = session;
        this.config = config != null ? config : new RuntimeConfig();
        this.interaction = interaction != null ? interaction : new InteractionContext();
    }

    // ==================== 向后兼容的代理方法 ====================

    /** 获取工作目录 */
    public Path getWorkDir() {
        return config.getWorkDir();
    }

    /** 是否启用 YOLO 模式（跳过审批） */
    public boolean isYoloMode() {
        return config.isYoloMode();
    }

    /** 上下文最大 Token 数 */
    public int getMaxContextTokens() {
        return config.getMaxContextTokens();
    }

    /** 人机交互接口 */
    public HumanInteraction getHumanInteraction() {
        return interaction.getHumanInteraction();
    }

    /** 审批服务 */
    public Approval getApproval() {
        return interaction.getApproval();
    }

    /** 获取会话 ID */
    public String getSessionId() {
        return session != null ? session.getId() : null;
    }

    // ==================== Builder（向后兼容） ====================

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runtime 构建器
     * <p>
     * 使用新风格 API：{@code .config(runtimeConfig).interaction(interactionCtx)}
     * </p>
     */
    public static class Builder {
        private LLM llm;
        private Session session;
        private RuntimeConfig config;
        private InteractionContext interaction;

        public Builder llm(LLM llm) { this.llm = llm; return this; }
        public Builder session(Session session) { this.session = session; return this; }
        public Builder config(RuntimeConfig config) { this.config = config; return this; }
        public Builder interaction(InteractionContext interaction) { this.interaction = interaction; return this; }

        public Runtime build() {
            return new Runtime(llm, session, config, interaction);
        }
    }
}
