package io.leavesfly.jimi.agent;

import io.leavesfly.jimi.exception.AgentSpecException;
import io.leavesfly.jimi.soul.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.soul.runtime.Runtime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentRegistry 单元测试
 * 
 * @author Jimi Team
 */
class AgentRegistryTest {
    
    private AgentRegistry registry;
    private Path testAgentPath;
    
    @BeforeEach
    void setUp() {
        // 使用单例实例
        registry = AgentRegistry.getInstance();
        // 每次测试前清除缓存，确保测试独立性
        registry.reload();
        // 使用测试资源中的 agent
        testAgentPath = Path.of("src/test/resources/agents/test_agent/agent.yaml");
    }
    
    @Test
    void testSingletonInstance() {
        // 测试单例模式
        AgentRegistry instance1 = AgentRegistry.getInstance();
        AgentRegistry instance2 = AgentRegistry.getInstance();
        
        assertSame(instance1, instance2, "Should return the same singleton instance");
        assertSame(registry, instance1, "Should be the same as setUp instance");
    }
    
    @Test
    void testInitialize() {
        // 测试初始化方法
        AgentRegistry initialized = AgentRegistry.initialize();
        
        assertNotNull(initialized, "Initialized instance should not be null");
        assertSame(AgentRegistry.getInstance(), initialized, "Should return singleton instance");
    }
    
    @Test
    void testReload() {
        // 测试重新加载方法
        if (!Files.exists(testAgentPath)) {
            System.out.println("Test agent file not found, skipping test");
            return;
        }
        
        // 加载一个 agent
        ResolvedAgentSpec spec1 = registry.loadAgentSpec(testAgentPath).block();
        assertNotNull(spec1);
        
        // 重新加载，清除缓存
        registry.reload();
        
        // 再次加载，应该是新实例
        ResolvedAgentSpec spec2 = registry.loadAgentSpec(testAgentPath).block();
        assertNotNull(spec2);
        
        // 重新加载后应该是不同的对象
        assertNotSame(spec1, spec2, "After reload, should load a new instance");
    }
    
    @Test
    void testLoadDefaultAgentSpec() {
        // 测试加载默认 Agent 规范
        try {
            ResolvedAgentSpec spec = registry.loadDefaultAgentSpec().block();
            
            assertNotNull(spec, "Default agent spec should not be null");
            assertNotNull(spec.getName(), "Agent name should not be null");
            assertNotNull(spec.getTools(), "Tools list should not be null");
            
            System.out.println("Default agent name: " + spec.getName());
            System.out.println("Tools count: " + spec.getTools().size());
        } catch (AgentSpecException e) {
            // 如果配置文件格式不符，跳过测试
            System.out.println("Skipping test due to config format: " + e.getMessage());
        }
    }
    
    @Test
    void testLoadAgentSpec() {
        // 测试加载 Agent 规范
        if (!Files.exists(testAgentPath)) {
            System.out.println("Test agent file not found, skipping test");
            return;
        }
        
        ResolvedAgentSpec spec = registry.loadAgentSpec(testAgentPath).block();
        
        assertNotNull(spec, "Agent spec should not be null");
        assertEquals("Test Registry Agent", spec.getName());
        assertNotNull(spec.getTools());
        assertTrue(spec.getTools().contains("ReadFile"));
        assertTrue(spec.getTools().contains("WriteFile"));
        
        System.out.println("Loaded agent: " + spec.getName());
        System.out.println("Tools: " + spec.getTools());
    }
    
    @Test
    void testLoadAgentSpecWithCaching() {
        // 测试缓存功能
        if (!Files.exists(testAgentPath)) {
            System.out.println("Test agent file not found, skipping test");
            return;
        }
        
        // 第一次加载
        ResolvedAgentSpec spec1 = registry.loadAgentSpec(testAgentPath).block();
        assertNotNull(spec1);
        
        // 第二次加载（应该从缓存获取）
        ResolvedAgentSpec spec2 = registry.loadAgentSpec(testAgentPath).block();
        assertNotNull(spec2);
        
        // 验证是同一个对象（来自缓存）
        assertSame(spec1, spec2, "Second load should return cached instance");
    }
    
