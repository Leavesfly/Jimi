package io.leavesfly.jimi.plugin

/**
 * MCP协议相关数据模型
 */
object McpModels {
    
    data class JsonRpcRequest(
        val jsonrpc: String = "2.0",
        val id: Any,
        val method: String,
        val params: Map<String, Any?>
    )
    
    data class JsonRpcResponse(
        val jsonrpc: String,
        val id: Any?,
        val result: Map<String, Any?>? = null,
        val error: Error? = null
    )
    
    data class Error(
        val code: Int,
        val message: String,
        val data: Any? = null
    )
    
    data class InitializeResult(
        val protocolVersion: String,
        val capabilities: Map<String, Any?>,
        val serverInfo: Map<String, Any?>
    )
    
    data class Tool(
        val name: String,
        val description: String,
        val inputSchema: Map<String, Any?>
    )
    
    data class ListToolsResult(
        val tools: List<Tool>
    )
    
    data class CallToolResult(
        val content: List<Content>,
        val isError: Boolean?
    )
    
    data class Content(
        val type: String,
        val text: String
    )
}
