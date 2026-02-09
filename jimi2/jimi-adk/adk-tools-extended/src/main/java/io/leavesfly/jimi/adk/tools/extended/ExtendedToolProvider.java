package io.leavesfly.jimi.adk.tools.extended;

import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 扩展工具提供器
 * <p>
 * 通过 SPI 机制自动发现并提供扩展工具：
 * - str_replace_file: 字符串替换编辑文件
 * - grep: 正则搜索文件内容
 * - fetch_url: 抓取网页内容
 * - patch_file: 应用 unified diff patch
 * - set_todo_list: 管理待办事项列表
 * - web_search: 网络搜索（桩实现）
 * </p>
 *
 * @author Jimi2 Team
 */
public class ExtendedToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ExtendedToolProvider.class);

    @Override
    public boolean supports(AgentSpec agentSpec, Runtime runtime) {
        // 所有 Agent 默认支持扩展工具
        return true;
    }

    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, Runtime runtime) {
        List<Tool<?>> tools = new ArrayList<>();

        tools.add(new StrReplaceFileTool(runtime));
        tools.add(new GrepTool(runtime));
        tools.add(new FetchURLTool(runtime));
        tools.add(new PatchFileTool(runtime));
        tools.add(new SetTodoListTool(runtime));
        tools.add(new WebSearchTool(runtime));
        tools.add(new AskHumanTool(runtime));

        log.debug("ExtendedToolProvider 提供 {} 个工具", tools.size());
        return tools;
    }
}
