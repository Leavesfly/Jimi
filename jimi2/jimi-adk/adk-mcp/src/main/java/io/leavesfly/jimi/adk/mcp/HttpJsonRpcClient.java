package io.leavesfly.jimi.adk.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP JSON-RPC 客户端实现
 * 通过 HTTP 协议与远程 MCP 服务进行通信
 *
 * 核心功能：
 * 1. HTTP 通信：基于 WebClient 发送 HTTP POST 请求
 * 2. JSON-RPC 协议：实现 JSON-RPC 2.0 协议
 * 3. 超时控制：支持请求超时配置
 */
@Slf4j
public class HttpJsonRpcClient implements JsonRpcClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    /**
     * 构造 HTTP JSON-RPC 客户端
     *
     * @param url     MCP 服务的 HTTP URL
     * @param headers 自定义 HTTP 请求头
     */
    public HttpJsonRpcClient(String url, Map<String, String> headers) {
        this.objectMapper = new ObjectMapper();

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(url)
                .defaultHeader("Content-Type", "application/json");

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(builder::defaultHeader);
        }

        this.webClient = builder.build();
        log.info("HTTP JSON-RPC client initialized for URL: {}", url);
    }

    @Override
    public MCPSchema.InitializeResult initialize() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "jimi2", "version", "0.1.0"));

        JsonRpcMessage.Response response = sendRequest("initialize", params);

        if (response.getError() != null) {
            throw new RuntimeException("Initialize failed: " + response.getError().getMessage());
        }

        return objectMapper.convertValue(response.getResult(), MCPSchema.InitializeResult.class);
    }

    @Override
    public MCPSchema.ListToolsResult listTools() throws Exception {
        JsonRpcMessage.Response response = sendRequest("tools/list", Map.of());

        if (response.getError() != null) {
            throw new RuntimeException("List tools failed: " + response.getError().getMessage());
        }

        return objectMapper.convertValue(response.getResult(), MCPSchema.ListToolsResult.class);
    }

    @Override
    public MCPSchema.CallToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());

        JsonRpcMessage.Response response = sendRequest("tools/call", params);

        if (response.getError() != null) {
            throw new RuntimeException("Call tool failed: " + response.getError().getMessage());
        }

        Map<String, Object> result = response.getResult();
        List<MCPSchema.Content> contents = MCPContentParser.parseContents(result.get("content"));

        return MCPSchema.CallToolResult.builder()
                .content(contents)
                .isError((Boolean) result.get("isError"))
                .build();
    }

    /**
     * 发送 JSON-RPC 请求并等待响应
     */
    private JsonRpcMessage.Response sendRequest(String method, Map<String, Object> params) throws Exception {
        Object requestId = requestIdCounter.getAndIncrement();

        JsonRpcMessage.Request request = JsonRpcMessage.Request.builder()
                .jsonrpc("2.0")
                .id(requestId)
                .method(method)
                .params(params)
                .build();

        log.debug("Sending HTTP MCP request: method={}, id={}", method, requestId);

        Mono<JsonRpcMessage.Response> responseMono = webClient.post()
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonRpcMessage.Response.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .doOnSuccess(response -> log.debug("Received HTTP MCP response: id={}", response.getId()))
                .doOnError(error -> log.error("HTTP MCP request failed: {}", error.getMessage()));

        return responseMono.block();
    }

    @Override
    public void close() throws Exception {
        log.info("HTTP JSON-RPC client closed");
    }
}
