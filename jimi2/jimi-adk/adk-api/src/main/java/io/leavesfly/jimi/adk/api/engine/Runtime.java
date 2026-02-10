package io.leavesfly.jimi.adk.api.engine;

import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.session.Session;
import lombok.Getter;

/**
 * 运行时环境 - 为 Agent 执行提供运行时上下文
 * <p>
 * 组合 {@link RuntimeConfig}（纯配置）和 {@link InteractionContext}（人机交互），
 * 同时持有 {@link LLM} 和 {@link Session} 的引用。
 * </p>
 * <p>
 * 设计说明：Runtime 作为不可变的组合容器（Value Object），
 * 聚合了执行所需的全部运行时依赖。所有字段在构建后不应被修改。
 * </p>
 */
public final class Runtime {

    /** LLM 实例 */
    @Getter
    private LLM llm;

    /** 当前会话 */
    @Getter
    private Session session;

    /** 运行时配置（workDir, maxContextTokens） */
    @Getter
    private RuntimeConfig config;

    /** 交互上下文（HumanInteraction, Approval） */
    @Getter
    private InteractionContext interaction;

    /**
     * 获取工作目录的便捷方法
     * <p>
     * 等价于 {@code getConfig().getWorkDir()}，减少调用链长度。
     * 工作目录的权威来源是 {@link RuntimeConfig#getWorkDir()}。
     * </p>
     *
     * @return 当前工作目录
     */
    public java.nio.file.Path getWorkDir() {
        return config != null ? config.getWorkDir() : null;
    }

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

    // ==================== Builder ====================

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