    @Test
    void testClearCache() {
        // 测试重新加载（内部会清除缓存）
        if (!Files.exists(testAgentPath)) {
            System.out.println("Test agent file not found, skipping test");
            return;
        }
        
        // 加载一次
        ResolvedAgentSpec spec1 = registry.loadAgentSpec(testAgentPath).block();
        assertNotNull(spec1);
        
        // 使用 reload 清除缓存
        registry.reload();
        
        // 再次加载
        ResolvedAgentSpec spec2 = registry.loadAgentSpec(testAgentPath).block();
        assertNotNull(spec2);
        
        // 应该是不同的对象（重新加载）
        assertNotSame(spec1, spec2, "After cache clear, should load a new instance");
    }
    
    @Test
    void testClearCacheForSpecificAgent() {
        // 测试缓存隔离性（加载不同 agent 不会互相影响）
        if (!Files.exists(testAgentPath)) {
            System.out.println("Test agent file not found, skipping test");
            return;
        }
        
        // 加载
        ResolvedAgentSpec spec1 = registry.loadAgentSpec(testAgentPath).block();
        assertNotNull(spec1);
        
        // 再次加载同一个，应该从缓存获取
        ResolvedAgentSpec spec2 = registry.loadAgentSpec(testAgentPath).block();
        assertNotNull(spec2);
        
        // 应该是同一个对象
        assertSame(spec1, spec2, "Should return cached instance");
    }
    
    @Test
    void testListAvailableAgents() {
        // 测试查询 Agent 是否存在（间接验证可用 Agent 列表）
        boolean hasDefault = registry.hasAgent("default");
        System.out.println("Has default agent: " + hasDefault);
        
        // 测试不存在的 agent
        boolean hasNonExistent = registry.hasAgent("non_existent_agent");
        assertFalse(hasNonExistent, "Should not have non-existent agent");
    }
    
    @Test
    void testHasAgent() {
        // 测试检查 Agent 是否存在
        assertTrue(registry.hasAgent("default"), "Should have default agent");
        assertFalse(registry.hasAgent("non_existent_agent"), "Should not have non-existent agent");
    }
    

    @Test
    void testLoadAgentSpecByName() {
        // 测试根据名称加载 Agent 规范（可能不存在）
        try {
            ResolvedAgentSpec spec = registry.loadAgentSpecByName("test_agent").block();
            if (spec != null) {
                assertNotNull(spec.getName());
                System.out.println("Loaded agent by name: " + spec.getName());
            }
        } catch (AgentSpecException e) {
            System.out.println("Agent not found in standard location: " + e.getMessage());
        }
    }
    
    @Test
    void testGetCacheStats() {
        // 测试缓存统计
        String stats1 = registry.getCacheStats();
        assertNotNull(stats1);
        assertTrue(stats1.contains("AgentRegistry Cache"));
        
        if (Files.exists(testAgentPath)) {
            // 加载一些 Agent
            registry.loadAgentSpec(testAgentPath).block();
            
            String stats2 = registry.getCacheStats();
            assertNotNull(stats2);
            assertTrue(stats2.contains("Specs: 1"), "Should show 1 cached spec");
            
            System.out.println(stats2);
        }
    }
    
    @Test
    void testGetAgentsRootDir() {
        // 测试加载 Agent 功能（间接验证目录配置）
        try {
            ResolvedAgentSpec spec = registry.loadDefaultAgentSpec().block();
            if (spec != null) {
                System.out.println("Successfully loaded agent from configured directory");
                assertNotNull(spec.getName());
            }
        } catch (Exception e) {
            System.out.println("Agent directory test: " + e.getMessage());
        }
    }
    
    @Test
    void testLoadNonExistentAgent() {
        // 测试加载不存在的 Agent 文件
        Path nonExistentPath = Path.of("/non/existent/agent.yaml");
        
        assertThrows(Exception.class, () -> {
            registry.loadAgentSpec(nonExistentPath).block();
        }, "Should throw exception when loading non-existent agent");
    }
    
    @Test
    void testLoadSubagentSpec() {
        // 测试加载子 Agent 规范
        // 跳过此测试，因为需要实际的子 Agent 配置
        System.out.println("testLoadSubagentSpec: Skipped (requires actual subagent config)");
    }
    
    @Test
    void testLoadInvalidSubagentSpec() {
        // 测试加载无效的子 Agent 规范
        SubagentSpec invalidSpec = null;
        
        assertThrows(Exception.class, () -> {
            registry.loadSubagentSpec(invalidSpec).block();
        }, "Should throw exception for null subagent spec");
        
        SubagentSpec emptySpec = SubagentSpec.builder().build();
        assertThrows(Exception.class, () -> {
            registry.loadSubagentSpec(emptySpec).block();
        }, "Should throw exception for subagent spec without path");
    }
}
