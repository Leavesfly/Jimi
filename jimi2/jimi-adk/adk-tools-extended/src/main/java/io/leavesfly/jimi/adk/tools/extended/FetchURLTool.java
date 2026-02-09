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
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * FetchURL 工具 - 抓取网页内容
 *
 * @author Jimi2 Team
 */
@Slf4j
public class FetchURLTool extends AbstractTool<FetchURLTool.Params> {

    private final WebClient webClient;

    public FetchURLTool(Runtime runtime) {
        super("fetch_url",
                "Fetch content from a URL",
                Params.class);

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(30));

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("URL to fetch")
        private String url;

        @JsonPropertyDescription("Max content length in characters. Default: 50000")
        @Builder.Default
        private int maxLength = 50000;
    }

    @Override
    public Mono<ToolResult> doExecute(Params params) {
        if (params.url == null || params.url.trim().isEmpty()) {
            return Mono.just(ToolResult.error("URL is required"));
        }

        return webClient.get()
                .uri(params.url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .map(content -> {
                    if (content.length() > params.maxLength) {
                        content = content.substring(0, params.maxLength) +
                                String.format("\n... (content truncated to %d chars)", params.maxLength);
                    }
                    return ToolResult.success(content);
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch URL: {}", params.url, e);
                    return Mono.just(ToolResult.error("Failed to fetch URL: " + e.getMessage()));
                });
    }
}
