# 代码图功能使用指南

## 简介

Jimi 的代码图功能基于 LocAgent 论文实现,提供了强大的代码理解和导航能力。通过构建代码的抽象语法树(AST)图,可以实现:

- **精准代码定位**: 基于图结构和向量检索的混合搜索
- **影响分析**: 分析代码修改的上游/下游影响
- **调用图查询**: 查看方法调用关系和依赖链
- **可视化**: 导出 Mermaid 图表

## 配置

### 1. application.yml 配置

在 `src/main/resources/application.yml` 中配置:

```yaml
jimi:
  graph:
    # 是否启用代码图功能
    enabled: true
    
    # 是否自动构建代码图(文件变化时)
    auto-build: false
    
    # 启动时是否构建代码图
    build-on-startup: false
    
    # 包含文件模式
    include-patterns:
      - "**/*.java"
    
    # 排除文件模式
    exclude-patterns:
      - "**/test/**"
      - "**/tests/**"
      - "**/target/**"
      - "**/build/**"
      - "**/node_modules/**"
      - "**/.git/**"
    
    # 缓存配置
    cache:
      enabled: true
      ttl: 3600
      max-size: 10000
    
    # 搜索配置
    search:
      max-results: 50
      enable-hybrid: true
      graph-weight: 0.6
      vector-weight: 0.4
      min-similarity: 0.3
```

### 2. config.json 配置(可选)

在 `~/.jimi/config.json` 中也可以添加:

```json
{
  "graph": {
    "enabled": true,
    "auto_build": false,
    "build_on_startup": false,
    "include_patterns": ["**/*.java"],
    "exclude_patterns": [
      "**/test/**",
      "**/target/**"
    ],
    "cache": {
      "enabled": true,
      "ttl": 3600
    },
    "search": {
      "max_results": 50,
      "enable_hybrid": true
    }
  }
}
```

## 命令行使用

### /graph 命令

```bash
# 查看帮助
/graph

# 构建代码图(默认当前目录)
/graph build

# 构建指定项目的代码图
/graph build /path/to/project

# 查看图统计信息
/graph stats

# 查看图状态
/graph status

# 重新构建代码图
/graph rebuild

# 清空代码图
/graph clear
```

### 使用示例

```bash
# 1. 构建代码图
jimi> /graph build
开始构建代码图...
项目路径: /Users/yefei.yf/QoderCLI/Jimi

✅ 代码图构建完成

统计信息:
  实体数: 1523
  关系数: 3847
  耗时: 2345ms

# 2. 查看统计
jimi> /graph stats
代码图统计:
  实体数: 1523
  关系数: 3847
  初始化状态: 已初始化
  项目路径: /Users/yefei.yf/QoderCLI/Jimi

# 3. 使用 Agent 工具查询
jimi> 查找 GraphManager 类的调用关系
```

## Agent 工具使用

构建代码图后,Agent 会自动获得以下工具:

### 1. CodeLocateTool - 代码定位

智能定位代码位置,支持 4 种检索模式:

- **SMART**: 智能模式,自动选择最佳策略
- **HYBRID**: 混合模式,融合图检索和向量检索
- **GRAPH_ONLY**: 仅使用图检索
- **VECTOR_ONLY**: 仅使用向量检索

```java
// Agent 自动调用示例
用户: "找到 GraphManager 类的定义"
Agent: [调用 CodeLocateTool]
  - mode: SMART
  - query: "GraphManager class definition"
  - topK: 5
```

### 2. ImpactAnalysisTool - 影响分析

分析代码修改的影响范围:

```java
// 分析类型:
// - DOWNSTREAM: 下游影响(谁依赖我)
// - UPSTREAM: 上游依赖(我依赖谁)
// - BOTH: 双向分析

用户: "分析修改 GraphBuilder 的影响"
Agent: [调用 ImpactAnalysisTool]
  - entityId: "io.leavesfly.jimi.graph.builder.GraphBuilder"
  - analysisType: DOWNSTREAM
  - maxDepth: 3
```

### 3. CallGraphTool - 调用图查询

查询方法调用关系:

```java
// 查询类型:
// - callers: 谁调用了这个方法
// - callees: 这个方法调用了谁
// - callchain: 调用链
// - visualize: 可视化调用图

用户: "查看 buildGraph 方法的调用者"
Agent: [调用 CallGraphTool]
  - queryType: callers
  - entityId: "GraphBuilder.buildGraph"
  - maxDepth: 2
```

## API 使用

### 1. 使用 GraphManager

