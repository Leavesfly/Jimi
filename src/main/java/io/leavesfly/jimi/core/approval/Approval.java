package io.leavesfly.jimi.core.approval;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批服务
 * 管理工具执行的用户审批流程
 */
@Slf4j
public class Approval {
    
    /**
     * YOLO 模式（自动批准所有请求）
     */
    private final boolean yolo;
    
    /**
     * 会话级批准缓存
     */
    private final Set<String> sessionApprovals;
    
    /**
     * 待处理的审批请求队列
     */
    private final Sinks.Many<ApprovalRequest> requestQueue;
    
    public Approval(boolean yolo) {
        this.yolo = yolo;
        this.sessionApprovals = ConcurrentHashMap.newKeySet();
        this.requestQueue = Sinks.many().multicast().onBackpressureBuffer();
    }
    
    /**
     * 请求审批
     * 
     * @param toolCallId 工具调用 ID
     * @param action 操作类型
     * @param description 操作描述
     * @return 审批响应的 Mono
     */
    public Mono<ApprovalResponse> requestApproval(String toolCallId, String action, String description) {
        // YOLO 模式直接批准
        if (yolo) {
            log.debug("YOLO mode: auto-approving action: {}", action);
            return Mono.just(ApprovalResponse.APPROVE);
        }
        
        // 检查会话级缓存
        if (sessionApprovals.contains(action)) {
            log.debug("Action '{}' already approved for session", action);
            return Mono.just(ApprovalResponse.APPROVE);
        }
        
        // 创建审批请求并等待响应
        return Mono.<ApprovalResponse>create(sink -> {
            ApprovalRequest request = ApprovalRequest.builder()
                                                    .toolCallId(toolCallId)
                                                    .action(action)
                                                    .description(description)
                                                    .responseSink(sink)
                                                    .build();
            
            // 发送请求到队列
            requestQueue.tryEmitNext(request);
            
            log.debug("Approval request sent for action: {}", action);
        }).doOnNext(response -> {
            // 如果是会话级批准，添加到缓存
            if (response == ApprovalResponse.APPROVE_FOR_SESSION) {
                sessionApprovals.add(action);
                log.info("Action '{}' approved for session", action);
            }
        });
    }
    
    /**
     * 获取待处理的审批请求
     * UI 层调用此方法获取审批请求
     */
    public Mono<ApprovalRequest> fetchRequest() {
        return requestQueue.asFlux().next();
    }
    
    public Flux<ApprovalRequest> asFlux() {
        return requestQueue.asFlux();
    }
    
    /**
     * 清除会话级批准缓存
     */
    public void clearSessionApprovals() {
        sessionApprovals.clear();
        log.info("Session approvals cleared");
    }
    
    /**
     * 获取会话级批准的操作数量
     */
    public int getSessionApprovalCount() {
        return sessionApprovals.size();
    }
    
    /**
     * 检查是否为YOLO模式
     */
    public boolean isYolo() {
        return yolo;
    }
}
