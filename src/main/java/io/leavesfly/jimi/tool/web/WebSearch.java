package io.leavesfly.jimi.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 搜索工具
 * 使用搜索服务（如 Moonshot Search）进行网页搜索
 */
@Slf4j
public class WebSearch extends AbstractTool<WebSearch.Params> {
    
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> customHeaders;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    /**
     * 搜索参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        /**
         * 搜索查询文本
         */
        private String query;
        
        /**
         * 返回结果数量（1-20）
         */
        @Builder.Default
        private int limit = 5;
        
        /**
         * 是否包含网页内容（会消耗大量 Token）
         */
        @Builder.Default
        private boolean includeContent = false;
    }
    
    /**
     * 搜索结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {
        private String siteName;
        private String title;
        private String url;
        private String snippet;
        private String content;
        private String date;
        private String icon;
        private String mime;
    }
    
    public WebSearch(String baseUrl, String apiKey, Map<String, String> customHeaders, ObjectMapper objectMapper) {
        super(
            "SearchWeb",
            "使用搜索服务搜索网络信息。"
            + "此工具允许您根据查询字符串搜索网页。"
            + "您可以控制结果数量以及是否包含页面内容。",
            Params.class
        );
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.customHeaders = customHeaders != null ? customHeaders : new HashMap<>();
        this.objectMapper = objectMapper;
        
        // 创建 WebClient
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("User-Agent", "Jimi/0.1.0")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.defer(() -> {
            // 验证配置
            if (baseUrl == null || baseUrl.isEmpty() || apiKey == null || apiKey.isEmpty()) {
                return Mono.just(ToolResult.error(
                    "Search service is not configured. You may want to try other methods to search.",
                    "Search service not configured"
                ));
            }
            
            // 验证参数
            if (params.query == null || params.query.trim().isEmpty()) {
                return Mono.just(ToolResult.error(
                    "Query parameter is required",
                    "Missing query"
                ));
            }
            
            if (params.limit < 1 || params.limit > 20) {
                return Mono.just(ToolResult.error(
                    "Limit must be between 1 and 20",
                    "Invalid limit"
                ));
            }
            
            log.info("Searching web for: {}", params.query);
            
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("text_query", params.query);
            requestBody.put("limit", params.limit);
            requestBody.put("enable_page_crawling", params.includeContent);
            requestBody.put("timeout_seconds", 30);
            
            // 发送请求
            return webClient.post()
                .headers(headers -> customHeaders.forEach(headers::add))
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(response -> parseSearchResults(response, params))
                .onErrorResume(e -> {
                    log.error("Failed to search web", e);
                    return Mono.just(ToolResult.error(
                        "Failed to search. This may indicate that the search service is currently unavailable. Error: " + e.getMessage(),
                        "Search failed"
                    ));
                });
        });
    }
    
    /**
     * 解析搜索结果
     */
    private Mono<ToolResult> parseSearchResults(JsonNode response, Params params) {
        try {
            JsonNode resultsNode = response.get("search_results");
            if (resultsNode == null || !resultsNode.isArray()) {
                return Mono.just(ToolResult.error(
                    "Failed to parse search results. Invalid response format.",
                    "Parse error"
                ));
            }
            
            List<SearchResult> results = new ArrayList<>();
            for (JsonNode resultNode : resultsNode) {
                SearchResult result = new SearchResult();
                result.setSiteName(resultNode.path("site_name").asText(""));
                result.setTitle(resultNode.path("title").asText(""));
                result.setUrl(resultNode.path("url").asText(""));
                result.setSnippet(resultNode.path("snippet").asText(""));
                result.setContent(resultNode.path("content").asText(""));
                result.setDate(resultNode.path("date").asText(""));
                result.setIcon(resultNode.path("icon").asText(""));
                result.setMime(resultNode.path("mime").asText(""));
                results.add(result);
            }
            
            if (results.isEmpty()) {
                return Mono.just(ToolResult.ok(
                    "No results found for the query.",
                    "No results"
                ));
            }
            
            // 格式化输出
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                if (i > 0) {
                    output.append("---\n\n");
                }
                
                SearchResult result = results.get(i);
                output.append("Title: ").append(result.getTitle()).append("\n");
                output.append("Date: ").append(result.getDate()).append("\n");
                output.append("URL: ").append(result.getUrl()).append("\n");
                output.append("Summary: ").append(result.getSnippet()).append("\n\n");
                
                if (result.getContent() != null && !result.getContent().isEmpty()) {
                    output.append(result.getContent()).append("\n\n");
                }
            }
            
            return Mono.just(ToolResult.ok(output.toString(), ""));
            
        } catch (Exception e) {
            log.error("Failed to parse search results", e);
            return Mono.just(ToolResult.error(
                "Failed to parse search results. Error: " + e.getMessage(),
                "Parse error"
            ));
        }
    }
}
