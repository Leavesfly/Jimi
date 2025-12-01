package io.leavesfly.jimi.graph;

import io.leavesfly.jimi.graph.builder.GraphBuilder;
import io.leavesfly.jimi.graph.model.*;
import io.leavesfly.jimi.graph.parser.JavaASTParser;
import io.leavesfly.jimi.graph.parser.ParseResult;
import io.leavesfly.jimi.graph.store.CodeGraphStore;
import io.leavesfly.jimi.graph.store.InMemoryCodeGraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代码图系统集成测试
 */
class GraphSystemTest {
    
    private JavaASTParser parser;
    private CodeGraphStore store;
    private GraphBuilder builder;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        parser = new JavaASTParser();
        store = new InMemoryCodeGraphStore();
        builder = new GraphBuilder(parser, store);
    }
    
    @Test
    void testParseSimpleJavaFile() throws Exception {
        // 创建测试 Java 文件
        String javaCode = """
            package io.test;
            
            public class TestClass {
                private String name;
                
                public String getName() {
                    return name;
                }
                
                public void setName(String name) {
                    this.name = name;
                }
            }
            """;
        
        Path testFile = tempDir.resolve("TestClass.java");
        Files.writeString(testFile, javaCode);
        
        // 解析文件
        ParseResult result = parser.parseFile(testFile, tempDir);
        
        // 验证解析成功
        assertTrue(result.getSuccess());
        assertNotNull(result.getEntities());
        assertNotNull(result.getRelations());
        
        // 验证实体数量: 1个文件 + 1个类 + 1个字段 + 2个方法 = 5个实体
        assertEquals(5, result.getEntities().size());
        
        // 验证类实体
        CodeEntity classEntity = result.getEntities().stream()
            .filter(e -> e.getType() == EntityType.CLASS)
            .findFirst()
            .orElse(null);
        assertNotNull(classEntity);
        assertEquals("TestClass", classEntity.getName());
        assertEquals("io.test.TestClass", classEntity.getQualifiedName());
        
        // 验证方法实体
        long methodCount = result.getEntities().stream()
            .filter(e -> e.getType() == EntityType.METHOD)
            .count();
        assertEquals(2, methodCount);
        
        // 验证字段实体
        long fieldCount = result.getEntities().stream()
            .filter(e -> e.getType() == EntityType.FIELD)
            .count();
        assertEquals(1, fieldCount);
        
        // 验证关系 (文件->类, 类->字段, 类->方法x2 = 4个关系)
        assertEquals(4, result.getRelations().size());
        
        System.out.println("✅ Parse test passed: " + result.getStats());
    }
    
    @Test
    void testGraphStoreOperations() throws Exception {
        // 创建测试实体
        CodeEntity entity1 = CodeEntity.builder()
            .id(CodeEntity.generateId(EntityType.CLASS, "com.example.ClassA"))
            .type(EntityType.CLASS)
            .name("ClassA")
            .qualifiedName("com.example.ClassA")
            .filePath("ClassA.java")
            .build();
        
        CodeEntity entity2 = CodeEntity.builder()
            .id(CodeEntity.generateId(EntityType.CLASS, "com.example.ClassB"))
            .type(EntityType.CLASS)
            .name("ClassB")
            .qualifiedName("com.example.ClassB")
            .filePath("ClassB.java")
            .build();
        
        // 添加实体
        store.addEntity(entity1).block();
        store.addEntity(entity2).block();
        
        // 验证获取实体
        CodeEntity retrieved = store.getEntity(entity1.getId()).block();
        assertNotNull(retrieved);
        assertEquals("ClassA", retrieved.getName());
        
        // 创建关系
        CodeRelation relation = CodeRelation.builder()
            .sourceId(entity2.getId())
            .targetId(entity1.getId())
            .type(RelationType.EXTENDS)
            .build();
        
        store.addRelation(relation).block();
        
        // 验证获取关系
        List<CodeRelation> relations = store.getRelationsBySource(entity2.getId()).block();
        assertNotNull(relations);
        assertEquals(1, relations.size());
        assertEquals(RelationType.EXTENDS, relations.get(0).getType());
        
        // 验证邻居查询
        List<CodeEntity> neighbors = store.getNeighbors(entity2.getId(), RelationType.EXTENDS, true).block();
        assertNotNull(neighbors);
        assertEquals(1, neighbors.size());
        assertEquals("ClassA", neighbors.get(0).getName());
        
        // 验证统计
        CodeGraphStore.GraphStats stats = store.getStats().block();
        assertNotNull(stats);
        assertEquals(2, stats.getTotalEntities());
        assertEquals(1, stats.getTotalRelations());
        
        System.out.println("✅ Graph store test passed: " + stats.getTotalEntities() + " entities, " + 
                          stats.getTotalRelations() + " relations");
    }
    
    @Test
    void testGraphBuilder() throws Exception {
        // 创建测试项目结构
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        
        // 创建多个 Java 文件
        String class1Code = """
            package io.test;
            
            public class ServiceA {
                public void methodA() {
                    System.out.println("A");
                }
            }
            """;
        
        String class2Code = """
            package io.test;
            
            public class ServiceB {
                private ServiceA serviceA;
                
                public void methodB() {
                    serviceA.methodA();
                }
            }
            """;
        
        Files.writeString(srcDir.resolve("ServiceA.java"), class1Code);
        Files.writeString(srcDir.resolve("ServiceB.java"), class2Code);
        
        // 构建图
        GraphBuilder.BuildStats buildStats = builder.buildGraph(srcDir).block();
        
        // 验证构建结果
        assertNotNull(buildStats);
        assertEquals(2, buildStats.getTotalFiles());
        assertEquals(2, buildStats.getSuccessFiles());
        assertEquals(0, buildStats.getFailedFiles());
        assertTrue(buildStats.getTotalEntities() > 0);
        
        // 验证图统计
        CodeGraphStore.GraphStats graphStats = store.getStats().block();
        assertNotNull(graphStats);
        assertTrue(graphStats.getTotalEntities() >= 2); // 至少有2个类
        
        System.out.println("✅ Graph builder test passed: " + buildStats);
        System.out.println("   Graph stats: " + graphStats.getTotalEntities() + " entities, " + 
                          graphStats.getTotalRelations() + " relations");
    }
    
    @Test
    void testPathFinding() throws Exception {
        // 创建测试图: A -> B -> C
        CodeEntity entityA = CodeEntity.builder()
            .id("CLASS:A")
            .type(EntityType.CLASS)
            .name("A")
            .qualifiedName("A")
            .filePath("A.java")
            .build();
        
        CodeEntity entityB = CodeEntity.builder()
            .id("CLASS:B")
            .type(EntityType.CLASS)
            .name("B")
            .qualifiedName("B")
            .filePath("B.java")
            .build();
        
        CodeEntity entityC = CodeEntity.builder()
            .id("CLASS:C")
            .type(EntityType.CLASS)
            .name("C")
            .qualifiedName("C")
            .filePath("C.java")
            .build();
        
        store.addEntity(entityA).block();
        store.addEntity(entityB).block();
        store.addEntity(entityC).block();
        
        CodeRelation relAB = CodeRelation.builder()
            .sourceId("CLASS:A")
            .targetId("CLASS:B")
            .type(RelationType.CALLS)
            .build();
        
        CodeRelation relBC = CodeRelation.builder()
            .sourceId("CLASS:B")
            .targetId("CLASS:C")
            .type(RelationType.CALLS)
            .build();
        
        store.addRelation(relAB).block();
        store.addRelation(relBC).block();
        
        // 查找路径 A -> C
        List<CodeEntity> path = store.findPath("CLASS:A", "CLASS:C", 5).block();
        
        assertNotNull(path);
        assertEquals(3, path.size());
        assertEquals("A", path.get(0).getName());
        assertEquals("B", path.get(1).getName());
        assertEquals("C", path.get(2).getName());
        
        System.out.println("✅ Path finding test passed: " + 
                          path.stream().map(CodeEntity::getName).toList());
    }
    
    @Test
    void testBFSSearch() throws Exception {
        // 创建测试图
        for (int i = 1; i <= 5; i++) {
            CodeEntity entity = CodeEntity.builder()
                .id("CLASS:" + i)
                .type(EntityType.CLASS)
                .name("Class" + i)
                .qualifiedName("Class" + i)
                .filePath("Class" + i + ".java")
                .build();
            store.addEntity(entity).block();
        }
        
        // 创建关系: 1 -> 2, 1 -> 3, 2 -> 4, 3 -> 5
        store.addRelation(CodeRelation.builder()
            .sourceId("CLASS:1")
            .targetId("CLASS:2")
            .type(RelationType.CALLS)
            .build()).block();
        
        store.addRelation(CodeRelation.builder()
            .sourceId("CLASS:1")
            .targetId("CLASS:3")
            .type(RelationType.CALLS)
            .build()).block();
        
        store.addRelation(CodeRelation.builder()
            .sourceId("CLASS:2")
            .targetId("CLASS:4")
            .type(RelationType.CALLS)
            .build()).block();
        
        store.addRelation(CodeRelation.builder()
            .sourceId("CLASS:3")
            .targetId("CLASS:5")
            .type(RelationType.CALLS)
            .build()).block();
        
        // BFS 搜索 (深度2)
        List<CodeEntity> result = store.bfs("CLASS:1", e -> true, 2).block();
        
        assertNotNull(result);
        assertEquals(5, result.size()); // 应该找到所有5个节点
        
        System.out.println("✅ BFS test passed: found " + result.size() + " entities");
    }
}
