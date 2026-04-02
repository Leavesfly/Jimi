package io.leavesfly.jimi.tool.provider;

import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.core.team.TeamAgentTool;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * TeamAgentTool 工具提供者
 * <p>
 * 职责：
 * - 检测 Agent 是否配置了 team（Agent Teams）
 * - 创建 TeamAgentTool 工具实例
 * <p>
 * 加载条件：
 * - Agent 的 team 配置不为空且 teammates 列表不为空
 */
@Slf4j
@Component
public class TeamToolProvider implements ToolProvider {

    private final ApplicationContext applicationContext;

    @Autowired
    public TeamToolProvider(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public boolean supports(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        return agentSpec.getTeam() != null
                && agentSpec.getTeam().getTeammates() != null
                && !agentSpec.getTeam().getTeammates().isEmpty();
    }

    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        log.info("Creating TeamAgentTool with {} teammates",
                agentSpec.getTeam().getTeammates().size());

        TeamAgentTool teamAgentTool = applicationContext.getBean(TeamAgentTool.class);
        teamAgentTool.setRuntimeParams(agentSpec, jimiRuntime);

        return Collections.singletonList(teamAgentTool);
    }

    @Override
    public int getOrder() {
        return 55;
    }
}
