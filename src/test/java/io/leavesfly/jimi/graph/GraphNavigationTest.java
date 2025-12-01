package io.leavesfly.jimi.graph;

import io.leavesfly.jimi.graph.model.*;
import io.leavesfly.jimi.graph.navigator.*;
import io.leavesfly.jimi.graph.store.CodeGraphStore;
import io.leavesfly.jimi.graph.store.InMemoryCodeGraphStore;
import io.leavesfly.jimi.graph.visualization.GraphVisualizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图导航和查询功能测试
 */
class GraphNavigationTest {
    
    private CodeGraphStore store;
    private GraphNavigator navigator;
    private ImpactAnalyzer impactAnalyzer;
    private PathFinder pathFinder;
    private GraphVisualizer visualizer;
    
    @BeforeEach
    void setUp() {
        store = new InMemoryCodeGraphStore();
        navigator = new GraphNavigator(store);
        impactAnalyzer = new ImpactAnalyzer(store);
        pathFinder = new PathFinder(store);
        visualizer = new GraphVisualizer(store);
        
        // 创建测试图
        setupTestGraph();
    }
    
    /**
     * 创建测试图:
     * ServiceA -> ServiceB -> ServiceC
     * ServiceA -> ServiceD
     * ServiceB -> ServiceD
     */
    private void setupTestGraph() {
        // 创建实体
        for (char c = 'A'; c <= 'D'; c++) {
            CodeEntity entity = CodeEntity.builder()
                .id("CLASS:Service" + c)
                .type(EntityType.CLASS)
                .name("Service" + c)
                .qualifiedName("com.example.Service" + c)
                .filePath("Service" + c + ".java")
                .build();
            store.addEntity(entity).block();
        }
        
        // 创建关系
        store.addRelation(CodeRelation.builder()
            .sourceId("CLASS:ServiceA")
            .targetId("CLASS:ServiceB")
            .type(RelationType.CALLS)
            .build()).block();
        
        store.addRelation(CodeRelation.builder()
            .sourceId("CLASS:ServiceB")
            .targetId("CLASS:ServiceC")
            .type(RelationType.CALLS)
            .build()).block();
        
        store.addRelation(CodeRelation.builder()
            .sourceId("CLASS:ServiceA")
            .targetId("CLASS:ServiceD")
            .type(RelationType.CALLS)
            .build()).block();
        
        store.addRelation(CodeRelation.builder()
            .sourceId("CLASS:ServiceB")
            .targetId("CLASS:ServiceD")
            .type(RelationType.CALLS)
            .build()).block();
    }
    
