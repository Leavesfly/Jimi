package io.leavesfly.jimi.wire.message.request;

import io.leavesfly.jimi.config.info.ThemeConfig;
import io.leavesfly.jimi.wire.message.WireRequest;
import lombok.Getter;

/**
 * 主题更新请求
 * <p>
 * Client 通过此请求让 Engine 更新主题配置。
 */
@Getter
public class ThemeUpdateRequest extends WireRequest<Void> {

    private final String themeName;
    private final ThemeConfig themeConfig;

    public ThemeUpdateRequest(String themeName, ThemeConfig themeConfig) {
        this.themeName = themeName;
        this.themeConfig = themeConfig;
    }

    @Override
    public String getMessageType() {
        return "request.theme_update";
    }
}
