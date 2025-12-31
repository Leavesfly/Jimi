package io.leavesfly.jimi.core.engine.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.skill.SkillSpec;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文管理器
 * 负责维护对话历史、Token 计数和检查点机制
 * 
 * 功能特性：
 * 1. 消息历史管理（追加、查询、清空）
 * 2. Token 计数追踪
 * 3. 检查点机制（创建、回退）
 * 4. 持久化委托给 ContextRepository
 * 5. 上下文恢复
 * 
 * 设计改进（v2.0）：
 * - 分离持久化层：通过 ContextRepository 接口解耦存储实现
 * - 单一职责：仅负责业务逻辑，不涉及文件 I/O
 * - 依赖倒置：依赖接口而非具体实现
 */
@Slf4j
public class Context {
    
    private final ContextRepository repository;
    
    /**
     * 消息历史列表（只读视图对外暴露）
     */
    private final List<Message> history;
    
    /**
     * Token 计数
     */
    private int tokenCount;
    
    /**
     * 下一个检查点 ID（从 0 开始递增）
     */
    private int nextCheckpointId;
    
    /**
     * 激活的 Skills 列表
     */
    private final List<SkillSpec> activeSkills;
    
    /**
     * 关键发现列表（外部状态，完整保留）
     * 用于 ReCAP 记忆优化
     */
    private final List<String> keyInsights;
    
    /**
     * 高层意图（从首条用户消息提取）
     * 用于 ReCAP 记忆优化
     */
    private String highLevelIntent;
    
    /**
     * 构造器（使用同步Repository）
     */
    public Context(Path fileBackend, ObjectMapper objectMapper) {
        this(fileBackend, objectMapper, false);
    }
    
    /**
     * 构造器（可选择Repository实现）
     * 
     * @param fileBackend 文件后端路径
     * @param objectMapper JSON序列化工具
     * @param useAsyncBatch 是否使用异步批量Repository（推荐用于高吞吐场景）
     */
    public Context(Path fileBackend, ObjectMapper objectMapper, boolean useAsyncBatch) {
        this.repository = useAsyncBatch 
                ? new AsyncBatchContextRepository(fileBackend, objectMapper)
                : new JSONLContextRepository(fileBackend, objectMapper);
        this.history = new ArrayList<>();
        this.tokenCount = 0;
        this.nextCheckpointId = 0;
        this.activeSkills = new ArrayList<>();
        this.keyInsights = new ArrayList<>();  // 初始化关键发现列表
        this.highLevelIntent = null;            // 初始化高层意图
    }
    

    
    /**
     * 从持久化存储恢复上下文（异步版本）
     * 
     * @return 是否成功恢复（true 表示恢复了数据，false 表示文件不存在或为空）
     */
    public Mono<Boolean> restore() {
        return repository.restore()
                .map(restoredContext -> {
                    // 恢复到内存
                    history.addAll(restoredContext.getMessages());
                    this.tokenCount = restoredContext.getTokenCount();
                    this.nextCheckpointId = restoredContext.getNextCheckpointId();
                    
                    boolean hasData = !restoredContext.getMessages().isEmpty();
                    if (hasData) {
                        log.info("Restored context: {} messages, {} tokens, {} checkpoints", 
                                history.size(), tokenCount, nextCheckpointId);
                    }
                    return hasData;
                })
                .onErrorResume(e -> {
                    log.error("Failed to restore context", e);
                    return Mono.just(false);
                });
    }
    
    /**
     * 追加单条或多条消息到上下文
     * 
     * @param messages 单条消息或消息列表
     */
    public Mono<Void> appendMessage(Object messages) {
        return Mono.defer(() -> {
            log.debug("Appending message(s) to context: {}", messages);
            
            List<Message> messageList;
            if (messages instanceof Message) {
                messageList = Collections.singletonList((Message) messages);
            } else if (messages instanceof List) {
                messageList = (List<Message>) messages;
            } else {
                throw new IllegalArgumentException("Messages must be Message or List<Message>");
            }
            
            // 首条用户消息自动提取高层意图（ReCAP 优化）
            if (history.isEmpty() && isUserMessage(messageList)) {
                autoExtractHighLevelIntent(messageList.get(0));
            }
            
            // 添加到内存
            history.addAll(messageList);
            
            // 持久化
            return repository.appendMessages(messageList);
        });
    }
    
