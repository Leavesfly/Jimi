package io.leavesfly.jimi.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.graph.model.*;
import io.leavesfly.jimi.graph.navigator.GraphNavigator;
import io.leavesfly.jimi.graph.navigator.ImpactAnalyzer;
import io.leavesfly.jimi.graph.search.GraphSearchEngine;
import io.leavesfly.jimi.graph.search.HybridSearchEngine;
import io.leavesfly.jimi.graph.store.CodeGraphStore;
import io.leavesfly.jimi.graph.store.InMemoryCodeGraphStore;
import io.leavesfly.jimi.retrieval.EmbeddingProvider;
import io.leavesfly.jimi.retrieval.MockEmbeddingProvider;
import io.leavesfly.jimi.retrieval.VectorStore;
import io.leavesfly.jimi.retrieval.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混合检索功能测试
 */
class HybridSearchTest {
    
    private CodeGraphStore graphStore;
    private VectorStore vectorStore;
    private EmbeddingProvider embeddingProvider;
    
    private GraphSearchEngine graphSearchEngine;
    private HybridSearchEngine hybridSearchEngine;
    
    @BeforeEach
    void setUp() {
        // 初始化组件
        graphStore = new InMemoryCodeGraphStore();
        embeddingProvider = new MockEmbeddingProvider(384, "test-mock");
        vectorStore = new InMemoryVectorStore(new ObjectMapper());
        
        GraphNavigator navigator = new GraphNavigator(graphStore);
        ImpactAnalyzer impactAnalyzer = new ImpactAnalyzer(graphStore);
        
        graphSearchEngine = new GraphSearchEngine(graphStore, navigator, impactAnalyzer);
        hybridSearchEngine = new HybridSearchEngine(graphSearchEngine, vectorStore, embeddingProvider);
        
        // 创建测试数据
        setupTestData();
    }
    
    /**
     * 创建测试数据
     */
    private void setupTestData() {
        // 创建图实体
        for (int i = 1; i <= 5; i++) {
            CodeEntity entity = CodeEntity.builder()
                .id("CLASS:TestService" + i)
                .type(EntityType.CLASS)
                .name("TestService" + i)
                .qualifiedName("com.example.TestService" + i)
                .filePath("TestService" + i + ".java")
                .startLine(1)
                .endLine(100)
                .build();
            graphStore.addEntity(entity).block();
        }
        
        // 创建关系
        graphStore.addRelation(CodeRelation.builder()
            .sourceId("CLASS:TestService1")
            .targetId("CLASS:TestService2")
            .type(RelationType.CALLS)
            .build()).block();
    }
    