```java
@Autowired
private GraphManager graphManager;

// 构建代码图
Path projectRoot = Paths.get("/path/to/project");
GraphManager.BuildResult result = graphManager.buildGraph(projectRoot).block();

System.out.println("实体数: " + result.getEntityCount());
System.out.println("关系数: " + result.getRelationCount());

// 获取统计信息
GraphManager.GraphStats stats = graphManager.getGraphStats().block();
System.out.println(stats);
```

### 2. 使用 GraphSearchEngine

```java
@Autowired
private GraphSearchEngine searchEngine;

// 符号搜索
GraphSearchEngine.GraphSearchResult result = searchEngine
    .searchBySymbol("GraphManager", Set.of(EntityType.CLASS), 10)
    .block();

// 关系查询
result = searchEngine
    .searchByRelation(entityId, 
        Set.of(RelationType.CALLS), 
        GraphSearchEngine.Direction.OUTGOING, 10)
    .block();
```

### 3. 使用 HybridSearchEngine

```java
@Autowired
private HybridSearchEngine hybridSearchEngine;

// 智能搜索
HybridSearchEngine.HybridSearchResult result = hybridSearchEngine
    .smartSearch("GraphManager initialization", 10)
    .block();

// 自定义混合搜索
HybridSearchEngine.HybridSearchConfig config = HybridSearchEngine.HybridSearchConfig.builder()
    .graphWeight(0.7)
    .vectorWeight(0.3)
    .fusionStrategy(HybridSearchEngine.FusionStrategy.RRF)
    .build();

result = hybridSearchEngine.search("code graph build", config).block();
```

## 性能优化

### 1. 缓存配置

```yaml
jimi:
  graph:
    cache:
      enabled: true      # 启用缓存
      ttl: 3600         # 缓存1小时
      max-size: 10000   # 最大缓存10000条
```

### 2. 包含/排除模式

合理配置包含和排除模式,避免解析不必要的文件:

```yaml
jimi:
  graph:
    include-patterns:
      - "**/*.java"
    exclude-patterns:
      - "**/test/**"      # 排除测试
      - "**/target/**"    # 排除编译输出
      - "**/build/**"     # 排除构建目录
```

### 3. 延迟构建

建议关闭启动时构建,在需要时手动触发:

```yaml
jimi:
  graph:
    build-on-startup: false  # 关闭启动时构建
    auto-build: false        # 关闭自动构建
```

## 故障排除

### 1. 代码图未启用

```bash
jimi> /graph build
代码图功能已禁用
请在配置文件中启用: jimi.graph.enabled=true
```

**解决方法**: 在 `application.yml` 中设置 `jimi.graph.enabled: true`

### 2. 构建失败

```bash
jimi> /graph build /path/to/project
构建失败: 路径不存在
```

**解决方法**: 
- 检查项目路径是否正确
- 确保路径存在且可读
- 检查是否有 Java 文件

### 3. 内存不足

对于大型项目,可能需要增加 JVM 内存:

```bash
export JAVA_OPTS="-Xmx4g -Xms2g"
./scripts/start.sh
```

## 最佳实践

### 1. 首次使用

1. 启用代码图功能
2. 使用 `/graph build` 构建代码图
3. 使用 `/graph stats` 查看统计
4. 开始使用 Agent 工具

### 2. 日常使用

- 代码修改后使用 `/graph rebuild` 重新构建
- 定期使用 `/graph stats` 查看图状态
- 使用自然语言向 Agent 提问,让 Agent 自动调用工具

### 3. 大型项目

- 合理配置排除模式,减少不必要的文件
- 增加缓存大小: `jimi.graph.cache.max-size: 50000`
- 调整搜索结果数: `jimi.graph.search.max-results: 100`

## 架构说明

### 核心组件

```
GraphManager (管理器)
├── GraphBuilder (图构建器)
│   ├── JavaASTParser (AST解析)
│   └── CodeGraphStore (图存储)
├── GraphNavigator (图导航)
├── ImpactAnalyzer (影响分析)
├── GraphSearchEngine (图检索)
└── GraphVisualizer (可视化)
```

### 数据模型

- **CodeEntity**: 代码实体(类、方法、字段等)
- **CodeRelation**: 代码关系(调用、继承、引用等)
- **EntityType**: 实体类型(9种)
- **RelationType**: 关系类型(10种)

### 检索流程

```
用户查询
    ↓
智能分析(符号/路径/自然语言)
    ↓
┌─────────┬─────────┐
│  图检索  │ 向量检索 │
└─────────┴─────────┘
    ↓         ↓
    └─── 融合 ───┘
         ↓
    结果排序与过滤
         ↓
      返回结果
```

## 参考资料

- LocAgent 论文: https://github.com/gersteinlab/LocAgent
- JavaParser 文档: https://javaparser.org/
- Mermaid 图表: https://mermaid.js.org/
