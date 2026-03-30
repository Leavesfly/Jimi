package io.leavesfly.jimi.tool.core.todo;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.tool.SyncTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.ToolResultBuilder;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireAware;
import io.leavesfly.jimi.wire.message.TodoUpdateMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 设置待办事项列表工具
 * <p>
 * 继承 SyncTool 基类，用于管理和显示待办事项。
 * 只需实现 executeSync() 方法，无需关心 Reactor 的 Mono 包装。
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SetTodoList extends SyncTool<SetTodoList.Params> implements WireAware {
    
    private static final String TODO_FILE_SUFFIX = "_todos.json";
    
    private Session session;
    private Wire wire;
    private final ObjectMapper objectMapper;
    
    /**
     * 设置 Session（运行时注入）
     */
    public void setSession(Session session) {
        this.session = session;
    }
    
    /**
     * 设置 Wire（实现 WireAware 接口）
     */
    @Override
    public void setWire(Wire wire) {
        this.wire = wire;
    }
    
    /**
     * 待办事项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Todo {
        /**
         * 待办事项唯一标识（可选，用于精确匹配）
         */
        @JsonPropertyDescription("待办事项的唯一标识，用于精确更新和删除。如不提供则使用 title 匹配")
        private String id;
        
        /**
         * 待办事项标题
         */
        @JsonPropertyDescription("待办事项的标题或名称")
        private String title;
        
        /**
         * 待办事项状态：Pending, In Progress, Done, Cancelled, Error
         */
        @JsonPropertyDescription("待办事项的状态，可选值：'Pending'（待办）、'In Progress'（进行中）、'Done'（已完成）、'Cancelled'（已取消）、'Error'（错误）")
        private String status;
        
        /**
         * 父任务ID（用于子任务）
         */
        @JsonPropertyDescription("父任务的ID，用于创建子任务层级结构")
        private String parentId;
    }
    
    /**
     * 参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        /**
         * 待办事项列表（基础列表）
         */
        @JsonPropertyDescription("待办事项数组，每项包含 id/title 和 status 字段。支持批量提交 5-10+ 任务以提高性能")
        @Builder.Default
        private List<Todo> todos = new ArrayList<>();
        
        /**
         * 对现有待办的状态更新（按ID或标题匹配）
         */
        @JsonPropertyDescription("需要更新状态的待办事项列表，优先根据 id 匹配，否则根据 title 匹配并更新 status")
        @Builder.Default
        private List<Todo> updates = new ArrayList<>();
        
        /**
         * 需要新增的待办项（当ID/标题不存在时添加）
         */
        @JsonPropertyDescription("需要新增的待办事项列表，只当 id/title 不存在时才会添加")
        @Builder.Default
        private List<Todo> adds = new ArrayList<>();
        
        /**
         * 需要删除的待办项ID或标题列表
         */
        @JsonPropertyDescription("需要删除的待办事项的ID或标题列表")
        @Builder.Default
        private List<String> deletes = new ArrayList<>();
        
        /**
         * 是否移除已完成（Done）的待办
         */
        @JsonPropertyDescription("是否从列表中移除状态为 'Done' 的待办事项。默认为 false")
        @Builder.Default
        private boolean removeCompleted = false;
    }
    
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return "Pending";
        String s = status.trim();
        if (s.equalsIgnoreCase("pending")) return "Pending";
        if (s.equalsIgnoreCase("inprogress") || s.equalsIgnoreCase("in progress") || s.equalsIgnoreCase("in_progress")) return "In Progress";
        if (s.equalsIgnoreCase("done") || s.equalsIgnoreCase("completed") || s.equalsIgnoreCase("complete")) return "Done";
        if (s.equalsIgnoreCase("cancelled") || s.equalsIgnoreCase("canceled")) return "Cancelled";
        if (s.equalsIgnoreCase("error") || s.equalsIgnoreCase("failed")) return "Error";
        return "Pending";
    }
    
    /**
     * 根据ID或标题查找待办事项
     */
    private Todo findTodo(List<Todo> list, String idOrTitle) {
        if (idOrTitle == null || idOrTitle.isBlank()) return null;
        String key = idOrTitle.trim();
        // 优先按ID匹配
        for (Todo t : list) {
            if (t.getId() != null && t.getId().equals(key)) {
                return t;
            }
        }
        // 其次按标题匹配
        for (Todo t : list) {
            if (t.getTitle() != null && t.getTitle().equals(key)) {
                return t;
            }
        }
        return null;
    }
    
    public SetTodoList() {
        super(
            "SetTodoList",
            """
            更新待办事项列表（Todo List），支持增量操作。
                
            使用场景：
            - 当一个任务包含多个子任务/里程碑时，用于拆解并追踪进度
            - 当一次请求中包含多项任务时，用于统一维护它们的状态
                
            操作模式：
            - todos：覆盖模式 - 用新列表替换现有列表
            - adds：增量添加 - 只添加新任务（不影响现有任务）
            - updates：增量更新 - 只更新指定任务的状态
            - deletes：增量删除 - 只删除指定的任务
                
            持久化：
            - 待办列表会自动保存，会话重启后不会丢失
            - 可以只使用 adds/updates/deletes 进行增量操作，无需每次传入完整列表
                
            示例 - 增量添加：
            {
              "adds": [
                {"id": "task1", "title": "New task", "status": "Pending"}
              ]
            }
                
            示例 - 增量更新：
            {
              "updates": [
                {"id": "task1", "status": "Done"}
              ]
            }
            """,
            Params.class
        );
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    @Override
    protected ToolResult executeSync(Params params) {
        ToolResultBuilder trb = new ToolResultBuilder();
        
        // 1. 加载已持久化的待办列表
        List<Todo> list = loadTodos();
        
        // 2. 如果提供了 todos 参数，用它覆盖现有列表
        if (params != null && params.getTodos() != null && !params.getTodos().isEmpty()) {
            list = new ArrayList<>();
            for (Todo t : params.getTodos()) {
                String title = (t != null && t.getTitle() != null) ? t.getTitle().trim() : "(未命名)";
                String status = (t != null) ? normalizeStatus(t.getStatus()) : "Pending";
                list.add(Todo.builder()
                    .id(t != null ? t.getId() : null)
                    .title(title)
                    .status(status)
                    .parentId(t != null ? t.getParentId() : null)
                    .build());
            }
        }
        
        // 3. 更新现有待办的状态（按ID或标题匹配）
        if (params != null && params.getUpdates() != null) {
            for (Todo upd : params.getUpdates()) {
                if (upd == null) continue;
                String key = upd.getId() != null ? upd.getId() : upd.getTitle();
                if (key == null) continue;
                
                Todo found = findTodo(list, key);
                if (found != null) {
                    if (upd.getStatus() != null) {
                        found.setStatus(normalizeStatus(upd.getStatus()));
                    }
                    if (upd.getTitle() != null) {
                        found.setTitle(upd.getTitle().trim());
                    }
                    if (upd.getParentId() != null) {
                        found.setParentId(upd.getParentId());
                    }
                }
            }
        }
        
        // 4. 添加新的待办（仅当ID/标题不存在时添加）
        if (params != null && params.getAdds() != null) {
            for (Todo add : params.getAdds()) {
                if (add == null || add.getTitle() == null) continue;
                String key = add.getId() != null ? add.getId() : add.getTitle();
                
                if (findTodo(list, key) == null) {
                    list.add(Todo.builder()
                        .id(add.getId())
                        .title(add.getTitle().trim())
                        .status(normalizeStatus(add.getStatus()))
                        .parentId(add.getParentId())
                        .build());
                }
            }
        }
        
        // 5. 删除指定的待办项
        if (params != null && params.getDeletes() != null && !params.getDeletes().isEmpty()) {
            list.removeIf(t -> {
                for (String key : params.getDeletes()) {
                    if (key != null && !key.isBlank()) {
                        String trimmedKey = key.trim();
                        if ((t.getId() != null && t.getId().equals(trimmedKey)) ||
                            (t.getTitle() != null && t.getTitle().equals(trimmedKey))) {
                            return true;
                        }
                    }
                }
                return false;
            });
        }
        
        // 6. 移除完成项
        if (params != null && params.isRemoveCompleted()) {
            List<Todo> filtered = new ArrayList<>();
            for (Todo t : list) {
                String s = normalizeStatus(t.getStatus());
                if (!"Done".equals(s)) {
                    filtered.add(t);
                }
            }
            list = filtered;
        }
        
        // 7. 持久化待办列表
        saveTodos(list);
        
        // 8. 生成输出
        if (list.isEmpty()) {
            // 发送空列表通知
            sendTodoUpdate(list, 0, 0, 0, 0, 0);
            trb.write("暂无待办事项。\n");
            return trb.ok("空的待办清单", "空列表");
        }
        
        // 统计各状态数量
        int pending = 0, inProgress = 0, done = 0, cancelled = 0, error = 0;
        for (Todo t : list) {
            String s = normalizeStatus(t.getStatus());
            switch (s) {
                case "Pending": pending++; break;
                case "In Progress": inProgress++; break;
                case "Done": done++; break;
                case "Cancelled": cancelled++; break;
                case "Error": error++; break;
                default: pending++; break;
            }
            String indent = t.getParentId() != null ? "  " : "";
            String idStr = t.getId() != null ? "#" + t.getId() + " " : "";
            trb.write(String.format("%s- %s%s [%s]\n", indent, idStr, t.getTitle(), s));
        }
        
        // 9. 发送 Wire 消息通知 UI
        sendTodoUpdate(list, pending, inProgress, done, cancelled, error);
        
        StringBuilder briefBuilder = new StringBuilder();
        briefBuilder.append(String.format("共%d项：", list.size()));
        if (pending > 0) briefBuilder.append(String.format("Pending %d, ", pending));
        if (inProgress > 0) briefBuilder.append(String.format("In Progress %d, ", inProgress));
        if (done > 0) briefBuilder.append(String.format("Done %d, ", done));
        if (cancelled > 0) briefBuilder.append(String.format("Cancelled %d, ", cancelled));
        if (error > 0) briefBuilder.append(String.format("Error %d, ", error));
        String brief = briefBuilder.toString().replaceAll(", $", "");
        log.info("Todo list applied: size={}, pending={}, inProgress={}, done={}, cancelled={}, error={}", 
            list.size(), pending, inProgress, done, cancelled, error);
        
        return trb.ok("待办清单已更新" , brief);
    }
    
    /**
     * 获取持久化文件路径
     * 使用会话级别隔离：{sessionsDir}/{sessionId}_todos.json
     */
    private Path getTodoFilePath() {
        if (session == null || session.getHistoryFile() == null) {
            return null;
        }
        // historyFile: {sessionsDir}/{sessionId}.jsonl
        // todoFile:    {sessionsDir}/{sessionId}_todos.json
        return session.getHistoryFile().resolveSibling(session.getId() + TODO_FILE_SUFFIX);
    }
    
    /**
     * 加载已持久化的待办列表
     */
    private List<Todo> loadTodos() {
        Path todoFile = getTodoFilePath();
        if (todoFile == null || !Files.exists(todoFile)) {
            return new ArrayList<>();
        }
        
        try {
            String content = Files.readString(todoFile);
            if (content == null || content.isBlank()) {
                return new ArrayList<>();
            }
            List<Todo> todos = objectMapper.readValue(content, new TypeReference<List<Todo>>() {});
            log.debug("Loaded {} todos from {}", todos.size(), todoFile);
            return todos;
        } catch (IOException e) {
            log.warn("Failed to load todos from {}: {}", todoFile, e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 保存待办列表到文件
     */
    private void saveTodos(List<Todo> todos) {
        Path todoFile = getTodoFilePath();
        if (todoFile == null) {
            log.debug("Session not set, skipping todo persistence");
            return;
        }
        
        try {
            // 确保目录存在
            Path todoDir = todoFile.getParent();
            if (!Files.exists(todoDir)) {
                Files.createDirectories(todoDir);
            }
            
            // 写入文件
            String content = objectMapper.writeValueAsString(todos);
            Files.writeString(todoFile, content);
            log.debug("Saved {} todos to {}", todos.size(), todoFile);
        } catch (IOException e) {
            log.warn("Failed to save todos to {}: {}", todoFile, e.getMessage());
        }
    }
    
    /**
     * 发送 Todo 更新消息到 Wire
     */
    private void sendTodoUpdate(List<Todo> todos, int pending, int inProgress, int done, int cancelled, int error) {
        if (wire == null) {
            log.debug("Wire not set, skipping todo update notification");
            return;
        }
        
        List<TodoUpdateMessage.TodoItem> items = todos.stream()
            .map(t -> TodoUpdateMessage.TodoItem.builder()
                .id(t.getId())
                .title(t.getTitle())
                .status(t.getStatus())
                .parentId(t.getParentId())
                .build())
            .collect(Collectors.toList());
        
        TodoUpdateMessage msg = TodoUpdateMessage.builder()
            .todos(items)
            .totalCount(todos.size())
            .pendingCount(pending)
            .inProgressCount(inProgress)
            .doneCount(done)
            .cancelledCount(cancelled)
            .errorCount(error)
            .build();
        
        wire.send(msg);
        log.debug("Sent todo update: {} items", todos.size());
    }
}
