package io.leavesfly.jimi.tool.web;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.ConfigLoader;
import io.leavesfly.jimi.config.WebSearchConfig;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.AddressResolverGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 搜索工具
 * 使用搜索服务（如 Moonshot Search）进行网页搜索
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class WebSearch extends AbstractTool<WebSearch.Params> {
    
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> customHeaders;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    /**
     * 默认构造函数（用于 Spring Bean）
     * 从配置文件读取 WebSearch 配置
     */
    @Autowired
    public WebSearch(ConfigLoader configLoader, ObjectMapper objectMapper) {
        this(loadConfig(configLoader), objectMapper);
    }
    
    /**
     * 从 ConfigLoader 加载 WebSearch 配置
     */
    private static WebSearchConfig loadConfig(ConfigLoader configLoader) {
        try {
            return configLoader.loadConfig(null).getWebSearch();
        } catch (Exception e) {
            log.warn("Failed to load WebSearch config, using empty config: {}", e.getMessage());
            return new WebSearchConfig();
        }
    }
    
    /**
     * 从 WebSearchConfig 创建 WebSearch 实例
     */
    private WebSearch(WebSearchConfig config, ObjectMapper objectMapper) {
        this(config.getBaseUrl(), 
             config.getApiKey(), 
             config.getCustomHeaders(), 
             objectMapper);
    }
    
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
        @JsonPropertyDescription("要搜索的关键词或短语")
        private String query;
        
        /**
         * 返回结果数量（1-20）
         */
        @JsonPropertyDescription("返回的搜索结果数量，取值范围 1-20。默认为 5")
        @Builder.Default
        private int limit = 5;
        
        /**
         * 是否包含网页内容（会消耗大量 Token）
         */
        @JsonPropertyDescription("是否爬取并包含网页的完整内容。注意：开启后会显著增加 Token 消耗。默认为 false")
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
        
        // 创建 WebClient（仅在配置有效时）
        if (baseUrl != null && !baseUrl.isEmpty() && apiKey != null && !apiKey.isEmpty()) {
            // 配置 HttpClient 使用 JVM 的原生 DNS 解析器
            // 这样可以避免 Netty DNS 解析器在某些网络环境（如公司内网）下的问题
            // 使用 DefaultAddressResolverGroup.INSTANCE 强制使用 JVM 的 InetAddress DNS 解析
            HttpClient httpClient = HttpClient.create()
                .resolver(AddressResolverGroup.DEFAULT);
            
            this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", "Jimi/0.1.0")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        } else {
            this.webClient = null;
        }
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
            requestBody.put("query", params.query);
            requestBody.put("count", params.limit);
            if (params.includeContent) {
                requestBody.put("summary", true);
            }
            
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
            // 博查AI返回的是 data.webPages.value 数组
            JsonNode dataNode = response.get("data");
            if (dataNode == null) {
                return Mono.just(ToolResult.error(
                    "Failed to parse search results. Invalid response format.",
                    "Parse error"
                ));
            }
            
            JsonNode webPagesNode = dataNode.get("webPages");
            if (webPagesNode == null) {
                return Mono.just(ToolResult.error(
                    "Failed to parse search results. Invalid response format.",
                    "Parse error"
                ));
            }
            
            JsonNode resultsNode = webPagesNode.get("value");
            if (resultsNode == null || !resultsNode.isArray()) {
                return Mono.just(ToolResult.error(
                    "Failed to parse search results. Invalid response format.",
                    "Parse error"
                ));
            }
            
            List<SearchResult> results = new ArrayList<>();
            for (JsonNode resultNode : resultsNode) {
                SearchResult result = new SearchResult();
                result.setSiteName(resultNode.path("siteName").asText(""));
                result.setTitle(resultNode.path("name").asText(""));
                result.setUrl(resultNode.path("url").asText(""));
                result.setSnippet(resultNode.path("snippet").asText(""));
                result.setContent(resultNode.path("summary").asText(""));
                result.setDate(resultNode.path("datePublished").asText(""));
                result.setIcon(resultNode.path("siteIcon").asText(""));
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
                if (result.getDate() != null && !result.getDate().isEmpty()) {
                    output.append("Date: ").append(result.getDate()).append("\n");
                }
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
