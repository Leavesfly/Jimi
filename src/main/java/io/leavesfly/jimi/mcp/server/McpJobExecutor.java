package io.leavesfly.jimi.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.approval.ApprovalRequest;
import io.leavesfly.jimi.core.approval.ApprovalResponse;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.mcp.MCPSchema;
import io.leavesfly.jimi.wire.message.ContentPartMessage;
import io.leavesfly.jimi.wire.message.ToolCallMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.leavesfly.jimi.mcp.server.McpResultHelper.errorResult;
import static io.leavesfly.jimi.mcp.server.McpResultHelper.textResult;
import static io.leavesfly.jimi.mcp.server.McpResultHelper.getOptionalArgument;

/**
 * MCP任务执行器 - 管理任务执行、流式输出、审批等逻辑
 */
@Slf4j
public class McpJobExecutor {
    
    private final McpSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, McpJobContext> jobs = new ConcurrentHashMap<>();
    private final Map<String, ApprovalRequest> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, String> approvalJobMap = new ConcurrentHashMap<>();
    
    public McpJobExecutor(McpSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }
    
    /** 执行Jimi任务（同步） */
    public MCPSchema.CallToolResult executeJimiTask(Map<String, Object> arguments) {
        try {
            String input = getOptionalArgument(arguments, "input", null);
            String workDir = getOptionalArgument(arguments, "workDir", System.getProperty("user.dir"));
            String agentName = getOptionalArgument(arguments, "agent", "default");
            String sessionId = getOptionalArgument(arguments, "sessionId", null);
            log.info("Executing Jimi task: input={}, workDir={}, agent={}", input, workDir, agentName);
            
            JimiEngine engine = getOrCreateEngine(sessionId, workDir, agentName);
            if (engine == null) return errorResult("Session not found: " + sessionId);
            
            StringBuilder output = new StringBuilder();
            List<String> steps = new ArrayList<>();
            engine.getWire().asFlux().doOnNext(msg -> {
                String msgType = msg.getMessageType();
                if ("step_begin".equals(msgType)) steps.add("Step started");
                else if ("content_part".equals(msgType) && msg instanceof ContentPartMessage cpm 
                        && cpm.getContentPart() instanceof TextPart tp) output.append(tp.getText());
                else if ("tool_call".equals(msgType) && msg instanceof ToolCallMessage tcm) 
                    steps.add("Tool: " + tcm.getToolCall().getFunction().getName());
            }).subscribe();
            
            engine.run(input).block();
            String result = output.toString().isEmpty() ? 
                "SubAgentTool completed. Steps: " + String.join(", ", steps) : output.toString();
            log.info("SubAgentTool completed with {} steps", steps.size());
            return textResult(result);
        } catch (Exception e) {
            log.error("Error executing Jimi task", e);
            return errorResult("Error: " + e.getMessage());
        }
    }
    
    /** 执行Jimi任务（流式） */
    public MCPSchema.CallToolResult executeJimiTaskStream(Map<String, Object> arguments) {
        try {
            String input = getOptionalArgument(arguments, "input", null);
            String workDir = getOptionalArgument(arguments, "workDir", System.getProperty("user.dir"));
            String agentName = getOptionalArgument(arguments, "agent", "default");
            String sessionId = getOptionalArgument(arguments, "sessionId", null);
            if (input == null || input.isBlank()) return errorResult("Missing 'input'");
            
            JimiEngine engine;
            if (sessionId != null && !sessionId.isBlank()) {
                var ctx = sessionManager.getSession(sessionId);
                if (ctx == null) return errorResult("Session not found: " + sessionId);
                engine = ctx.getEngine();
            } else {
                var ctx = sessionManager.createSessionContext(workDir, agentName);
                sessionManager.putSession(ctx.getSessionId(), ctx);
                sessionId = ctx.getSessionId();
                engine = ctx.getEngine();
            }
            
            String jobId = UUID.randomUUID().toString();
            McpJobContext job = new McpJobContext(jobId, sessionId, engine, pendingApprovals, approvalJobMap);
            jobs.put(jobId, job);
            job.start(input);
            return textResult("jobId=" + jobId);
        } catch (Exception e) {
            log.error("Error starting stream task", e);
            return errorResult("Error: " + e.getMessage());
        }
    }
    