    @Test
    void testMultiHopNavigation() {
        // 从 ServiceA 开始进行2跳导航
        GraphNavigator.NavigationResult result = navigator.multiHopNavigation(
            "CLASS:ServiceA",
            Collections.singleton(RelationType.CALLS),
            2,
            entity -> true
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertTrue(result.getTotalEntities() >= 3); // A, B, C, D
        
        System.out.println("✅ Multi-hop navigation test passed: " + 
                          result.getTotalEntities() + " entities found");
    }
    
    @Test
    void testImpactAnalysisDownstream() {
        // 分析修改 ServiceB 的下游影响
        ImpactAnalyzer.ImpactAnalysisResult result = impactAnalyzer.analyzeImpact(
            "CLASS:ServiceB",
            ImpactAnalyzer.AnalysisType.DOWNSTREAM,
            3
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        
        // ServiceB 的下游应该包括 ServiceA (谁调用了 ServiceB)
        List<CodeEntity> downstream = result.getDownstreamEntities();
        assertNotNull(downstream);
        assertTrue(downstream.stream()
            .anyMatch(e -> e.getName().equals("ServiceA")));
        
        System.out.println("✅ Impact analysis (downstream) test passed: " + 
                          downstream.size() + " entities affected");
    }
    
    @Test
    void testImpactAnalysisUpstream() {
        // 分析 ServiceB 的上游依赖
        ImpactAnalyzer.ImpactAnalysisResult result = impactAnalyzer.analyzeImpact(
            "CLASS:ServiceB",
            ImpactAnalyzer.AnalysisType.UPSTREAM,
            3
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        
        // ServiceB 的上游应该包括 ServiceC 和 ServiceD (ServiceB 调用了它们)
        List<CodeEntity> upstream = result.getUpstreamEntities();
        assertNotNull(upstream);
        assertEquals(2, upstream.size());
        
        System.out.println("✅ Impact analysis (upstream) test passed: " + 
                          upstream.size() + " dependencies found");
    }
    
    @Test
    void testShortestPath() {
        // 查找 A 到 C 的最短路径
        PathFinder.PathResult result = pathFinder.findShortestPath(
            "CLASS:ServiceA",
            "CLASS:ServiceC",
            Collections.singleton(RelationType.CALLS),
            5
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertNotNull(result.getPath());
        
        // 路径应该是 A -> B -> C
        PathFinder.Path path = result.getPath();
        assertEquals(3, path.getEntities().size());
        assertEquals("ServiceA", path.getEntities().get(0).getName());
        assertEquals("ServiceB", path.getEntities().get(1).getName());
        assertEquals("ServiceC", path.getEntities().get(2).getName());
        
        System.out.println("✅ Shortest path test passed: " + path.getPathString());
    }
    
    @Test
    void testFindAllPaths() {
        // 查找 A 到 D 的所有路径
        PathFinder.MultiPathResult result = pathFinder.findAllPaths(
            "CLASS:ServiceA",
            "CLASS:ServiceD",
            Collections.singleton(RelationType.CALLS),
            3,
            10
        ).block();
        
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertNotNull(result.getPaths());
        
        // 应该找到两条路径: A->D 和 A->B->D
        List<PathFinder.Path> paths = result.getPaths();
        assertEquals(2, paths.size());
        
        System.out.println("✅ Find all paths test passed: " + paths.size() + " paths found");
        paths.forEach(p -> System.out.println("   Path: " + p.getPathString()));
    }
    
    @Test
    void testGetNeighbors() {
        // 获取 ServiceA 的所有出边邻居
        List<CodeEntity> neighbors = navigator.getNeighbors(
            "CLASS:ServiceA",
            GraphNavigator.Direction.OUTGOING,
            null
        ).block();
        
        assertNotNull(neighbors);
        assertEquals(2, neighbors.size()); // B 和 D
        
        Set<String> neighborNames = new HashSet<>();
        neighbors.forEach(n -> neighborNames.add(n.getName()));
        assertTrue(neighborNames.contains("ServiceB"));
        assertTrue(neighborNames.contains("ServiceD"));
        
        System.out.println("✅ Get neighbors test passed: " + neighbors.size() + " neighbors found");
    }
    
    @Test
    void testVisualizationMermaid() {
        // 导出为 Mermaid 格式
        String mermaid = visualizer.exportToMermaid(
            Arrays.asList("CLASS:ServiceA", "CLASS:ServiceB", "CLASS:ServiceC"),
            Collections.singleton(RelationType.CALLS),
            10
        ).block();
        
        assertNotNull(mermaid);
        assertTrue(mermaid.contains("```mermaid"));
        assertTrue(mermaid.contains("graph TD"));
        assertTrue(mermaid.contains("ServiceA"));
        assertTrue(mermaid.contains("ServiceB"));
        
        System.out.println("✅ Mermaid visualization test passed");
        System.out.println("Mermaid output:\n" + mermaid);
    }
    
    @Test
    void testCallChainFinding() {
        // 查找调用链
        List<GraphNavigator.CallChain> chains = navigator.findCallChains(
            "CLASS:ServiceA",
            "CLASS:ServiceC",
            3
        ).block();
        
        assertNotNull(chains);
        assertFalse(chains.isEmpty());
        
        // 应该找到 A -> B -> C 的调用链
        GraphNavigator.CallChain chain = chains.get(0);
        assertNotNull(chain.getPath());
        assertEquals(3, chain.getPath().size());
        
        System.out.println("✅ Call chain test passed: " + chains.size() + " chains found");
        System.out.println("   Chain: " + chain.getPathString());
    }
}
