package io.leavesfly.jimi.mcp;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STDIO JSON-RPC 客户端实现
 * 通过标准输入输出与外部MCP服务进程通信
 */
@Slf4j
public class StdIoJsonRpcClient extends AbstractJsonRpcClient {

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private final Map<Object, JsonRpcMessage.Response> responseCache = new ConcurrentHashMap<>();
    private final Thread readerThread;
    private volatile boolean closed = false;

    /**
     * 构造 STDIO JSON-RPC 客户端
     *
     * @param command 启动命令，如 "node"、"python" 等
     * @param args    命令参数列表
     * @param env     环境变量映射，传递给子进程
     * @throws IOException 进程启动失败时抛出
     */
    public StdIoJsonRpcClient(String command, List<String> args, Map<String, String> env) throws IOException {
        super();

        ProcessBuilder pb = new ProcessBuilder();
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(command);
        if (args != null) {
            fullCommand.addAll(args);
        }
        pb.command(fullCommand);

        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }

        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        try {
            this.process = pb.start();
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            this.readerThread = new Thread(this::readLoop, "MCP-Reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        } catch (IOException e) {
            log.error("Failed to start MCP process: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    protected synchronized JsonRpcMessage.Response sendRequest(String method, Map<String, Object> params) throws Exception {
        Object requestId = requestIdCounter.getAndIncrement();

        JsonRpcMessage.Request request = JsonRpcMessage.Request.builder()
                .jsonrpc("2.0")
                .id(requestId)
                .method(method)
                .params(params)
                .build();

        String requestJson = objectMapper.writeValueAsString(request);
        log.debug("Sending MCP request: {}", requestJson);

        writer.write(requestJson);
        writer.write("\n");
        writer.flush();

        long startTime = System.currentTimeMillis();
        while (!responseCache.containsKey(requestId)) {
            if (closed) {
                throw new RuntimeException("Client closed");
            }
            if (System.currentTimeMillis() - startTime > 30000) {
                throw new RuntimeException("Request timeout");
            }
            Thread.sleep(100);
        }

        return responseCache.remove(requestId);
    }

    /**
     * 后台读取响应循环
     */
    private void readLoop() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                log.debug("Received MCP response: {}", line);
                try {
                    JsonRpcMessage.Response response = objectMapper.readValue(line, JsonRpcMessage.Response.class);
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
