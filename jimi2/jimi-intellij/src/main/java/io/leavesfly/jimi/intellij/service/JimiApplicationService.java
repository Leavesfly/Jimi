package io.leavesfly.jimi.intellij.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import io.leavesfly.jimi.intellij.settings.JimiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jimi 应用级服务
 * <p>
 * 管理全局 LLMFactory 和 LLM 实例，根据 {@link JimiSettings} 创建 LLM。
 * </p>
 *
 * @author Jimi2 Team
 */
@Service(Service.Level.APP)
public final class JimiApplicationService {

    private static final Logger log = LoggerFactory.getLogger(JimiApplicationService.class);

    private final LLMFactory llmFactory;

    public JimiApplicationService() {
        this.llmFactory = new LLMFactory();
        log.info("Jimi 应用服务已初始化");
    }

    /**
     * 获取当前配置对应的 LLM 实例
     * <p>
     * 基于 {@link JimiSettings} 中的 provider / model / apiKey 构建 {@link LLMConfig}，
     * 通过 {@link LLMFactory} 获取（带缓存）。
     * </p>
     *
     * @return LLM 实例；若未配置 API Key 则返回 null
     */
    public LLM getOrCreateLLM() {
        JimiSettings settings = JimiSettings.getInstance();

        if (!settings.hasValidApiKey()) {
            log.warn("未配置有效的 API Key，请在 Settings → Tools → Jimi AI Assistant 中配置");
            return null;
        }

        LLMConfig.LLMConfigBuilder builder = LLMConfig.builder()
                .provider(settings.getProvider())
                .model(settings.getModel())
                .apiKey(settings.getApiKey())
                .temperature(settings.getTemperature())
                .maxTokens(settings.getMaxTokens());

        String baseUrl = settings.getBaseUrl();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        LLMConfig config = builder.build();
        return llmFactory.create(config);
    }

    /**
     * 清除 LLM 缓存（设置变更后调用）
     */
    public void clearCache() {
        llmFactory.clearCache();
        log.info("LLM 缓存已清空");
    }

    public static JimiApplicationService getInstance() {
        return ApplicationManager.getApplication().getService(JimiApplicationService.class);
    }
}
