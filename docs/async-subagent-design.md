# Jimi 异步子代理（Async Subagent）技术方案

## 1. 概述

### 1.1 背景
Claude Code CLI 支持异步子代理功能，可启动后台独立运行的子代理，适用于日志监控、长时间构建等场景。本方案为 Jimi 设计类似功能。

### 1.2 核心特性
- **后台独立运行**：子代理启动后在后台执行，主代理可继续其他任务
- **生命周期独立**：子代理可在主代理完成后继续运行
- **事件触发回调**：支持日志监控、构建完成等事件触发通知
- **多子代理并行**：可同时启动多个后台子代理

### 1.3 兼容性承诺
- **现有 Task 工具完全兼容**：`Task.java` 保持不变，同步子代理功能正常工作
- **新增独立模块**：异步功能通过新增 `AsyncTask` 工具实现
- **Wire 消息向后兼容**：新增消息类型，不修改现有消息

---

## 2. 架构设计

### 2.1 模块结构

```
src/main/java/io/leavesfly/jimi/
├── engine/
│   └── async/                              # 新增：异步子代理模块
│       ├── AsyncSubagent.java              # 异步子代理实体
│       ├── AsyncSubagentManager.java       # 生命周期管理器（Spring Service）
│       ├── AsyncSubagentStatus.java        # 状态枚举
│       └── AsyncSubagentCallback.java      # 完成回调接口
├── tool/
│   └── async/                              # 新增：异步任务工具
│       ├── AsyncTask.java                  # 异步任务工具
│       └── AsyncTaskToolProvider.java      # 工具提供者
├── command/handlers/
│   └── AsyncCommandHandler.java            # 新增：/async 命令
└── wire/message/
    ├── AsyncSubagentStarted.java           # 新增
    ├── AsyncSubagentProgress.java          # 新增
    └── AsyncSubagentCompleted.java         # 新增
```

### 2.2 组件交互图

```
┌─────────────────────────────────────────────────────────────────┐
│                         主 Agent                                 │
├─────────────────────────────────────────────────────────────────┤
│  AgentExecutor  ──调用──>  AsyncTask（新工具）                    │
│                              │                                   │
│                              ▼                                   │
│                    AsyncSubagentManager                          │
│                     (Spring Service)                             │
│                              │                                   │
│              ┌───────────────┼───────────────┐                   │
│              ▼               ▼               ▼                   │
│      AsyncSubagent-1   AsyncSubagent-2   AsyncSubagent-N        │
│      (后台线程池)       (后台线程池)       (后台线程池)           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ Wire 消息
┌─────────────────────────────────────────────────────────────────┐
│                         ShellUI                                  │
│          (显示异步子代理状态、进度、完成通知)                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. 详细设计

### 3.1 AsyncSubagent 实体

```java
package io.leavesfly.jimi.engine.async;

import io.leavesfly.jimi.core.async.AsyncSubagentCallback;
import io.leavesfly.jimi.core.async.AsyncSubagentMode;
import io.leavesfly.jimi.core.async.AsyncSubagentStatus;

@Data
@Builder
public class AsyncSubagent {
    private String id;                          // UUID
    private String name;                        // 显示名称
    private AsyncSubagentMode mode;             // 运行模式
    private AsyncSubagentStatus status;         // 状态
    private Agent agent;                        // 底层 Agent
    private JimiEngine engine;                  // 执行引擎
    private Context context;                    // 独立上下文
    private Instant startTime;                  // 启动时间
    private Instant endTime;                    // 结束时间
    private Disposable subscription;            // Reactor 订阅（用于取消）
    private String result;                      // 执行结果
    private Throwable error;                    // 错误信息
    private AsyncSubagentCallback callback;     // 完成回调
    private Duration timeout;                   // 超时时间（可选）
    private String triggerPattern;              // 监控模式的触发正则（可选）
}
```

### 3.2 AsyncSubagentStatus 状态枚举

```java
package io.leavesfly.jimi.engine.async;

public enum AsyncSubagentStatus {
    PENDING,        // 等待启动
    RUNNING,        // 运行中
    COMPLETED,      // 正常完成
    FAILED,         // 执行失败
    CANCELLED,      // 被取消
    TIMEOUT         // 超时
}
```

### 3.3 AsyncSubagentMode 模式枚举

```java
package io.leavesfly.jimi.engine.async;