    /** 获取任务输出 */
    public MCPSchema.CallToolResult getJobOutput(Map<String, Object> arguments) {
        try {
            String jobId = getOptionalArgument(arguments, "jobId", null);
            Number sinceNum = (Number) arguments.getOrDefault("since", 0);
            int since = sinceNum != null ? sinceNum.intValue() : 0;
            if (jobId == null || jobId.isBlank()) return errorResult("Missing 'jobId'");
            
            McpJobContext job = jobs.get(jobId);
            if (job == null) return errorResult("Job not found: " + jobId);
            
            var output = job.getOutputSince(since);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chunks", output.chunks());
            result.put("next", output.nextIndex());
            result.put("done", job.isDone());
            result.put("error", job.getError());
            result.put("approvals", job.getPendingApprovals());
            result.put("sessionId", job.getSessionId());
            
            if (job.shouldCleanup(output.nextIndex())) jobs.remove(jobId);
            return textResult(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.error("Error getting job output", e);
            return errorResult("Error: " + e.getMessage());
        }
    }
    
    /** 处理审批请求 */
    public MCPSchema.CallToolResult handleApproval(Map<String, Object> arguments) {
        try {
            String action = getOptionalArgument(arguments, "action", null);
            String toolCallId = getOptionalArgument(arguments, "toolCallId", null);
            String sessionId = getOptionalArgument(arguments, "sessionId", null);
            if (action == null) return errorResult("Missing 'action'");
            
            if ("list".equals(action)) {
                return textResult(objectMapper.writeValueAsString(Map.of("approvals", listApprovals(sessionId))));
            }
            if (toolCallId == null || toolCallId.isBlank()) return errorResult("Missing 'toolCallId'");
            
            ApprovalRequest request = pendingApprovals.get(toolCallId);
            if (request == null) return errorResult("Approval not found: " + toolCallId);
            
            ApprovalResponse response = switch (action) {
                case "approve" -> ApprovalResponse.APPROVE;
                case "approve_session" -> ApprovalResponse.APPROVE_FOR_SESSION;
                case "reject" -> ApprovalResponse.REJECT;
                default -> null;
            };
            if (response == null) return errorResult("Unknown action: " + action);
            
            request.resolve(response);
            pendingApprovals.remove(toolCallId);
            String jobId = approvalJobMap.remove(toolCallId);
            if (jobId != null) {
                McpJobContext job = jobs.get(jobId);
                if (job != null) job.removeApproval(toolCallId);
            }
            return textResult("ok");
        } catch (Exception e) {
            log.error("Error handling approval", e);
            return errorResult("Error: " + e.getMessage());
        }
    }
    
    /** 取消任务 */
    public MCPSchema.CallToolResult cancelJob(Map<String, Object> arguments) {
        try {
            String jobId = getOptionalArgument(arguments, "jobId", null);
            if (jobId == null || jobId.isBlank()) return errorResult("Missing 'jobId'");
            McpJobContext job = jobs.get(jobId);
            if (job == null) return errorResult("Job not found: " + jobId);
            job.cancel();
            return textResult("cancelled");
        } catch (Exception e) {
            log.error("Error cancelling job", e);
            return errorResult("Error: " + e.getMessage());
        }
    }
    
    private JimiEngine getOrCreateEngine(String sessionId, String workDir, String agentName) {
        if (sessionId != null && !sessionId.isBlank()) {
            var ctx = sessionManager.getSession(sessionId);
            return ctx != null ? ctx.getEngine() : null;
        }
        return sessionManager.createEngine(workDir, agentName);
    }
    
    private List<Map<String, Object>> listApprovals(String sessionId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (var entry : pendingApprovals.entrySet()) {
            String tcId = entry.getKey();
            ApprovalRequest req = entry.getValue();
            String jId = approvalJobMap.get(tcId);
            McpJobContext job = jId != null ? jobs.get(jId) : null;
            if (sessionId != null && job != null && !sessionId.equals(job.getSessionId())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolCallId", tcId);
            item.put("action", req.getAction());
            item.put("description", req.getDescription());
            if (job != null) { item.put("sessionId", job.getSessionId()); item.put("jobId", jId); }
            list.add(item);
        }
        return list;
    }
}
