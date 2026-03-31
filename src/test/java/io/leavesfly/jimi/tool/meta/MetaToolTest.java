package io.leavesfly.jimi.tool.meta;

import io.leavesfly.jimi.config.info.MetaToolConfig;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.core.meta.MetaTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MetaTool 单元测试
 */
@ExtendWith(MockitoExtension.class)
class MetaToolTest {

    @Mock
    private ToolRegistry mockToolRegistry;

    private MetaToolConfig config;
    private MetaTool metaTool;

    @BeforeEach
    void setUp() {
        config = new MetaToolConfig();
        config.setEnabled(true);
        config.setMaxExecutionTime(30);
        config.setMaxCodeLength(10000);
        config.setLogExecutionDetails(false);

        metaTool = new MetaTool(config);
        metaTool.setToolRegistry(mockToolRegistry);
    }

    // =========================================================
    // 基本属性测试
    // =========================================================

    @Test
    @DisplayName("工具名称应为 MetaTool")
    void testGetName() {
        assertEquals("MetaTool", metaTool.getName());
    }

    @Test
    @DisplayName("工具描述不应为空")
    void testGetDescription() {
        String description = metaTool.getDescription();
        assertNotNull(description);
        assertFalse(description.isBlank());
        // 描述中应包含关键使用说明
        assertTrue(description.contains("callTool"));
    }

    @Test
    @DisplayName("参数类型应为 MetaTool.Params")
    void testGetParamsType() {
        assertEquals(MetaTool.Params.class, metaTool.getParamsType());
    }

    // =========================================================
    // 参数验证测试
    // =========================================================

    @Nested
    @DisplayName("参数验证")
    class ParamsValidationTests {

        @Test
        @DisplayName("代码为 null 时验证失败")
        void testValidateParams_NullCode() {
            MetaTool.Params params = new MetaTool.Params(null, 10, null);
            assertFalse(metaTool.validateParams(params));
        }

        @Test
        @DisplayName("代码为空字符串时验证失败")
        void testValidateParams_EmptyCode() {
            MetaTool.Params params = new MetaTool.Params("   ", 10, null);
            assertFalse(metaTool.validateParams(params));
        }

        @Test
        @DisplayName("代码超出最大长度时验证失败")
        void testValidateParams_CodeTooLong() {
            String longCode = "x".repeat(config.getMaxCodeLength() + 1);
            MetaTool.Params params = new MetaTool.Params(longCode, 10, null);
            assertFalse(metaTool.validateParams(params));
        }

        @Test
        @DisplayName("timeout 小于 1 秒时验证失败")
        void testValidateParams_TimeoutTooSmall() {
            MetaTool.Params params = new MetaTool.Params("return \"ok\";", 0, null);
            assertFalse(metaTool.validateParams(params));
        }

        @Test
        @DisplayName("timeout 超过最大值时验证失败")
        void testValidateParams_TimeoutTooLarge() {
            int overMax = config.getMaxExecutionTime() + 1;
            MetaTool.Params params = new MetaTool.Params("return \"ok\";", overMax, null);
            assertFalse(metaTool.validateParams(params));
        }

        @Test
        @DisplayName("合法参数应验证通过")
        void testValidateParams_Valid() {
            MetaTool.Params params = new MetaTool.Params("return \"hello\";", 10, null);
            assertTrue(metaTool.validateParams(params));
        }
    }

    // =========================================================
    // execute() 前置检查测试
    // =========================================================

    @Nested
    @DisplayName("execute() 前置检查")
    class ExecutePreCheckTests {

        @Test
        @DisplayName("未注入 ToolRegistry 时应返回错误")
        void testExecute_NullToolRegistry() {
            MetaTool toolWithoutRegistry = new MetaTool(config);
            // 不调用 setToolRegistry()

            MetaTool.Params params = new MetaTool.Params("return \"ok\";", 10, null);
            ToolResult result = toolWithoutRegistry.execute(params).block();

            assertNotNull(result);
            assertTrue(result.isError());
            assertTrue(result.getMessage().contains("ToolRegistry"));
        }

        @Test
        @DisplayName("代码为空时 execute() 应返回错误")
        void testExecute_EmptyCode() {
            MetaTool.Params params = new MetaTool.Params("", 10, null);
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            assertTrue(result.isError());
        }

        @Test
        @DisplayName("代码超出长度限制时应返回错误")
        void testExecute_CodeTooLong() {
            String longCode = "x".repeat(config.getMaxCodeLength() + 1);
            MetaTool.Params params = new MetaTool.Params(longCode, 10, null);
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            assertTrue(result.isError());
            assertTrue(result.getMessage().contains("length") || result.getMessage().contains("exceeds"));
        }

        @Test
        @DisplayName("包含危险操作 System.exit 时应返回安全错误")
        void testExecute_DangerousCode_SystemExit() {
            MetaTool.Params params = new MetaTool.Params("System.exit(1);", 10, null);
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            assertTrue(result.isError());
            assertTrue(result.getMessage().toLowerCase().contains("dangerous")
                    || result.getMessage().toLowerCase().contains("security"));
        }