public enum AsyncSubagentMode {
    FIRE_AND_FORGET,  // 启动后不等待，后台执行
    WATCH,            // 持续监控模式
    WAIT_COMPLETE     // 等待完成（兼容同步模式）
}
```

### 3.4 AsyncSubagentCallback 回调接口

```java
package io.leavesfly.jimi.engine.async;

import io.leavesfly.jimi.core.async.AsyncSubagent;

@FunctionalInterface
public interface AsyncSubagentCallback {
    /**
     * 子代理完成时的回调
     * @param subagent 完成的子代理
     */
    void onComplete(AsyncSubagent subagent);
}
```

### 3.5 AsyncSubagentManager 管理器

```java
package io.leavesfly.jimi.engine.async;

import io.leavesfly.jimi.core.async.AsyncSubagent;
import io.leavesfly.jimi.core.async.AsyncSubagentCallback;
import io.leavesfly.jimi.core.async.AsyncSubagentMode;
import io.leavesfly.jimi.core.async.AsyncSubagentStatus;

@Slf4j
@Service
public class AsyncSubagentManager {

    // 活跃的异步子代理映射
    private final ConcurrentHashMap<String, AsyncSubagent> activeSubagents = new ConcurrentHashMap<>();

    // 已完成的子代理（保留最近N个）
    private final LinkedHashMap<String, AsyncSubagent> completedSubagents = new LinkedHashMap<>();
    private static final int MAX_COMPLETED_CACHE = 50;

    // 后台执行线程池
    private final Scheduler asyncScheduler = Schedulers.newBoundedElastic(
            10,                    // 最大线程数
            100,                   // 最大排队任务
            "async-subagent"       // 线程名前缀
    );

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ToolRegistryFactory toolRegistryFactory;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private List<ToolProvider> toolProviders;

    /**
     * 启动异步子代理（Fire-and-Forget 模式）
     */
    public Mono<String> startAsync(
            Agent agent,
            Runtime runtime,
            String prompt,
            @Nullable Wire parentWire,
            @Nullable AsyncSubagentCallback callback,
            @Nullable Duration timeout
    ) {
        return Mono.defer(() -> {
            String subagentId = UUID.randomUUID().toString().substring(0, 8);

            // 创建异步子代理实体
            AsyncSubagent asyncSubagent = AsyncSubagent.builder()
                    .id(subagentId)
                    .name(agent.getName())
                    .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                    .status(AsyncSubagentStatus.PENDING)
                    .agent(agent)
                    .startTime(Instant.now())
                    .callback(callback)
                    .timeout(timeout)
                    .build();

            // 注册到活跃列表
            activeSubagents.put(subagentId, asyncSubagent);

            // 后台启动执行
            Disposable subscription = executeInBackground(asyncSubagent, runtime, prompt, parentWire)
                    .subscribeOn(asyncScheduler)
                    .subscribe();

            asyncSubagent.setSubscription(subscription);
            asyncSubagent.setStatus(AsyncSubagentStatus.RUNNING);

            // 发送启动消息
            if (parentWire != null) {
                parentWire.send(new AsyncSubagentStarted(subagentId, agent.getName(),
                        AsyncSubagentMode.FIRE_AND_FORGET.name(), Instant.now()));
            }

            return Mono.just(subagentId);
        });
    }

    /**
     * 后台执行子代理
     */
    private Mono<Void> executeInBackground(
            AsyncSubagent asyncSubagent,
            Runtime runtime,
            String prompt,
            @Nullable Wire parentWire
    ) {
        return Mono.defer(() -> {
            try {
                // 创建独立上下文
                Path historyFile = createAsyncHistoryFile(asyncSubagent.getId());
                Context context = new Context(historyFile, objectMapper);
                asyncSubagent.setContext(context);

                // 创建工具注册表
                ToolRegistry toolRegistry = createToolRegistry(asyncSubagent.getAgent(), runtime);

                // 创建引擎
                JimiEngine engine = new JimiEngine(
                        asyncSubagent.getAgent(),
                        runtime,
                        context,
                        toolRegistry,
                        objectMapper,
                        new WireImpl(),
                        new SimpleCompaction(),
                        true,
                        null,
                        null
                );
                asyncSubagent.setEngine(engine);

                // Wire 事件转发
                if (parentWire != null) {
                    engine.getWire().asFlux().subscribe(msg -> {
                        // 包装为异步进度消息
                        parentWire.send(new AsyncSubagentProgress(
                                asyncSubagent.getId(),
                                msg.getClass().getSimpleName(),
                                0
                        ));
                    });
                }

                // 执行（带超时）
                Mono<Void> execution = engine.run(prompt);
                if (asyncSubagent.getTimeout() != null) {
                    execution = execution.timeout(asyncSubagent.getTimeout());
                }

                return execution
                        .doOnSuccess(v -> handleComplete(asyncSubagent, parentWire))
                        .doOnError(e -> handleError(asyncSubagent, e, parentWire));

            } catch (Exception e) {
                return Mono.error(e);
            }
        });
    }

