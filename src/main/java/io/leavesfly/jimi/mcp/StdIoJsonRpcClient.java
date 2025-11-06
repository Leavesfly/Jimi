package io.leavesfly.jimi.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STDIO JSON-RPC 客户端实现
 * 通过标准输入输出与外部MCP服务进行通信
 * 
 * 核心功能：
 * 1. 进程管理：通过ProcessBuilder启动外部MCP服务进程
 * 2. 双向通信：使用BufferedReader/Writer进行JSON-RPC消息交换
 * 3. 异步处理：后台线程读取响应，缓存到Map中
 * 4. 请求匹配：基于id匹配请求和响应
 */
@Slf4j
public class StdIoJsonRpcClient implements JsonRpcClient {
    
    /** 外部MCP服务进程 */
    private final Process process;
    /** 向进程写入数据的Writer */
    private final BufferedWriter writer;
    /** 从进程读取数据的Reader */
    private final BufferedReader reader;
    /** JSON序列化/反序列化工具 */
    private final ObjectMapper objectMapper;
    /** 请求ID计数器，生成唯一请求ID */
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    /** 响应缓存，存储接收到的响应消息 */
    private final Map<Object, JsonRpcMessage.Response> responseCache = new ConcurrentHashMap<>();
    /** 后台读取线程，持续监听进程输出 */
    private final Thread readerThread;
    /** 关闭标志，用于停止读取线程 */
    private volatile boolean closed = false;

    /**
     * 构造STDIO JSON-RPC客户端
     * 
     * @param command 启动命令，如"node"、"python"等
     * @param args 命令参数列表，如["server.js"]
     * @param env 环境变量映射，传递给子进程
     * @throws IOException 进程启动失败时抛出
     */
    public StdIoJsonRpcClient(String command, List<String> args, Map<String, String> env) throws IOException {
        this.objectMapper = new ObjectMapper();
        
        // 构建进程启动器
        ProcessBuilder pb = new ProcessBuilder();
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(command);
        if (args != null) {
            fullCommand.addAll(args);
        }
        pb.command(fullCommand);
        
        // 设置环境变量
        if (env != null && !env.isEmpty()) {
            Map<String, String> processEnv = pb.environment();
            processEnv.putAll(env);
        }
        
        // 将进程的错误输出重定向到父进程，方便调试
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        
        try {
            // 启动进程
            this.process = pb.start();
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            // 启动后台读取线程，持续监听进程输出
            this.readerThread = new Thread(this::readLoop, "MCP-Reader");
            this.readerThread.setDaemon(true);  // 设置为守护线程，主程序退出时自动结束
            this.readerThread.start();
            
        } catch (IOException e) {
            log.error("Failed to start MCP process: {}", e.getMessage());
            throw e;
        }
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
    private synchronized JsonRpcMessage.Response sendRequest(String method, Map<String, Object> params) throws Exception {
        // 生成唯一请求ID
        Object requestId = requestIdCounter.getAndIncrement();
        
        // 构建JSON-RPC请求消息
        JsonRpcMessage.Request request = JsonRpcMessage.Request.builder()
                .jsonrpc("2.0")
                .id(requestId)
                .method(method)
                .params(params)
                .build();
        
        String requestJson = objectMapper.writeValueAsString(request);
        
        log.debug("Sending MCP request: {}", requestJson);
        
        // 写入进程的标准输入，添加换行符作为消息分隔符
        writer.write(requestJson);
        writer.write("\n");
        writer.flush();
        
        // 等待响应（最多30秒）
        long startTime = System.currentTimeMillis();
        while (!responseCache.containsKey(requestId)) {
            if (closed) {
                throw new RuntimeException("Client closed");
            }
            if (System.currentTimeMillis() - startTime > 30000) {
                throw new RuntimeException("Request timeout");
            }
            Thread.sleep(100);  // 等待100ms后再次检查
        }
        
        // 从缓存中获取并移除响应
        return responseCache.remove(requestId);
    }

    /**
     * 后台读取响应循环
     * 持续从进程输出流读取JSON-RPC响应，并缓存到responseCache中
     */
    private void readLoop() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                // 跳过空行
                if (line.trim().isEmpty()) {
                    continue;
                }
                
                log.debug("Received MCP response: {}", line);
                
                try {
                    // 解析JSON响应
                    JsonRpcMessage.Response response = objectMapper.readValue(line, JsonRpcMessage.Response.class);
                    // 根据id缓存响应
                    if (response.getId() != null) {
                        responseCache.put(response.getId(), response);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse response: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!closed) {
                log.error("Error reading from MCP process: {}", e.getMessage());
            }
        }
    }



    /**
     * 关闭客户端连接
     * 释放所有资源，包括进程、流和线程
     */
    @Override
    public void close() throws Exception {
        closed = true;
        
        if (writer != null) {
            writer.close();
        }
        if (reader != null) {
            reader.close();
        }
        if (process != null) {
            process.destroy();
            process.waitFor();
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