        @Test
        @DisplayName("包含危险操作 ProcessBuilder 时应返回安全错误")
        void testExecute_DangerousCode_ProcessBuilder() {
            MetaTool.Params params = new MetaTool.Params(
                    "ProcessBuilder pb = new ProcessBuilder();", 10, null);
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            assertTrue(result.isError());
        }
    }

    // =========================================================
    // execute() 正常执行测试
    // =========================================================

    @Nested
    @DisplayName("execute() 正常执行")
    class ExecuteSuccessTests {

        @Test
        @DisplayName("执行简单 return 语句应成功返回结果")
        void testExecute_SimpleReturn() {
            MetaTool.Params params = new MetaTool.Params("return \"hello\";", 10, null);
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            assertTrue(result.isOk());
            assertTrue(result.getOutput().contains("hello"));
        }

        @Test
        @DisplayName("执行字符串拼接代码应返回正确结果")
        void testExecute_StringConcatenation() {
            String code = "return \"foo\" + \"bar\";";
            MetaTool.Params params = new MetaTool.Params(code, 10, null);
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            assertTrue(result.isOk());
            assertTrue(result.getOutput().contains("foobar"));
        }

        @Test
        @DisplayName("执行带循环的代码应返回正确结果")
        void testExecute_LoopCode() {
            String code = """
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= 5; i++) {
                        sb.append(i);
                    }
                    return sb.toString();
                    """;
            MetaTool.Params params = new MetaTool.Params(code, 10, null);
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            assertTrue(result.isOk());
            assertTrue(result.getOutput().contains("12345"));
        }

        @Test
        @DisplayName("调用 callTool 时 ToolRegistry 应被正确调用")
        void testExecute_CallTool_InvokesRegistry() {
            when(mockToolRegistry.execute(eq("MockTool"), anyString()))
                    .thenReturn(Mono.just(ToolResult.ok("tool_output", "ok")));

            String code = """
                    String r = callTool("MockTool", "{\\"key\\":\\"value\\"}");
                    return r;
                    """;
            MetaTool.Params params = new MetaTool.Params(code, 10, null);
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            verify(mockToolRegistry, times(1)).execute(eq("MockTool"), anyString());
            assertTrue(result.isOk());
            assertTrue(result.getOutput().contains("tool_output"));
        }

        @Test
        @DisplayName("指定 allowedTools 时仅允许调用白名单工具")
        void testExecute_AllowedTools_Respected() {
            when(mockToolRegistry.execute(eq("AllowedTool"), anyString()))
                    .thenReturn(Mono.just(ToolResult.ok("allowed_result", "ok")));

            String code = """
                    String r = callTool("AllowedTool", "{}");
                    return r;
                    """;
            MetaTool.Params params = new MetaTool.Params(code, 10, List.of("AllowedTool"));
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            // AllowedTool 在白名单中，应能正常调用
            verify(mockToolRegistry, atLeastOnce()).execute(eq("AllowedTool"), anyString());
        }

        @Test
        @DisplayName("执行结果携带成功消息")
        void testExecute_SuccessMessage() {
            MetaTool.Params params = new MetaTool.Params("return \"done\";", 10, null);
            ToolResult result = metaTool.execute(params).block();

            assertNotNull(result);
            assertTrue(result.isOk());
            assertNotNull(result.getMessage());
            assertFalse(result.getMessage().isBlank());
        }
    }

    // =========================================================
    // timeout 边界测试
    // =========================================================

    @Nested
    @DisplayName("timeout 边界")
    class TimeoutTests {

        @Test
        @DisplayName("timeout 设为 1 秒（最小合法值）应通过验证")
        void testValidateParams_MinTimeout() {
            MetaTool.Params params = new MetaTool.Params("return \"ok\";", 1, null);
            assertTrue(metaTool.validateParams(params));
        }

        @Test
        @DisplayName("timeout 等于最大执行时间时应通过验证")
        void testValidateParams_MaxTimeout() {
            MetaTool.Params params = new MetaTool.Params("return \"ok\";", config.getMaxExecutionTime(), null);
            assertTrue(metaTool.validateParams(params));
        }
    }

    // =========================================================
    // Params 数据类测试
    // =========================================================

    @Nested
    @DisplayName("Params 数据类")
    class ParamsDataClassTests {

        @Test
        @DisplayName("默认构造器 timeout 默认值为 30")
        void testParams_DefaultTimeout() {
            MetaTool.Params params = new MetaTool.Params();
            assertEquals(30, params.getTimeout());
        }

        @Test
        @DisplayName("全参构造器正确赋值")
        void testParams_AllArgsConstructor() {
            List<String> tools = List.of("Tool1", "Tool2");
            MetaTool.Params params = new MetaTool.Params("return \"x\";", 15, tools);

            assertEquals("return \"x\";", params.getCode());
            assertEquals(15, params.getTimeout());
            assertEquals(tools, params.getAllowedTools());
        }

        @Test
        @DisplayName("setter/getter 正常工作")
        void testParams_SetterGetter() {
            MetaTool.Params params = new MetaTool.Params();
            params.setCode("return \"test\";");
            params.setTimeout(20);
            params.setAllowedTools(List.of("ReadFile"));

            assertEquals("return \"test\";", params.getCode());
            assertEquals(20, params.getTimeout());
            assertEquals(1, params.getAllowedTools().size());
        }
    }
}
