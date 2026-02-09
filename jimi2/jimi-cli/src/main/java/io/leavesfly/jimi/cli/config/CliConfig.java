package io.leavesfly.jimi.cli.config;

import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.config.JimiConfig;

import java.nio.file.Path;

/**
 * CLI 配置加载器
 * <p>
 * 委托 {@link JimiConfig} 完成通用配置加载，
 * 仅保留 CLI 特有的配置扩展。
 * </p>
 */
public class CliConfig {

    /** 底层统一配置 */
    private final JimiConfig jimiConfig;

    private CliConfig(JimiConfig jimiConfig) {
        this.jimiConfig = jimiConfig;
    }

    /**
     * 加载配置
     *
     * @param workDir 工作目录
     * @return 配置实例
     */
    public static CliConfig load(Path workDir) {
        JimiConfig base = JimiConfig.load(workDir);
        return new CliConfig(base);
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

    /**
     * 是否为 YOLO 模式
     */
    public boolean isYoloMode() {
        return jimiConfig.isYoloMode();
    }

    /**
     * 获取最大上下文 Token 数
     */
    public int getMaxContextTokens() {
        return jimiConfig.getMaxContextTokens();
    }
}