    /**
     * 处理完成
     */
    private void handleComplete(AsyncSubagent asyncSubagent, @Nullable Wire parentWire) {
        asyncSubagent.setStatus(AsyncSubagentStatus.COMPLETED);
        asyncSubagent.setEndTime(Instant.now());

        // 提取结果
        String result = extractResult(asyncSubagent.getContext());
        asyncSubagent.setResult(result);

        // 移动到已完成列表
        activeSubagents.remove(asyncSubagent.getId());
        addToCompleted(asyncSubagent);

        // 发送完成消息
        if (parentWire != null) {
            Duration duration = Duration.between(asyncSubagent.getStartTime(), asyncSubagent.getEndTime());
            parentWire.send(new AsyncSubagentCompleted(
                    asyncSubagent.getId(), result, true, duration
            ));
        }

        // 执行回调
        if (asyncSubagent.getCallback() != null) {
            try {
                asyncSubagent.getCallback().onComplete(asyncSubagent);
            } catch (Exception e) {
                log.error("Callback error for subagent {}", asyncSubagent.getId(), e);
            }
        }

        log.info("Async subagent {} completed", asyncSubagent.getId());
    }

    /**
     * 处理错误
     */
    private void handleError(AsyncSubagent asyncSubagent, Throwable error, @Nullable Wire parentWire) {
        if (error instanceof TimeoutException) {
            asyncSubagent.setStatus(AsyncSubagentStatus.TIMEOUT);
        } else {
            asyncSubagent.setStatus(AsyncSubagentStatus.FAILED);
        }
        asyncSubagent.setEndTime(Instant.now());
        asyncSubagent.setError(error);

        activeSubagents.remove(asyncSubagent.getId());
        addToCompleted(asyncSubagent);

        if (parentWire != null) {
            Duration duration = Duration.between(asyncSubagent.getStartTime(), asyncSubagent.getEndTime());
            parentWire.send(new AsyncSubagentCompleted(
                    asyncSubagent.getId(), error.getMessage(), false, duration
            ));
        }

        log.error("Async subagent {} failed", asyncSubagent.getId(), error);
    }

    /**
     * 查询子代理状态
     */
    public Optional<AsyncSubagent> getSubagent(String id) {
        AsyncSubagent active = activeSubagents.get(id);
        if (active != null) return Optional.of(active);
        return Optional.ofNullable(completedSubagents.get(id));
    }

    /**
     * 列出所有活跃子代理
     */
    public List<AsyncSubagent> listActive() {
        return new ArrayList<>(activeSubagents.values());
    }

    /**
     * 取消子代理
     */
    public boolean cancel(String id) {
        AsyncSubagent subagent = activeSubagents.get(id);
        if (subagent != null && subagent.getSubscription() != null) {
            subagent.getSubscription().dispose();
            subagent.setStatus(AsyncSubagentStatus.CANCELLED);
            subagent.setEndTime(Instant.now());
            activeSubagents.remove(id);
            addToCompleted(subagent);
            return true;
        }
        return false;
    }

    /**
     * 优雅关闭所有子代理
     */
    @PreDestroy
    public void shutdownAll() {
        log.info("Shutting down {} async subagents", activeSubagents.size());
        activeSubagents.values().forEach(subagent -> {
            if (subagent.getSubscription() != null) {
                subagent.getSubscription().dispose();
            }
        });
        activeSubagents.clear();
        asyncScheduler.dispose();
    }

    // ... 辅助方法省略
}
```

### 3.6 AsyncTask 工具

```java
package io.leavesfly.jimi.tool.async;