    /**
     * 更新 Token 计数并持久化
     * 
     * @param count 新的 token 计数
     */
    public Mono<Void> updateTokenCount(int count) {
        return Mono.defer(() -> {
            log.debug("Updating token count in context: {}", count);
            this.tokenCount = count;
            
            // 持久化
            return repository.updateTokenCount(count);
        });
    }
    
    /**
     * 创建检查点
     * 
     * @param addUserMessage 是否添加用户可见的检查点消息
     * @return 检查点 ID
     */
    public Mono<Integer> checkpoint(boolean addUserMessage) {
        return Mono.defer(() -> {
            int checkpointId = nextCheckpointId++;
            log.debug("Checkpointing, ID: {}", checkpointId);
            
            // 持久化检查点
            return repository.saveCheckpoint(checkpointId)
                    .then(Mono.defer(() -> {
                        // 可选：添加用户可见的检查点消息
                        if (addUserMessage) {
                            Message checkpointMessage = Message.builder()
                                    .role(MessageRole.USER)
                                    .content(List.of(TextPart.of("<system>CHECKPOINT " + checkpointId + "</system>")))
                                    .build();
                            return appendMessage(checkpointMessage).thenReturn(checkpointId);
                        }
                        return Mono.just(checkpointId);
                    }));
        });
    }
    
    /**
     * 回退到指定检查点
     * 回退后，指定检查点及之后的所有内容将被移除
     * 原文件会被轮转保存
     * 
     * @param checkpointId 检查点 ID（0 是第一个检查点）
     */
    public Mono<Void> revertTo(int checkpointId) {
        return Mono.defer(() -> {
            log.debug("Reverting checkpoint, ID: {}", checkpointId);
            
            if (checkpointId >= nextCheckpointId) {
                return Mono.error(new IllegalArgumentException(
                    "Checkpoint " + checkpointId + " does not exist"
                ));
            }
            
            // 使用 repository 执行回退
            return repository.revertToCheckpoint(checkpointId)
                    .map(restoredContext -> {
                        // 清空并恢复内存状态
                        history.clear();
                        history.addAll(restoredContext.getMessages());
                        this.tokenCount = restoredContext.getTokenCount();
                        this.nextCheckpointId = restoredContext.getNextCheckpointId();
                        
                        log.info("Reverted to checkpoint {}: {} messages, {} tokens", 
                                checkpointId, history.size(), tokenCount);
                        return restoredContext;
                    })
                    .then();
        });
    }
    
    /**
     * 获取消息历史（只读视图）
     */
    public List<Message> getHistory() {
        return Collections.unmodifiableList(history);
    }
    
    /**
     * 获取 Token 计数
     */
    public int getTokenCount() {
        return tokenCount;
    }
    
    /**
     * 获取检查点数量
     */
    public int getnCheckpoints() {
        return nextCheckpointId;
    }
    
    /**
     * 添加激活的 Skills
     * 
     * @param skills 要添加的 Skills 列表
     * @return 完成的 Mono
     */
    public Mono<Void> addActiveSkills(List<SkillSpec> skills) {
        return Mono.defer(() -> {
            if (skills == null || skills.isEmpty()) {
                return Mono.empty();
            }
            
            // 去重添加
            for (SkillSpec skill : skills) {
                if (!isSkillActive(skill.getName())) {
                    activeSkills.add(skill);
                    log.debug("Added active skill: {}", skill.getName());
                }
            }
            
            return Mono.empty();
        });
    }
    
