package io.leavesfly.jimi.work.config;

import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.config.JimiConfig;

import java.nio.file.Path;

/**
 * Work 配置加载器
 * <p>
 * 委托 {@link JimiConfig} 完成通用配置加载，
 * 仅保留 Desktop 特有的配置扩展。
 * </p>
 */
public class WorkConfig {

    /** 底层统一配置 */
    private final JimiConfig jimiConfig;

    private WorkConfig(JimiConfig jimiConfig) {
        this.jimiConfig = jimiConfig;
    }

    /**
     * 加载配置
     */
    public static WorkConfig load(Path workDir) {
        JimiConfig base = JimiConfig.load(workDir);
        return new WorkConfig(base);
    }

    /**
     * 获取底层统一配置
     */
    public JimiConfig getJimiConfig() {
        return jimiConfig;
    }

    /**
     * 构建 LLMConfig
     */
    public LLMConfig toLLMConfig() {
        return jimiConfig.toLLMConfig();
    }

    public boolean isYoloMode() {
        return jimiConfig.isYoloMode();
    }

    public int getMaxContextTokens() {
        return jimiConfig.getMaxContextTokens();
    }
}