@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class AsyncTask extends AbstractTool<AsyncTask.Params> implements WireAware {
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        
        @JsonProperty("subagent_name")
        @JsonPropertyDescription("要使用的子代理名称")
        private String subagentName;
        
        @JsonProperty("prompt")
        @JsonPropertyDescription("发送给子代理的任务提示")
        private String prompt;
        
        @JsonProperty("mode")
        @JsonPropertyDescription("执行模式: fire_and_forget(后台运行), watch(持续监控), wait_complete(等待完成)")
        private String mode = "fire_and_forget";
        
        @JsonProperty("timeout_seconds")
        @JsonPropertyDescription("超时时间（秒），可选")
        private Integer timeoutSeconds;
    }
    
    private Wire parentWire;
    private Runtime runtime;
    private Map<String, Agent> subagents;
    
    @Autowired
    private AsyncSubagentManager asyncSubagentManager;
    
    @Autowired
    public AsyncTask() {
        super("AsyncTask", 
              "启动异步子代理在后台执行任务。子代理独立运行，主对话可继续。\n" +
              "适用场景：长时间构建、日志监控、并行任务处理。\n\n" +
              "可用模式：\n" +
              "- fire_and_forget: 后台运行，立即返回\n" +
              "- watch: 持续监控模式\n" +
              "- wait_complete: 等待完成（同步）",
              Params.class);
    }
    
    @Override
    public void setWire(Wire wire) {
        this.parentWire = wire;
    }
    
    public void setRuntimeParams(Map<String, Agent> subagents, Runtime runtime) {
        this.subagents = subagents;
        this.runtime = runtime;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        // 参数校验
        if (params.getSubagentName() == null || params.getSubagentName().isBlank()) {
            return Mono.just(ToolResult.error("subagent_name 不能为空", "参数错误"));
        }
        if (params.getPrompt() == null || params.getPrompt().isBlank()) {
            return Mono.just(ToolResult.error("prompt 不能为空", "参数错误"));
        }
        
        Agent agent = subagents.get(params.getSubagentName());
        if (agent == null) {
            return Mono.just(ToolResult.error(
                    "子代理不存在: " + params.getSubagentName(),
                    "子代理未找到"
            ));
        }
        
        String mode = params.getMode() != null ? params.getMode() : "fire_and_forget";
        
        return switch (mode) {
            case "fire_and_forget" -> executeFireAndForget(agent, params);
            case "wait_complete" -> executeWaitComplete(agent, params);
            default -> Mono.just(ToolResult.error("未知模式: " + mode, "参数错误"));
        };
    }
    
    private Mono<ToolResult> executeFireAndForget(Agent agent, Params params) {
        Duration timeout = params.getTimeoutSeconds() != null 
                ? Duration.ofSeconds(params.getTimeoutSeconds()) 
                : null;
        
        return asyncSubagentManager.startAsync(
                agent, 
                runtime, 
                params.getPrompt(), 
                parentWire,
                null,  // 无回调
                timeout
        ).map(subagentId -> {
            String output = String.format(
                    "异步子代理已启动\n" +
                    "- ID: %s\n" +
                    "- 名称: %s\n" +
                    "- 模式: 后台运行\n\n" +
                    "使用 /async status %s 查看状态\n" +
                    "使用 /async cancel %s 取消执行",
                    subagentId, agent.getName(), subagentId, subagentId
            );
            return ToolResult.ok(output, "异步子代理已启动");
        });
    }
    
    private Mono<ToolResult> executeWaitComplete(Agent agent, Params params) {
        // 兼容同步模式：复用现有 Task 逻辑
        // 这里可以直接委托给 AsyncSubagentManager，设置阻塞等待
        // 或者提示用户使用原有 Task 工具
        return Mono.just(ToolResult.ok(
                "wait_complete 模式请使用 Task 工具（同步子代理）",
                "模式提示"
        ));
    }
}
```

### 3.7 AsyncCommandHandler 命令处理器

```java
package io.leavesfly.jimi.command.handlers;

@Slf4j
@Component
public class AsyncCommandHandler implements CommandHandler {
    
    @Autowired
    private AsyncSubagentManager asyncSubagentManager;
    
    @Override
    public String getName() {
        return "async";
    }
    
    @Override
    public String getDescription() {
        return "管理异步子代理";
    }
    
    @Override
    public String getUsage() {
        return "/async <list|status|cancel|result> [id]";
    }
    
