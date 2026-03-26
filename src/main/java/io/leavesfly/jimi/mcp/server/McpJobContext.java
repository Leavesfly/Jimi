package io.leavesfly.jimi.mcp.server;

import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.interaction.approval.ApprovalRequest;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.wire.message.ContentPartMessage;
import io.leavesfly.jimi.wire.message.ToolCallMessage;
import reactor.core.Disposable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP任务上下文
 * 管理单个任务的执行状态、输出和审批
 */
public class McpJobContext {
    
    private final String jobId;
    private final String sessionId;
    private final JimiEngine engine;
    private final List<String> chunks;
    private final Map<String, ApprovalRequest> jobApprovals;
    private final AtomicBoolean done;
    private final AtomicBoolean cancelled;
    private final AtomicReference<String> error;
    private Disposable wireSubscription;
    private Thread thread;
    
    // 外部引用（用于审批缓存）
    private final Map<String, ApprovalRequest> pendingApprovals;
    private final Map<String, String> approvalJobMap;
    
    public McpJobContext(String jobId, String sessionId, JimiEngine engine,
                         Map<String, ApprovalRequest> pendingApprovals,
                         Map<String, String> approvalJobMap) {
        this.jobId = jobId;
        this.sessionId = sessionId;
        this.engine = engine;
        this.chunks = new CopyOnWriteArrayList<>();
        this.jobApprovals = new ConcurrentHashMap<>();
        this.done = new AtomicBoolean(false);
        this.cancelled = new AtomicBoolean(false);
        this.error = new AtomicReference<>(null);
        this.pendingApprovals = pendingApprovals;
        this.approvalJobMap = approvalJobMap;
    }
    
    public void start(String input) {
        // 订阅 Wire 消息，转成可轮询的文本块
        wireSubscription = engine.getWire().asFlux().subscribe(msg -> {
            if (cancelled.get()) return;
            if (msg instanceof ContentPartMessage cpm) {
                if (cpm.getContentPart() instanceof TextPart tp) {
                    chunks.add(tp.getText());
                }
            } else if (msg instanceof ToolCallMessage tcm) {
                String toolName = tcm.getToolCall().getFunction().getName();
                chunks.add("\n[Tool] " + toolName + "\n");
            } else if (msg instanceof ApprovalRequest approvalRequest) {
                jobApprovals.put(approvalRequest.getToolCallId(), approvalRequest);
                pendingApprovals.put(approvalRequest.getToolCallId(), approvalRequest);
                approvalJobMap.put(approvalRequest.getToolCallId(), jobId);
                chunks.add("\n[Approval required] " + approvalRequest.getAction() + ": " 
                    + approvalRequest.getDescription() + " (id=" + approvalRequest.getToolCallId() + ")\n");
            }
        });
        
        // 独立线程执行任务，完成后标记 done
        thread = new Thread(() -> {
            try {
                engine.run(input).block();
            } catch (Exception e) {
                error.set(e.getMessage());
            } finally {
                done.set(true);
                if (wireSubscription != null) {
                    wireSubscription.dispose();
                }
            }
        }, "jimi-job-" + jobId);
        thread.setDaemon(true);
        thread.start();
    }
    
    public McpJobOutput getOutputSince(int since) {
        // 按游标返回增量输出
        if (since < 0) since = 0;
        int size = chunks.size();
        if (since >= size) {
            return new McpJobOutput(Collections.emptyList(), size);
        }
        List<String> output = new ArrayList<>(chunks.subList(since, size));
        return new McpJobOutput(output, size);
    }
    
    public List<Map<String, Object>> getPendingApprovals() {
        // 仅返回当前 job 的审批列表
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (ApprovalRequest request : jobApprovals.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolCallId", request.getToolCallId());
            item.put("action", request.getAction());
            item.put("description", request.getDescription());
            approvals.add(item);
        }
        return approvals;
    }
    
    public void removeApproval(String toolCallId) {
        jobApprovals.remove(toolCallId);
    }
    
    public boolean shouldCleanup(int nextIndex) {
        return done.get() && jobApprovals.isEmpty() && nextIndex >= chunks.size();
    }
    
    public void cancel() {
        // 尽力中断执行线程和订阅
        cancelled.set(true);
        error.set("cancelled");
        done.set(true);
        if (wireSubscription != null) {
            wireSubscription.dispose();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }
    
    public String getJobId() {
        return jobId;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public boolean isDone() {
        return done.get();
    }
    
    public String getError() {
        return error.get();
    }
    
    /**
     * 任务输出
     */
    public record McpJobOutput(List<String> chunks, int nextIndex) {}
}
