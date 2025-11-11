package io.leavesfly.jimi.tool.web;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * URL 内容抓取工具
 * 从指定 URL 获取网页内容并提取主要文本
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FetchURL extends AbstractTool<FetchURL.Params> {
    
    private final WebClient webClient;
    
    /**
     * 抓取参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        /**
         * 要抓取的 URL
         */
        @JsonPropertyDescription("要获取内容的网页 URL，必须是完整的 HTTP 或 HTTPS 地址")
        private String url;
    }
    
    public FetchURL() {
        super(
            "FetchURL",
            "从网页获取并提取内容。"
            + "此工具从 URL 获取 HTML 内容并提取主要文本内容，"
            + "移除脚本、样式和格式。",
            Params.class
        );
        this.webClient = WebClient.builder()
            .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .build();
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.defer(() -> {
            // 验证参数
            if (params.url == null || params.url.trim().isEmpty()) {
                return Mono.just(ToolResult.error(
                    "URL parameter is required",
                    "Missing URL"
                ));
            }
            
            log.info("Fetching URL: {}", params.url);
            
            // 发送 HTTP 请求
            return webClient.get()
                .uri(params.url)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(html -> extractContent(html))
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("HTTP error while fetching URL: {} - {}", e.getStatusCode(), e.getMessage());
                    return Mono.just(ToolResult.error(
                        String.format("Failed to fetch URL. Status: %d. This may indicate the page is not accessible or the server is down.",
                            e.getStatusCode().value()),
                        "HTTP " + e.getStatusCode().value() + " error"
                    ));
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch URL", e);
                    return Mono.just(ToolResult.error(
                        "Failed to fetch URL due to network error: " + e.getMessage() + 
                        ". This may indicate the URL is invalid or the server is unreachable.",
                        "Network error"
                    ));
                });
        });
    }
    
    /**
     * 提取网页内容
     */
    private Mono<ToolResult> extractContent(String html) {
        try {
            if (html == null || html.trim().isEmpty()) {
                return Mono.just(ToolResult.ok(
                    "The response body is empty.",
                    "Empty response body"
                ));
            }
            
            // 使用 Jsoup 解析 HTML
            Document doc = Jsoup.parse(html);
            
            // 移除 script, style 等标签
            doc.select("script, style, nav, footer, header, aside").remove();
            
            // 提取文本内容
            String text = doc.body().text();
            
            // 清理空白
            text = text.replaceAll("\\s+", " ").trim();
            
            if (text.isEmpty()) {
                return Mono.just(ToolResult.error(
                    "Failed to extract meaningful content from the page. " +
                    "This may indicate the page content is not suitable for text extraction, " +
                    "or the page requires JavaScript to render its content.",
                    "No content extracted"
                ));
            }
            
            return Mono.just(ToolResult.ok(
                text,
                "The returned content is the main text content extracted from the page."
            ));
            
        } catch (Exception e) {
            log.error("Failed to extract content", e);
            return Mono.just(ToolResult.error(
                "Failed to extract content from HTML: " + e.getMessage(),
                "Extraction error"
            ));
        }
    }
}