    @Override
    public Category getCategory() {
        return Category.AGENT;
    }
    
    @Override
    public Mono<HandlerResult> handle(CommandContext context) {
        String[] args = context.getArgs();
        
        if (args.length == 0) {
            return Mono.just(HandlerResult.message(getUsage()));
        }
        
        String subCommand = args[0].toLowerCase();
        
        return switch (subCommand) {
            case "list" -> handleList();
            case "status" -> handleStatus(args);
            case "cancel" -> handleCancel(args);
            case "result" -> handleResult(args);
            default -> Mono.just(HandlerResult.message("未知子命令: " + subCommand));
        };
    }
    
    private Mono<HandlerResult> handleList() {
        List<AsyncSubagent> active = asyncSubagentManager.listActive();
        
        if (active.isEmpty()) {
            return Mono.just(HandlerResult.message("当前没有活跃的异步子代理"));
        }
        
        StringBuilder sb = new StringBuilder("活跃的异步子代理：\n\n");
        for (AsyncSubagent subagent : active) {
            Duration running = Duration.between(subagent.getStartTime(), Instant.now());
            sb.append(String.format("  [%s] %s - %s (运行 %ds)\n",
                    subagent.getId(),
                    subagent.getName(),
                    subagent.getStatus(),
                    running.getSeconds()
            ));
        }
        
        return Mono.just(HandlerResult.message(sb.toString()));
    }
    
    private Mono<HandlerResult> handleStatus(String[] args) {
        if (args.length < 2) {
            return Mono.just(HandlerResult.message("用法: /async status <id>"));
        }
        
        String id = args[1];
        return asyncSubagentManager.getSubagent(id)
                .map(subagent -> {
                    String info = String.format(
                            "子代理状态：\n" +
                            "  ID: %s\n" +
                            "  名称: %s\n" +
                            "  状态: %s\n" +
                            "  模式: %s\n" +
                            "  启动时间: %s\n" +
                            "  %s",
                            subagent.getId(),
                            subagent.getName(),
                            subagent.getStatus(),
                            subagent.getMode(),
                            subagent.getStartTime(),
                            subagent.getEndTime() != null 
                                    ? "结束时间: " + subagent.getEndTime()
                                    : "运行中..."
                    );
                    return HandlerResult.message(info);
                })
                .map(Mono::just)
                .orElse(Mono.just(HandlerResult.message("未找到子代理: " + id)));
    }
    
    private Mono<HandlerResult> handleCancel(String[] args) {
        if (args.length < 2) {
            return Mono.just(HandlerResult.message("用法: /async cancel <id>"));
        }
        
        String id = args[1];
        boolean cancelled = asyncSubagentManager.cancel(id);
        
        return Mono.just(HandlerResult.message(
                cancelled ? "已取消子代理: " + id : "无法取消: " + id
        ));
    }
    
    private Mono<HandlerResult> handleResult(String[] args) {
        if (args.length < 2) {
            return Mono.just(HandlerResult.message("用法: /async result <id>"));
        }
        
        String id = args[1];
        return asyncSubagentManager.getSubagent(id)
                .filter(s -> s.getStatus() == AsyncSubagentStatus.COMPLETED)
                .map(subagent -> HandlerResult.message(
                        "子代理结果：\n\n" + subagent.getResult()
                ))
                .map(Mono::just)
                .orElse(Mono.just(HandlerResult.message("未找到已完成的子代理: " + id)));
    }
}
```

### 3.8 Wire 消息定义

```java
// AsyncSubagentStarted.java
package io.leavesfly.jimi.wire.message;

@Value
public class AsyncSubagentStarted implements WireMessage {
    String subagentId;
    String subagentName;
    String mode;
    Instant startTime;
}

// AsyncSubagentProgress.java
@Value
public class AsyncSubagentProgress implements WireMessage {
    String subagentId;
    String progressInfo;
    int stepNumber;
}

