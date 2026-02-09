package io.leavesfly.jimi.adk.tools.extended;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.tools.base.AbstractTool;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * WebSearch 工具 - 网络搜索（桩实现）
 * <p>
 * 当前版本为桩实现，需要外部搜索服务支持。
 * 可集成 Google Search API、DuckDuckGo API 等。
 * </p>
 *
 * @author Jimi2 Team
 */
@Slf4j
public class WebSearchTool extends AbstractTool<WebSearchTool.Params> {

    private final Runtime runtime;

    public WebSearchTool(Runtime runtime) {
        super("web_search",
                "Search the web (requires external search API configuration)",
                Params.class);
        this.runtime = runtime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("Search query")
        private String query;

        @JsonPropertyDescription("Number of results to return (1-20). Default: 5")
        @Builder.Default
        private int limit = 5;
    }

    @Override
    public Mono<ToolResult> doExecute(Params params) {
        // 桩实现：提示用户配置搜索服务
        String message = String.format(
                "Web search is not configured.\n\n" +
                "Query: %s\n\n" +
                "To enable web search, you need to:\n" +
                "1. Configure a search service provider (e.g., Google Custom Search, DuckDuckGo)\n" +
                "2. Set up API credentials in config.yaml\n" +
                "3. Restart the application\n\n" +
                "Alternatively, use 'fetch_url' tool if you have a specific URL to fetch.",
                params.query);

        log.warn("WebSearch tool called but not configured: {}", params.query);

        return Mono.just(ToolResult.success(message));
    }
}