    @Test
    void testGraphSearch_SymbolSearch() {
        // 符号搜索: 查找 "TestService1"
        GraphSearchEngine.GraphSearchResult result = graphSearchEngine.searchBySymbol(
            "TestService1",
            null,
            10
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertTrue(result.getTotalResults() >= 1);
        
        // 验证第一个结果是精确匹配
        GraphSearchEngine.ScoredEntity top = result.getResults().get(0);
        assertEquals("TestService1", top.getEntity().getName());
        assertEquals(1.0, top.getScore(), 0.001);
        
        System.out.println("✅ Graph search (symbol) test passed: " + 
                          result.getTotalResults() + " results found");
    }
    
    @Test
    void testGraphSearch_PartialMatch() {
        // 部分匹配: 查找 "Service"
        GraphSearchEngine.GraphSearchResult result = graphSearchEngine.searchBySymbol(
            "Service",
            Collections.singleton(EntityType.CLASS),
            10
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertEquals(5, result.getTotalResults()); // 应该找到 5 个 TestService
        
        System.out.println("✅ Graph search (partial match) test passed: " + 
                          result.getTotalResults() + " results found");
    }
    
    @Test
    void testGraphSearch_RelationSearch() {
        // 关系搜索: 查找与 TestService1 有关系的实体
        GraphSearchEngine.GraphSearchResult result = graphSearchEngine.searchByRelation(
            "CLASS:TestService1",
            Collections.singleton(RelationType.CALLS),
            GraphNavigator.Direction.OUTGOING,
            10
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertEquals(1, result.getTotalResults()); // 应该找到 TestService2
        
        GraphSearchEngine.ScoredEntity related = result.getResults().get(0);
        assertEquals("TestService2", related.getEntity().getName());
        
        System.out.println("✅ Graph search (relation) test passed: found " + 
                          related.getEntity().getName());
    }
    
    @Test
    void testGraphSearch_FileSearch() {
        // 文件搜索
        GraphSearchEngine.GraphSearchResult result = graphSearchEngine.searchByFile(
            "TestService3.java",
            10
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertTrue(result.getTotalResults() >= 1);
        
        // 验证匹配的文件路径
        GraphSearchEngine.ScoredEntity top = result.getResults().get(0);
        assertTrue(top.getEntity().getFilePath().contains("TestService3.java"));
        
        System.out.println("✅ Graph search (file) test passed: found in " + 
                          top.getEntity().getFilePath());
    }
    
    @Test
    void testGraphSearch_ContextSearch() {
        // 上下文搜索
        GraphSearchEngine.ContextQuery contextQuery = GraphSearchEngine.ContextQuery.builder()
            .description("Find TestService1 and related")
            .symbols(Arrays.asList("TestService1"))
            .entityTypes(Collections.singleton(EntityType.CLASS))
            .relationTypes(Collections.singleton(RelationType.CALLS))
            .includeRelated(true)
            .limit(20)
            .build();
        
        GraphSearchEngine.GraphSearchResult result = graphSearchEngine.searchByContext(contextQuery).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertTrue(result.getTotalResults() >= 1);
        
        System.out.println("✅ Graph search (context) test passed: " + 
                          result.getTotalResults() + " results found");
    }
    
    @Test
    void testHybridSearch_SmartSearch() {
        // 智能搜索 (自动选择策略)
        HybridSearchEngine.HybridSearchResult result = hybridSearchEngine.smartSearch(
            "TestService1",
            5
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertNotNull(result.getConfig());
        
        // 验证图检索启用 (因为包含符号)
        assertTrue(result.getConfig().isEnableGraphSearch());
        
        System.out.println("✅ Hybrid search (smart) test passed: " + 
                          result.getTotalResults() + " results found");
        System.out.println("   Strategy: graph=" + result.getConfig().isEnableGraphSearch() + 
                          ", vector=" + result.getConfig().isEnableVectorSearch());
    }
    
    @Test
    void testHybridSearch_CustomConfig() {
        // 自定义配置搜索
        HybridSearchEngine.HybridSearchConfig config = HybridSearchEngine.HybridSearchConfig.builder()
            .enableGraphSearch(true)
            .enableVectorSearch(false)
            .graphTopK(10)
            .finalTopK(5)
            .graphWeight(1.0)
            .fusionStrategy(HybridSearchEngine.FusionStrategy.WEIGHTED_SUM)
            .build();
        
        HybridSearchEngine.HybridSearchResult result = hybridSearchEngine.search(
            "Service",
            config
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertTrue(result.getTotalResults() > 0);
        assertTrue(result.getTotalResults() <= config.getFinalTopK());
        
        System.out.println("✅ Hybrid search (custom config) test passed: " + 
                          result.getTotalResults() + " results (topK=" + config.getFinalTopK() + ")");
    }
    
    @Test
    void testHybridSearch_FusionStrategies() {
        HybridSearchEngine.FusionStrategy[] strategies = {
            HybridSearchEngine.FusionStrategy.WEIGHTED_SUM,
            HybridSearchEngine.FusionStrategy.RRF,
            HybridSearchEngine.FusionStrategy.MAX,
            HybridSearchEngine.FusionStrategy.MULTIPLICATIVE
        };
        
        for (HybridSearchEngine.FusionStrategy strategy : strategies) {
            HybridSearchEngine.HybridSearchConfig config = HybridSearchEngine.HybridSearchConfig.builder()
                .enableGraphSearch(true)
                .enableVectorSearch(false)
                .graphTopK(5)
                .finalTopK(5)
                .fusionStrategy(strategy)
                .build();
            
            HybridSearchEngine.HybridSearchResult result = hybridSearchEngine.search(
                "TestService",
                config
            ).block();
            
            assertNotNull(result);
            assertTrue(result.getSuccess());
            
            System.out.println("✅ Fusion strategy test passed: " + strategy + 
                              " -> " + result.getTotalResults() + " results");
        }
    }
}
