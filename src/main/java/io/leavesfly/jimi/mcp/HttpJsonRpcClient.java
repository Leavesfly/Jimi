package io.leavesfly.jimi.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP JSON-RPC 客户端实现
 * 通过 HTTP 协议与远程 MCP 服务通信
 */
@Slf4j
public class HttpJsonRpcClient extends AbstractJsonRpcClient {

    private final WebClient webClient;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    /**
     * 构造 HTTP JSON-RPC 客户端
     *
     * @param url     MCP 服务的 HTTP URL
     * @param headers 自定义 HTTP 请求头
     */
    public HttpJsonRpcClient(String url, Map<String, String> headers) {
        super();

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
    protected JsonRpcMessage.Response sendRequest(String method, Map<String, Object> params) throws Exception {
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
