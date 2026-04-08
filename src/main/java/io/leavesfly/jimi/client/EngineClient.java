package io.leavesfly.jimi.client;

import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.config.info.ThemeConfig;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.message.WireMessage;
import io.leavesfly.jimi.wire.message.request.ContextQueryRequest;
import io.leavesfly.jimi.wire.message.request.RuntimeInfoQueryRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;

/**
 * EngineClient 接口
 * <p>
 * UI层与引擎层交互的统一抽象，遵循初始化态与运行态分离原则：
 * - 初始化阶段：创建实例时完成所有配置的获取和缓存
 * - 运行阶段：通过Wire消息与引擎交互
 * <p>
 * 设计原则：
 * - UI层不直接依赖JimiEngine
 * - 配置数据在初始化时获取，运行时直接返回（无需消息交互）
 * - 动态数据和操作通过Wire消息交互
 */
public interface EngineClient {

    // ==================== 初始化时获取的配置（getter，无需消息交互） ====================

    /**
     * 获取Agent名称
     */
    String getAgentName();

    /**
     * 获取模型名称
     */
    String getModelName();

    /**
     * 获取工作目录
     */
    Path getWorkDir();

    /**
     * 获取Shell UI配置
     */
    ShellUIConfig getShellUIConfig();

    /**
     * 获取主题配置
     */
    ThemeConfig getThemeConfig();

    /**
     * 获取是否为YOLO模式
     */
    boolean isYoloMode();

    // ==================== 运行时操作（通过Wire消息） ====================

    /**
     * 执行命令（文本输入）
     *
     * @param input 用户输入文本
     * @return 完成的Mono
     */
    Mono<Void> runCommand(String input);

    /**
     * 执行命令（多部分内容输入）
     *
     * @param input 用户输入内容部分列表
     * @return 完成的Mono
     */
    Mono<Void> runCommand(List<ContentPart> input);

    /**
     * 执行工具
     *
     * @param toolName  工具名称
     * @param arguments 工具参数（JSON格式）
     * @return 工具执行结果
     */
    Mono<ToolResult> executeTool(String toolName, String arguments);

    /**
     * 检查工具是否存在
     *
     * @param toolName 工具名称
     * @return 是否存在
     */
    boolean hasTool(String toolName);

    // ==================== 运行时查询（通过Wire消息） ====================

    /**
     * 获取当前Token计数
     */
    int getTokenCount();

    /**
     * 获取历史消息数量
     */
    int getHistorySize();

    /**
     * 获取所有已注册工具的名称列表
     *
     * @return 工具名称列表
     */
    List<String> getToolNames();

    /**
     * 获取上下文信息（Token数、历史消息数、检查点数）
     *
     * @return 上下文信息
     */
    ContextQueryRequest.ContextInfo getContextInfo();

    /**
     * 重置上下文（清空历史消息，回退到初始状态）
     *
     * @return 完成的 Mono
     */
    Mono<Void> resetContext();

    /**
     * 获取运行时信息（LLM 状态、会话信息、工作目录等）
     *
     * @return 运行时信息
     */
    RuntimeInfoQueryRequest.RuntimeInfo getRuntimeInfo();

    /**
     * 更新主题配置
     *
     * @param themeName   主题名称
     * @param themeConfig 主题配置
     */
    void updateTheme(String themeName, ThemeConfig themeConfig);

    // ==================== 会话管理 ====================

    /**
     * 开启新会话
     * 创建新的 Session 并重置上下文
     *
     * @return 完成的 Mono
     */
    Mono<Void> newSession();

    /**
     * 获取当前会话 ID
     */
    String getSessionId();

    // ==================== Wire订阅 ====================

    /**
     * 订阅引擎事件（初始化时建立订阅）
     *
     * @return 消息流
     */
    Flux<WireMessage> subscribe();

}
