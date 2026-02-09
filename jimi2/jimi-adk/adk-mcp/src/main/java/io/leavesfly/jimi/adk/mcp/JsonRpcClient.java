package io.leavesfly.jimi.adk.mcp;

import java.util.Map;

/**
 * JSON-RPC 客户端接口
 * 定义 MCP 通信的统一接口，支持 STDIO 和 HTTP 两种传输方式
 */
public interface JsonRpcClient extends AutoCloseable {

    /**
     * 初始化连接
     * 发送 initialize 请求到 MCP 服务，建立通信协议
     *
     * @return 初始化结果，包含服务端信息和能力
     * @throws Exception 初始化失败时抛出
     */
    MCPSchema.InitializeResult initialize() throws Exception;

    /**
     * 获取工具列表
     * 调用 tools/list 方法，查询服务端提供的所有工具
     *
     * @return 工具列表结果
     * @throws Exception 查询失败时抛出
     */
    MCPSchema.ListToolsResult listTools() throws Exception;

    /**
     * 调用工具
     * 执行指定名称的工具，传入参数获取结果
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     * @throws Exception 执行失败时抛出
     */
    MCPSchema.CallToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception;
}
