package io.leavesfly.jimi.mcp;

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
 * 通过HTTP协议与远程MCP服务进行通信
 * 
 * 核心功能：
 * 1. HTTP通信：基于WebClient发送HTTP POST请求
 * 2. JSON-RPC协议：实现JSON-RPC 2.0协议
 * 3. 请求管理：生成唯一请求ID
 * 4. 超时控制：支持请求超时配置
 */
@Slf4j
public class HttpJsonRpcClient implements JsonRpcClient {
    
    /** WebClient实例用于HTTP通信 */
    private final WebClient webClient;
    /** JSON序列化/反序列化工具 */
    private final ObjectMapper objectMapper;
    /** 请求ID计数器，生成唯一请求ID */
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    /** 请求超时时间（秒） */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    
    /**
     * 构造HTTP JSON-RPC客户端
     * 
     * @param url MCP服务的HTTP URL
     * @param headers 自定义HTTP请求头
     */
    public HttpJsonRpcClient(String url, Map<String, String> headers) {
        this.objectMapper = new ObjectMapper();
        
        // 构建WebClient
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(url)
                .defaultHeader("Content-Type", "application/json");
        
        // 添加自定义请求头
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(builder::defaultHeader);
        }
        
        this.webClient = builder.build();
        
        log.info("HTTP JSON-RPC client initialized for URL: {}", url);
    }
    
    /**
     * 初始化连接
     * 发送initialize请求到MCP服务，建立通信协议
     * 
     * @return 初始化结果，包含服务端信息和能力
     * @throws Exception 初始化失败时抛出
     */
    public MCPSchema.InitializeResult initialize() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());  // 客户端能力声明，目前为空
        params.put("clientInfo", Map.of("name", "jimi", "version", "0.1.0"));
        
        JsonRpcMessage.Response response = sendRequest("initialize", params);
        
        if (response.getError() != null) {
            throw new RuntimeException("Initialize failed: " + response.getError().getMessage());
        }
        
        return objectMapper.convertValue(response.getResult(), MCPSchema.InitializeResult.class);
    }
    
    /**
     * 获取工具列表
     * 调用tools/list方法，查询服务端提供的所有工具
     * 
     * @return 工具列表结果
     * @throws Exception 查询失败时抛出
     */
    public MCPSchema.ListToolsResult listTools() throws Exception {
        JsonRpcMessage.Response response = sendRequest("tools/list", Map.of());
        
        if (response.getError() != null) {
            throw new RuntimeException("List tools failed: " + response.getError().getMessage());
        }
        
        return objectMapper.convertValue(response.getResult(), MCPSchema.ListToolsResult.class);
    }
    
    /**
     * 调用工具
     * 执行指定名称的工具，传入参数获取结果
     * 
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     * @throws Exception 执行失败时抛出
     */
    public MCPSchema.CallToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());
        
        JsonRpcMessage.Response response = sendRequest("tools/call", params);
        
        if (response.getError() != null) {
            throw new RuntimeException("Call tool failed: " + response.getError().getMessage());
        }
        
        // 解析content字段，将JSON数据转换为Content对象列表
        Map<String, Object> result = response.getResult();
        List<MCPSchema.Content> contents = MCPContentParser.parseContents(result.get("content"));
        
        return MCPSchema.CallToolResult.builder()
                .content(contents)
                .isError((Boolean) result.get("isError"))
                .build();
    }
    
    /**
     * 发送JSON-RPC请求并等待响应
     * 
     * @param method JSON-RPC方法名
     * @param params 方法参数
     * @return 服务端返回的响应
     * @throws Exception 请求失败或超时时抛出
     */
    private JsonRpcMessage.Response sendRequest(String method, Map<String, Object> params) throws Exception {
        // 生成唯一请求ID
        Object requestId = requestIdCounter.getAndIncrement();
        
        // 构建JSON-RPC请求消息
        JsonRpcMessage.Request request = JsonRpcMessage.Request.builder()
                .jsonrpc("2.0")
                .id(requestId)
                .method(method)
                .params(params)
                .build();
        
        log.debug("Sending HTTP MCP request: method={}, id={}", method, requestId);
        
        // 发送HTTP POST请求
        Mono<JsonRpcMessage.Response> responseMono = webClient.post()
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonRpcMessage.Response.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .doOnSuccess(response -> log.debug("Received HTTP MCP response: id={}", response.getId()))
                .doOnError(error -> log.error("HTTP MCP request failed: {}", error.getMessage()));
        
        // 阻塞等待响应
        return responseMono.block();
    }
    
    /**
     * 关闭客户端连接
     * HTTP客户端无需显式关闭，此方法仅用于实现AutoCloseable接口
     */
    @Override
    public void close() throws Exception {
        log.info("HTTP JSON-RPC client closed");
        // WebClient无需显式关闭
    }
}
