package io.leavesfly.jimi.core.engine.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.info.ToolOutputConfig;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.hook.HookContext;
import io.leavesfly.jimi.core.hook.HookExecutor;
import io.leavesfly.jimi.core.hook.HookRegistry;
import io.leavesfly.jimi.core.hook.HookType;
import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.WireImpl;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolDispatcherHookContextTest {

    @TempDir
    Path workDir;

    @Test
    void preToolHookReceivesSessionAndParsedToolInput() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry(objectMapper);
        AtomicBoolean executed = new AtomicBoolean();
        registry.register(new StubTool(executed));

        HookRegistry hooks = mock(HookRegistry.class);
        when(hooks.triggerWithResults(eq(HookType.PRE_TOOL_USE), any(HookContext.class)))
                .thenReturn(Mono.just(List.of(HookExecutor.HookResult.success())));
        when(hooks.trigger(eq(HookType.POST_TOOL_USE), any(HookContext.class))).thenReturn(Mono.empty());

        ToolDispatcher dispatcher = new ToolDispatcher(registry, workDir, new WireImpl(),
                new ToolErrorTracker(), hooks, new ToolOutputConfig(), "session-123");
        Context context = new Context(workDir.resolve("history.jsonl"), objectMapper);
        ToolCall call = ToolCall.builder()
                .id("call-123")
                .type("function")
                .function(FunctionCall.builder()
                        .name("StubTool")
                        .arguments("{\"command\":\"rm -rf ./build\",\"force\":true}")
                        .build())
                .build();

        Message result = dispatcher.executeToolCall(call, context).block();
        assertNotNull(result);
        assertEquals(true, executed.get());

        ArgumentCaptor<HookContext> captor = ArgumentCaptor.forClass(HookContext.class);
        verify(hooks).triggerWithResults(eq(HookType.PRE_TOOL_USE), captor.capture());
        HookContext hook = captor.getValue();
        assertEquals("session-123", hook.getSessionId());
        assertEquals("StubTool", hook.getToolName());
        assertEquals("call-123", hook.getToolCallId());
        assertEquals("rm -rf ./build", hook.getToolInput().get("command"));
        assertEquals(Boolean.TRUE, hook.getToolInput().get("force"));
    }

    @Test
    void blockedPreToolHookStopsToolExecution() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry(objectMapper);
        AtomicBoolean executed = new AtomicBoolean();
        registry.register(new StubTool(executed));

        HookRegistry hooks = mock(HookRegistry.class);
        when(hooks.triggerWithResults(eq(HookType.PRE_TOOL_USE), any(HookContext.class)))
                .thenReturn(Mono.just(List.of(HookExecutor.HookResult.blocked("HOL Guard denied", "HOL Guard denied"))));

        ToolDispatcher dispatcher = new ToolDispatcher(registry, workDir, new WireImpl(),
                new ToolErrorTracker(), hooks, new ToolOutputConfig(), "session-123");
        Context context = new Context(workDir.resolve("history.jsonl"), objectMapper);
        ToolCall call = ToolCall.builder()
                .id("call-124")
                .type("function")
                .function(FunctionCall.builder().name("StubTool").arguments("{\"command\":\"rm -rf ./build\"}").build())
                .build();

        Message result = dispatcher.executeToolCall(call, context).block();
        assertNotNull(result);
        assertFalse(executed.get());
        assertEquals(true, result.getTextContent().contains("HOL Guard denied"));
    }

    @Data
    public static class StubParams {
        private String command;
        private boolean force;
    }

    private static class StubTool extends AbstractTool<StubParams> {
        private final AtomicBoolean executed;

        StubTool(AtomicBoolean executed) {
            super("StubTool", "stub", StubParams.class);
            this.executed = executed;
        }

        @Override
        public Mono<ToolResult> execute(StubParams params) {
            executed.set(true);
            return Mono.just(ToolResult.ok("ok", "ok"));
        }
    }
}