    /**
     * 获取激活的 Skills
     * 
     * @return Skills 列表（只读视图）
     */
    public List<SkillSpec> getActiveSkills() {
        return Collections.unmodifiableList(activeSkills);
    }
    
    /**
     * 检查某个 Skill 是否已激活
     * 
     * @param skillName Skill 名称
     * @return 是否已激活
     */
    public boolean isSkillActive(String skillName) {
        return activeSkills.stream()
                .anyMatch(skill -> skill.getName().equals(skillName));
    }
    
    /**
     * 清除所有激活的 Skills
     * 
     * @return 完成的 Mono
     */
    public Mono<Void> clearActiveSkills() {
        return Mono.defer(() -> {
            int count = activeSkills.size();
            activeSkills.clear();
            log.debug("Cleared {} active skills", count);
            return Mono.empty();
        });
    }
    
    // ==================== ReCAP 记忆优化相关方法 ====================
    
    /**
     * 添加关键发现
     * 
     * @param insight 发现内容
     * @return 完成的 Mono
     */
    public Mono<Void> addKeyInsight(String insight) {
        return Mono.defer(() -> {
            if (insight == null || insight.trim().isEmpty()) {
                return Mono.empty();
            }
            
            keyInsights.add(insight);
            log.debug("添加关键发现 (总数: {}): {}", keyInsights.size(), insight);
            
            // 保持最近 20 条（窗口压缩）
            if (keyInsights.size() > 20) {
                keyInsights.remove(0);
            }
            
            return Mono.empty();
        });
    }
    
    /**
     * 获取最近的关键发现
     * 
     * @param n 数量
     * @return 发现列表
     */
    public List<String> getRecentInsights(int n) {
        int start = Math.max(0, keyInsights.size() - n);
        return new ArrayList<>(keyInsights.subList(start, keyInsights.size()));
    }
    
    /**
     * 设置高层意图
     * 
     * @param intent 意图内容
     */
    public void setHighLevelIntent(String intent) {
        this.highLevelIntent = intent;
        log.debug("设置高层意图: {}", intent);
    }
    
    /**
     * 获取高层意图
     * 
     * @return 意图内容
     */
    public String getHighLevelIntent() {
        return highLevelIntent;
    }
    
    // ==================== 内部辅助方法 ====================
    
    /**
     * 判断消息列表是否为用户消息
     */
    private boolean isUserMessage(List<Message> messages) {
        return !messages.isEmpty() && messages.get(0).getRole() == MessageRole.USER;
    }
    
    /**
     * 自动提取首条用户消息的高层意图
     * 用于 ReCAP 记忆优化，只在首次添加用户消息时执行
     */
    private void autoExtractHighLevelIntent(Message userMessage) {
        if (highLevelIntent != null) {
            return;  // 已设置则跳过
        }
        
        String intent = extractIntentFromMessage(userMessage);
        this.highLevelIntent = intent;
        log.debug("自动提取高层意图: {}", intent);
    }
    
    /**
     * 从用户消息中提取意图（简化版：取前 200 字符）
     */
    private String extractIntentFromMessage(Message message) {
        Object content = message.getContent();
        if (content == null) {
            return "(无)";
        }
        
        // 如果是 List<ContentPart>，提取所有文本内容
        if (content instanceof List) {
            @SuppressWarnings("unchecked")
            List<ContentPart> parts = (List<ContentPart>) content;
            
            String fullText = parts.stream()
                    .filter(part -> part instanceof TextPart)
                    .map(part -> ((TextPart) part).getText())
                    .collect(Collectors.joining(" "));
            
            // 截取前 200 字符作为高层意图
            return fullText.length() > 200 
                    ? fullText.substring(0, 200) + "..." 
                    : fullText;
        }
        
        // 如果是 String，直接使用
        String text = content.toString();
        return text.length() > 200 
                ? text.substring(0, 200) + "..." 
                : text;
    }
}
