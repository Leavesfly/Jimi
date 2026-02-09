package io.leavesfly.jimi.web.controller;

import io.leavesfly.jimi.web.service.WebWorkService;
import io.leavesfly.jimi.web.service.WebWorkService.WebSession;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理 REST API
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    private final WebWorkService workService;

    public SessionController(WebWorkService workService) {
        this.workService = workService;
    }

    /**
     * 创建新会话
     * POST /api/sessions
     * Body: { "workDir": "/path/to/project", "agentName": "default" }
     */
    @PostMapping("/sessions")
    public Mono<Map<String, Object>> createSession(@RequestBody(required = false) Map<String, String> body) {
        String workDir = body != null ? body.get("workDir") : null;
        String agentName = body != null ? body.get("agentName") : null;

        WebSession session = workService.createSession(workDir, agentName);
        return Mono.just(toSessionMap(session));
    }

    /**
     * 获取所有会话
     * GET /api/sessions
     */
    @GetMapping("/sessions")
    public Mono<List<Map<String, Object>>> listSessions() {
        List<Map<String, Object>> result = workService.getAllSessions().stream()
                .map(this::toSessionMap)
                .toList();
        return Mono.just(result);
    }

    /**
     * 获取单个会话详情
     * GET /api/sessions/{id}
     */
    @GetMapping("/sessions/{id}")
    public Mono<Map<String, Object>> getSession(@PathVariable String id) {
        WebSession session = workService.getSession(id);
        if (session == null) {
            return Mono.error(new IllegalArgumentException("会话不存在: " + id));
        }
        return Mono.just(toSessionMap(session));
    }

    /**
     * 关闭会话
     * DELETE /api/sessions/{id}
     */
    @DeleteMapping("/sessions/{id}")
    public Mono<Map<String, String>> closeSession(@PathVariable String id) {
        workService.closeSession(id);
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("message", "会话已关闭");
        return Mono.just(result);
    }

    /**
     * 获取可用 Agent 列表
     * GET /api/agents
     */
    @GetMapping("/agents")
    public Mono<List<String>> getAvailableAgents() {
        return Mono.just(workService.getAvailableAgents());
    }

    // ---- helpers ----

    private Map<String, Object> toSessionMap(WebSession session) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", session.getId());
        map.put("workDir", session.getWorkDir().toString());
        map.put("agentName", session.getAgentName());
        map.put("displayName", session.getDisplayName());
        map.put("running", session.isRunning());
        map.put("createdAt", session.getCreatedAt()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return map;
    }
}
