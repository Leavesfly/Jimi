package io.leavesfly.jimi.adk.tools.extended;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.tools.base.AbstractTool;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SetTodoList 工具 - 管理待办事项列表
 * <p>
 * 用于规划和追踪任务进度。
 * </p>
 *
 * @author Jimi2 Team
 */
@Slf4j
public class SetTodoListTool extends AbstractTool<SetTodoListTool.Params> {

    private static final String TODO_DIR = ".jimi";
    private static final String TODO_FILE = "todos.json";

    private final Runtime runtime;
    private final ObjectMapper objectMapper;

    public SetTodoListTool(Runtime runtime) {
        super("set_todo_list",
                "Create or update a todo list to track task progress",
                Params.class);
        this.runtime = runtime;
        this.objectMapper = new ObjectMapper();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Todo {
        @JsonPropertyDescription("Unique ID for this todo item (optional, auto-generated if not provided)")
        private String id;

        @JsonPropertyDescription("Title or name of the todo item")
        private String title;

        @JsonPropertyDescription("Status: 'Pending', 'In Progress', 'Done', 'Cancelled', 'Error'")
        @Builder.Default
        private String status = "Pending";

        @JsonPropertyDescription("Parent task ID (for subtasks)")
        private String parentId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("List of todo items")
        private List<Todo> todos;

        @JsonPropertyDescription("Whether to replace existing list (true) or merge (false). Default: false")
        @Builder.Default
        private boolean replace = false;
    }

    @Override
    public Mono<ToolResult> doExecute(Params params) {
        return Mono.defer(() -> {
            try {
                if (params.todos == null || params.todos.isEmpty()) {
                    return Mono.just(ToolResult.error("Todo list is required"));
                }

                Path todoDir = runtime.getWorkDir().resolve(TODO_DIR);
                Path todoFile = todoDir.resolve(TODO_FILE);

                // 确保目录存在
                Files.createDirectories(todoDir);

                List<Todo> currentTodos = new ArrayList<>();

                // 读取现有 todos（如果存在且是合并模式）
                if (!params.replace && Files.exists(todoFile)) {
                    try {
                        String json = Files.readString(todoFile);
                        currentTodos = objectMapper.readValue(json, new TypeReference<List<Todo>>() {});
                    } catch (Exception e) {
                        log.warn("Failed to read existing todos, will replace", e);
                    }
                }

                // 自动生成 ID
                for (Todo todo : params.todos) {
                    if (todo.id == null || todo.id.isEmpty()) {
                        todo.id = UUID.randomUUID().toString().substring(0, 8);
                    }
                    if (todo.status == null || todo.status.isEmpty()) {
                        todo.status = "Pending";
                    }
                }

                if (params.replace) {
                    currentTodos = params.todos;
                } else {
                    // 合并：更新现有或添加新的
                    for (Todo newTodo : params.todos) {
                        boolean found = false;
                        for (int i = 0; i < currentTodos.size(); i++) {
                            Todo existing = currentTodos.get(i);
                            if (newTodo.id.equals(existing.id)) {
                                currentTodos.set(i, newTodo);
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            currentTodos.add(newTodo);
                        }
                    }
                }

                // 写入文件
                String json = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(currentTodos);
                Files.writeString(todoFile, json);

                // 格式化输出
                String summary = formatTodoList(currentTodos);

                log.info("Updated todo list: {} items", currentTodos.size());

                return Mono.just(ToolResult.success(summary));

            } catch (Exception e) {
                log.error("Failed to update todo list", e);
                return Mono.just(ToolResult.error("Failed to update todo list: " + e.getMessage()));
            }
        });
    }

    private String formatTodoList(List<Todo> todos) {
        if (todos.isEmpty()) {
            return "Todo list is empty";
        }

        StringBuilder sb = new StringBuilder("Current Todo List:\n\n");

        // 按状态分组
        List<Todo> pending = todos.stream().filter(t -> "Pending".equals(t.status)).collect(Collectors.toList());
        List<Todo> inProgress = todos.stream().filter(t -> "In Progress".equals(t.status)).collect(Collectors.toList());
        List<Todo> done = todos.stream().filter(t -> "Done".equals(t.status)).collect(Collectors.toList());

        if (!inProgress.isEmpty()) {
            sb.append("⏳ In Progress:\n");
            for (Todo todo : inProgress) {
                sb.append(String.format("  - [%s] %s\n", todo.id, todo.title));
            }
            sb.append("\n");
        }

        if (!pending.isEmpty()) {
            sb.append("📋 Pending:\n");
            for (Todo todo : pending) {
                sb.append(String.format("  - [%s] %s\n", todo.id, todo.title));
            }
            sb.append("\n");
        }

        if (!done.isEmpty()) {
            sb.append("✅ Done:\n");
            for (Todo todo : done) {
                sb.append(String.format("  - [%s] %s\n", todo.id, todo.title));
            }
        }

        return sb.toString();
    }
}