// AsyncSubagentCompleted.java
@Value
public class AsyncSubagentCompleted implements WireMessage {
    String subagentId;
    String result;
    boolean success;
    Duration duration;
}
```

---

## 4. 兼容性设计

### 4.1 与现有 Task 工具的关系

| 特性 | Task（现有） | AsyncTask（新增） |
|------|-------------|------------------|
| 执行模式 | 同步阻塞 | 异步后台 |
| 结果返回 | 直接返回完整结果 | 返回子代理ID |
| 上下文 | 独立上下文 | 独立上下文 |
| Wire事件 | 转发到主Wire | 转发为Progress消息 |
| 使用场景 | 需要结果的任务 | 长时间/后台任务 |

### 4.2 不修改的现有代码

以下文件**完全不修改**，确保向后兼容：

- `Task.java` - 现有同步子代理工具
- `TaskToolProvider.java` - 现有工具提供者
- `AgentExecutor.java` - 执行器逻辑
- `JimiEngine.java` - 引擎核心
- 现有所有 Wire 消息类型

### 4.3 新增的代码

| 文件 | 类型 | 说明 |
|------|------|------|
| `AsyncSubagent.java` | 实体 | 异步子代理数据模型 |
| `AsyncSubagentStatus.java` | 枚举 | 状态定义 |
| `AsyncSubagentMode.java` | 枚举 | 模式定义 |
| `AsyncSubagentCallback.java` | 接口 | 回调接口 |
| `AsyncSubagentManager.java` | Service | 生命周期管理 |
| `AsyncTask.java` | 工具 | LLM可调用的工具 |
| `AsyncTaskToolProvider.java` | Provider | 工具注册 |
| `AsyncCommandHandler.java` | Handler | /async 命令 |
| `AsyncSubagentStarted.java` | 消息 | Wire消息 |
| `AsyncSubagentProgress.java` | 消息 | Wire消息 |
| `AsyncSubagentCompleted.java` | 消息 | Wire消息 |

---

## 5. 实施计划

### 5.1 阶段划分

| 阶段 | 内容 | 文件数 | 预估工作量 |
|------|------|--------|-----------|
| Phase 1 | 核心实体和枚举 | 4 | 低 |
| Phase 2 | AsyncSubagentManager | 1 | 中 |
| Phase 3 | AsyncTask 工具 | 2 | 中 |
| Phase 4 | Wire 消息 | 3 | 低 |
| Phase 5 | /async 命令 | 1 | 低 |
| Phase 6 | 集成测试 | - | 中 |

### 5.2 实施顺序

1. **Phase 1**: 创建基础实体类
   - `AsyncSubagentStatus.java`
   - `AsyncSubagentMode.java`
   - `AsyncSubagentCallback.java`
   - `AsyncSubagent.java`

2. **Phase 2**: 实现管理器
   - `AsyncSubagentManager.java`

3. **Phase 3**: 实现工具
   - `AsyncTask.java`
   - `AsyncTaskToolProvider.java`

4. **Phase 4**: 添加Wire消息
   - `AsyncSubagentStarted.java`
   - `AsyncSubagentProgress.java`
   - `AsyncSubagentCompleted.java`

5. **Phase 5**: 实现命令
   - `AsyncCommandHandler.java`

---

## 6. 使用示例

### 6.1 后台构建

```
用户: 帮我在后台运行 mvn clean install

LLM 调用 AsyncTask:
{
  "subagent_name": "Build-Agent",
  "prompt": "执行 mvn clean install 并报告结果",
  "mode": "fire_and_forget",
  "timeout_seconds": 600
}

返回:
异步子代理已启动
- ID: a1b2c3d4
- 名称: Build-Agent
- 模式: 后台运行

使用 /async status a1b2c3d4 查看状态
```

### 6.2 查看状态

```
用户: /async list

输出:
活跃的异步子代理：

  [a1b2c3d4] Build-Agent - RUNNING (运行 45s)
  [e5f6g7h8] Test-Agent - RUNNING (运行 12s)
```

### 6.3 获取结果

```
用户: /async result a1b2c3d4

输出:
子代理结果：

BUILD SUCCESS
构建用时: 2分15秒
...
```

---

## 7. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 资源泄漏 | 中 | Disposable 管理 + @PreDestroy 钩子 |
| 线程饥饿 | 低 | BoundedElastic 线程池限制 |
| 内存溢出 | 低 | 限制已完成缓存数量 |
| 主进程退出 | 中 | 优雅关闭机制 |

---

## 8. 后续扩展

- **Watch 模式完整实现**：持续监控日志文件
- **通知机制**：完成后发送系统通知/邮件
- **持久化**：子代理状态持久化到磁盘
- **Web UI**：可视化管理界面
