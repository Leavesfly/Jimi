package io.leavesfly.jimi.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.web.service.WebWorkService;
import io.leavesfly.jimi.web.service.WebWorkService.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * 聊天 API - SSE 流式输出
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final WebWorkService workService;
    private final ObjectMapper objectMapper;

    public ChatController(WebWorkService workService, ObjectMapper objectMapper) {
        this.workService = workService;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送消息并获取 SSE 流式响应
     * POST /api/chat/{sessionId}
     * Body: { "message": "用户输入内容" }
     */
    @PostMapping(value = "/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {

        String message = body.get("message");
        if (message == null || message.trim().isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"type\":\"ERROR\",\"content\":\"消息不能为空\"}")
                    .build());
        }

        log.info("接收消息: sessionId={}, message={}", sessionId,
                message.length() > 100 ? message.substring(0, 100) + "..." : message);

        return workService.execute(sessionId, message)
                .map(event -> {
                    String eventType = event.getType().name().toLowerCase();
                    String data;
                    try {
                        data = objectMapper.writeValueAsString(event);
                    } catch (JsonProcessingException e) {
                        data = "{\"type\":\"ERROR\",\"content\":\"序列化失败\"}";
                    }
                    return ServerSentEvent.<String>builder()
                            .event(eventType)
                            .data(data)
                            .build();
                })
                .onErrorResume(e -> {
                    log.error("聊天错误: sessionId={}", sessionId, e);
                    String errorData = "{\"type\":\"ERROR\",\"content\":\""
                            + escapeJson(e.getMessage()) + "\"}";
                    return Flux.just(
                            ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data(errorData)
                                    .build()
                    );
                });
    }

    /**
     * 取消当前执行
     * POST /api/chat/{sessionId}/cancel
     */
    @PostMapping("/{sessionId}/cancel")
    public Mono<Map<String, String>> cancel(@PathVariable String sessionId) {
        workService.cancelTask(sessionId);
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("message", "任务已取消");
        return Mono.just(result);
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
