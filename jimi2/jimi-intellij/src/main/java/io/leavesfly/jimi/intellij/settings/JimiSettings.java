package io.leavesfly.jimi.intellij.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Jimi 持久化设置
 * <p>
 * 通过 IntelliJ 持久化机制存储 API Key、provider、model 等配置。
 * 配置存储在 {@code jimi-settings.xml} 中。
 * </p>
 *
 * @author Jimi2 Team
 */
@Service(Service.Level.APP)
@State(name = "JimiSettings", storages = @Storage("jimi-settings.xml"))
public final class JimiSettings implements PersistentStateComponent<JimiSettings.State> {

    private State myState = new State();

    /**
     * 持久化状态
     */
    public static class State {
        /** LLM 提供商 (openai / qwen / deepseek / kimi / ollama) */
        public String provider = "openai";
        /** 模型名称 */
        public String model = "gpt-4o";
        /** API Key */
        public String apiKey = "";
        /** 自定义 Base URL（可选） */
        public String baseUrl = "";
        /** 温度 */
        public double temperature = 0.7;
        /** 最大 token */
        public int maxTokens = 4096;
        /** YOLO 模式（跳过工具确认） */
        public boolean yoloMode = false;
    }

    public static JimiSettings getInstance() {
        return ApplicationManager.getApplication().getService(JimiSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.myState = state;
    }

    // ---- 便捷访问方法 ----

    public String getProvider() { return myState.provider; }
    public void setProvider(String provider) { myState.provider = provider; }

    public String getModel() { return myState.model; }
    public void setModel(String model) { myState.model = model; }

    public String getApiKey() {
        // 环境变量优先
        String envKey = resolveApiKeyFromEnv();
        if (envKey != null && !envKey.isEmpty()) {
            return envKey;
        }
        return myState.apiKey;
    }
    public void setApiKey(String apiKey) { myState.apiKey = apiKey; }

    public String getBaseUrl() { return myState.baseUrl; }
    public void setBaseUrl(String baseUrl) { myState.baseUrl = baseUrl; }

    public double getTemperature() { return myState.temperature; }
    public void setTemperature(double temperature) { myState.temperature = temperature; }

    public int getMaxTokens() { return myState.maxTokens; }
    public void setMaxTokens(int maxTokens) { myState.maxTokens = maxTokens; }

    public boolean isYoloMode() { return myState.yoloMode; }
    public void setYoloMode(boolean yoloMode) { myState.yoloMode = yoloMode; }

    /**
     * 从环境变量解析 API Key
     * 优先级：JIMI_API_KEY → {PROVIDER}_API_KEY → OPENAI_API_KEY
     */
    private String resolveApiKeyFromEnv() {
        String jimiKey = System.getenv("JIMI_API_KEY");
        if (jimiKey != null && !jimiKey.isEmpty()) return jimiKey;

        String providerKey = System.getenv(myState.provider.toUpperCase() + "_API_KEY");
        if (providerKey != null && !providerKey.isEmpty()) return providerKey;

        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isEmpty()) return openaiKey;

        return null;
    }

    /**
     * 判断是否已配置有效 API Key
     */
    public boolean hasValidApiKey() {
        String key = getApiKey();
        return key != null && !key.isEmpty();
    }
}
