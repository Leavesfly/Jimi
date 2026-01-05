package io.leavesfly.jimi.plugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP客户端 - 通过StdIO与Jimi进程通信
 */
class McpClient(private val process: Process) : AutoCloseable {
    
    private val mapper = jacksonObjectMapper()
    private val reader = BufferedReader(InputStreamReader(process.inputStream))
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
    private val requestId = AtomicInteger(0)
    
    /**
     * 初始化MCP连接
     */
    fun initialize(): McpModels.InitializeResult {
        val response = sendRequest(
            "initialize",
            mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf(
                    "name" to "jimi-intellij-plugin",
                    "version" to "0.1.0"
                )
            )
        )
        
        return mapper.convertValue(response, McpModels.InitializeResult::class.java)
    }
    
    /**
     * 获取工具列表
     */
    fun listTools(): McpModels.ListToolsResult {
        val response = sendRequest("tools/list", emptyMap())
        return mapper.convertValue(response, McpModels.ListToolsResult::class.java)
    }
    
    /**
     * 调用工具
     */
    fun callTool(toolName: String, arguments: Map<String, Any?>): McpModels.CallToolResult {
        val response = sendRequest(
            "tools/call",
            mapOf(
                "name" to toolName,
                "arguments" to arguments
            )
        )
        
        return mapper.convertValue(response, McpModels.CallToolResult::class.java)
    }
    
    /**
     * 发送JSON-RPC请求并等待响应
     */
    private fun sendRequest(method: String, params: Map<String, Any?>): Map<String, Any?> {
        val id = requestId.incrementAndGet()
        
        val request = McpModels.JsonRpcRequest(
            id = id,
            method = method,
            params = params
        )
        
        val requestJson = mapper.writeValueAsString(request)
        
        synchronized(writer) {
            writer.write(requestJson)
            writer.write("\n")
            writer.flush()
        }
        
        val responseLine = synchronized(reader) {
            reader.readLine()
        }
        
        val response = mapper.readValue<McpModels.JsonRpcResponse>(responseLine)
        
        if (response.error != null) {
            throw RuntimeException("MCP Error: ${response.error.message}")
        }
        
        return response.result ?: emptyMap()
    }
    
    override fun close() {
        try {
            writer.close()
            reader.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
