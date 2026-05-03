# 08 · 代码图谱与 RAG 检索

> **模块路径**：`io.leavesfly.jimi.knowledge`
> **配置类**：`io.leavesfly.jimi.config.info.GraphConfig` / `VectorIndexConfig`
> **外部文档**：[`docs/GRAPH_GUIDE.md`](../docs/GRAPH_GUIDE.md)、[`docs/RAG配置指南.md`](../docs/RAG配置指南.md)
> **相关文档**：[02 · 系统架构](02-系统架构与核心引擎.md)、[04 · 工具系统](04-工具系统与ToolRegistry.md)、[09 · 自定义命令](09-自定义命令与CLI交互.md)

---

## 1. 总览

Jimi 把"让 Agent 看懂仓库"拆成两条互补的管线：

- **代码图谱（Graph）**——对源码做 AST 解析，抽取**实体**（类/方法/字段…）与**关系**（继承/调用/包含…），存成一张有向图，支持结构化查询：谁调谁、谁实现谁、修改某方法影响哪些下游。
- **向量检索（RAG）**——对源码做**按行分块**，经 Embedding 向量化后存入向量库，按余弦相似度召回**语义相似**的代码片段，适合"给我讲解这段逻辑""找和 `XXX` 类似的实现"这类自然语言问题。

两者在 `HybridSearch` 层融合，对外暴露统一的"混合检索"能力。整体架构：

```text
                      ┌────────────────────────┐
                      │     HybridSearch       │  ← RRF / WeightedAvg / Union
                      │  (knowledge 顶层 Facade)│
                      └─────┬───────────┬──────┘
                            │           │
                 并行        │           │          并行
           ┌────────────────┘           └────────────────┐
           ▼                                             ▼
   ┌───────────────┐                             ┌───────────────┐
   │ GraphManager  │                             │  RagManager   │
   └──────┬────────┘                             └──────┬────────┘
          │ 组合                                         │ 组合
 ┌────────┼────────┬──────────┬──────────┐    ┌─────────┼──────────┐
 ▼        ▼        ▼          ▼          ▼    ▼         ▼          ▼
GraphBuilder  Navigator  ImpactAnalyzer  Visualizer  Chunker  EmbeddingProvider  VectorStore
Language-           │                                 │            │                 │
ParserRegistry      │                                 │            │                 │
   │                ▼                                 │            │                 │
 JavaASTParser  InMemoryCodeGraphStore              SimpleChunker  Mock/Qwen    InMemoryVectorStore
```

**实事求是的几条重要约束**（后文详述，先标在这里避免误解）：

1. **仅 Java 有真实解析器**——`parser/` 目录下只有 `JavaASTParser`，其它扩展名在 `LanguageParserRegistry.canParse(path)` 里直接返回 `false`，不会被构建进图。
2. **存储为纯内存**——`GraphManager` 内部 `new InMemoryCodeGraphStore()`，`RagManager` 注入的是 `InMemoryVectorStore`；没有 Neo4j、没有 FAISS、没有外部向量库。持久化靠自实现的 `save()/load()` 写到 `.jimi/` 下的文件。
3. **`HybridQuery.FusionStrategy` 定义了 6 个枚举，`HybridSearch.fuseResults` 的 `switch` 只真正实现了 3 个**（`RRF` / `WEIGHTED_AVERAGE` / `UNION`）；`CASCADE_*`、`INTERSECTION` 三个会落到 `default` 分支按 `UNION` 处理。
4. **`GraphBuilder.matchesExcludePatterns` 在 glob 之外叠加了硬编码目录黑名单**——源码里**每个 exclude matcher 的匹配结果都会 `||` 上** `pathStr.contains("/target/") || "/build/" || "/.git/" || "/node_modules/" || "/test/" || "/tests/"` 这 6 个 `contains` 判断。这意味着：(a) 如果 `excludeMatchers` 为空（用户把 `graph.exclude_patterns` 设成空集合），整个 `matchesExcludePatterns` 直接 `return false`，硬编码**不会生效**；(b) 只要 `excludeMatchers` 非空（默认就有 6 条 glob，见 §5.1），**任意一个 matcher 迭代都会被这 6 个 `contains` 命中就认为是排除的**——换句话说，只要你没把 exclude 列表清空，这 6 个目录片段就被无条件排除，**想对 `test/` 建图当前做不到**（除非把 exclude-patterns 设为空集合，同时放弃所有 glob 排除）。

---

## 2. 代码图谱（Graph）

### 2.1 数据模型

图的两个顶层类型在 `knowledge.graph.model`：

| 类 | 说明 |
|------|------|
| `CodeEntity` | 图中的**节点**。`id = type.name() + ":" + qualifiedName`（`CodeEntity.generateId(EntityType, String)` 静态方法）|
| `CodeRelation` | 图中的**有向边**。字段：`id`（`@Builder.Default` 取 `UUID.randomUUID().toString()`）、`sourceId`、`targetId`、`type: RelationType`、`weight`（默认 `1.0`）、`properties: Map<String,Object>`、`createdAt` |
| `EntityType` 枚举 | `PACKAGE / FILE / CLASS / INTERFACE / ENUM / METHOD / CONSTRUCTOR / FIELD / ANNOTATION`（共 **9** 类）|
| `RelationType` 枚举 | `CONTAINS / IMPORTS / EXTENDS / IMPLEMENTS / CALLS / REFERENCES / OVERRIDES / USES_TYPE / ANNOTATED_WITH / THROWS`（共 **10** 类）|

`CodeEntity` 的关键字段：`id`、`type`、`name`（简单名）、`qualifiedName`（全限定名）、`filePath`（项目根相对路径）、`startLine`/`endLine`（代码定位，`Integer` 可为 null）、`visibility`、`isStatic`/`isAbstract`（`Boolean` 默认 `false`）、`attributes: Map<String,Object>`（扩展属性，例如方法的 `signature`/`returnType`，字段的 `fieldType`）、`createdAt`。

### 2.2 构建流程——`GraphBuilder.buildGraph`

```text
scanSourceFiles(projectRoot)
 ├── Files.walk
 ├── .filter(parserRegistry::canParse)         ← 只有有解析器的扩展名才通过（目前= .java）
 ├── .filter(matchesIncludePatterns)           ← include-patterns glob 匹配
 └── .filter(!matchesExcludePatterns)          ← exclude-patterns glob + 硬编码目录黑名单
        │
        ▼
Flux.fromIterable(sourceFiles)
 └── .flatMap(file -> parseAndStore(file, projectRoot))
        │
        ▼
parser.parseFile(filePath, projectRoot)   // JavaASTParser
 └── graphStore.addEntities(result.entities).then(addRelations(result.relations))
        │
        ▼
.reduce(new BuildStats(), this::mergeStats)   // GraphBuilder.mergeStats 实例方法
```

返回的 `BuildStats`（`GraphBuilder` 的静态内部类）记录：`totalFiles / successFiles / failedFiles / totalEntities / totalRelations`，合并由 `GraphBuilder.mergeStats(a,b)` 完成（把 b 的 5 个计数累加到 a 上）。

### 2.3 Java 解析器——`JavaASTParser`

基于 [`com.github.javaparser:javaparser-core`](https://github.com/javaparser/javaparser) 实现（这是**唯一**的语言解析器）。它会为每个 `.java` 文件产出的实体：

- **FILE 实体**：`qualifiedName = packageName.isEmpty() ? filePath : packageName + "." + filePath`（默认包时直接退化为文件相对路径）
- **CLASS / INTERFACE / ENUM / ANNOTATION 实体**：`qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName`
- **METHOD / CONSTRUCTOR 实体**：`qualifiedName = classQualifiedName + "." + methodName + "(" + paramCount + ")"`
- **FIELD 实体**：`qualifiedName = classQualifiedName + "." + fieldName`

⚠️ **签名规则的重要现状**：方法/构造器的 `signature` 用的是**参数个数**（`method.getParameters().size()`），而非完整类型签名。也就是说，`foo(String)` 和 `foo(int)` 在图里会被视作同一个 `METHOD:ClassName.foo(1)`——这是**重载方法会撞 ID** 的已知简化。类型声明为非抽象接口时 `isAbstract` 字段会按 `ClassOrInterfaceDeclaration.isAbstract()` 结果设置（`JavaASTParser.parseTypeDeclaration` 仅对 class/interface 分支调用该 API，枚举/注解的 `isAbstract` 保持 `@Builder.Default` 的 `false`）。

抽取的关系（`JavaASTParser.parseTypeDeclaration`/`parseMember`/`parseInheritance`/`parseMethodCalls`）：

| 触发点 | RelationType | 备注 |
|--------|-------------|------|
| 文件 → 类/接口/枚举/注解 | `CONTAINS` | 每个顶层类型 |
| 类 → 方法/构造器/字段 | `CONTAINS` | 每个成员，见下"`parseMember` 限制" |
| `class A extends B` | `EXTENDS` | 按 `B` 的**简单名**生成 `targetId = CLASS:B`，**不解析全限定名**。**仅当 `typeDecl.isClassOrInterfaceDeclaration()` 成立时才调用 `parseInheritance`**——枚举（`enum implements Interface`）与注解类型的继承/实现关系**不会**被抽取为边 |
| `class A implements I` | `IMPLEMENTS` | 按接口**简单名**生成 `targetId = INTERFACE:I`，限制同上 |
| 方法体内的 `MethodCallExpr` | `CALLS` | 见下 §2.4；**仅 `MethodDeclaration` 内部的调用表达式被扫描**，`ConstructorDeclaration` 的方法体内部调用不会进入 `parseMethodCalls`（`parseConstructor` 源码里没有这一步）|

**`parseMember` 的成员类型限制**：`parseMember` 里只有 `if (member.isMethodDeclaration()) ... else if (member.isConstructorDeclaration()) ... else if (member.isFieldDeclaration()) ...` 三个分支——**内部类/内部接口（作为成员的 `ClassOrInterfaceDeclaration`）、注解成员（`AnnotationMemberDeclaration`）、枚举常量（`EnumConstantDeclaration`）、`static`/`instance` 初始化块（`InitializerDeclaration`）均被直接忽略**。这意味着**内部类不会产生独立 CLASS 实体**，对它们的调用/继承分析会缺失。

另外 `parseMethodCalls` 每识别到一条 `MethodCallExpr`，都会在**调用方**（`methodEntity`）上额外写一个 `attributes["calls_<methodName>"] = true` 的标记属性，无论最终是否产生 `CALLS` 边——所以即使目标无法解析，方法实体的 `attributes` 里也能看出它"调过什么名字的方法"。

### 2.4 `CALLS` 关系的解析限制

`parseMethodCalls` → `resolveMethodCallTarget` 的启发式只覆盖三种情形：

```text
1. 无 scope 的调用（foo()）           → 假设是"同类方法"，target = currentClass.foo(N)
2. scope.matches("[A-Z][a-zA-Z0-9]*")  → 假设是"静态调用", target = ClassName.foo(N)
   （严格要求 scope 是"仅字母数字、首字母大写"的简单标识符；
    com.example.Utils 这种带点的完整限定名因正则不匹配，不会走静态分支）
3. scope 是 this（this.foo()）        → 同类方法              target = currentClass.foo(N)
```

其他所有 `object.method()` 形式（以及 scope 为小写开头标识符、链式调用、`super.xxx()`、`a.b.C.foo()` 等带点限定 scope）在源码里**直接走 if/else 末尾 `return null`**，不会产生 `CALLS` 边。`JavaASTParser.resolveMethodCallTarget` 尾部注释原文：

> `// 其他情况（如 object.method()）需要符号解析器支持`
> `// 这里返回 null，暂不处理`

所以真实项目里调用图的**召回率较低**，尤其是通过注入的依赖（`service.doSomething()`）——这些调用不会被图记录。做影响分析时要注意这个盲区。

另一个潜在问题：由于 `EXTENDS`/`IMPLEMENTS` 用的是类型的**简单名**而不是全限定名，跨包同名类会产生**假连边**（例如两个不同包都有 `Utils` 类，会连到同一个 `CLASS:Utils` 节点上）。

### 2.5 存储——`InMemoryCodeGraphStore`

`CodeGraphStore` 是接口，当前**唯一**实现是 `InMemoryCodeGraphStore`（路径 `knowledge/graph/store/`）。进程退出即丢失，但 `GraphManager.saveGraph()` 会调用 `graphStore.save()` 把数据序列化到磁盘（由具体 store 实现决定格式）。内部持有 5 个 `ConcurrentHashMap`：

| 字段 | 类型 | 用途 |
|------|------|------|
| `entities` | `Map<entityId, CodeEntity>` | 节点主表 |
| `relations` | `Map<relationId, CodeRelation>` | 边主表（relationId 为 UUID，见 §2.1）|
| `outgoingEdges` | `Map<sourceId, CopyOnWriteArrayList<relationId>>` | 出边邻接表 |
| `incomingEdges` | `Map<targetId, CopyOnWriteArrayList<relationId>>` | 入边邻接表 |
| `fileIndex` | `Map<filePath, CopyOnWriteArrayList<entityId>>` | 文件 → 实体倒排索引，支撑 `deleteEntitiesByFile`/`getEntitiesByFile` |

所有公开 API 都返回 `Mono<...>`（通过 `Mono.fromRunnable`/`Mono.fromCallable` 把同步操作包装成反应式接口）；另外提供了 `getEntitySync`/`getRelationsBySourceSync`/`getRelationsByTargetSync` 等同步方法，`GraphNavigator` 的递归/BFS 实现里都走这些同步方法以避免在反应式链里 `block()`。

路径计算逻辑（`GraphManager.resolveStoragePath`）：

```text
baseDir = workDir != null ? workDir : System.getProperty("user.dir")
return baseDir.resolve(config.getStoragePath())   // 默认 ".jimi/code_graph"
```

⚠️ **`workDir` 的注入时机**：`GraphManager.setWorkDir(Path)` **不在 Spring 启动阶段被调用**。`file_grep "setWorkDir"` 全仓库排查后，`graphManager.setWorkDir` 的真实外部调用方只有一个：`GraphCommandHandler.execute()`（`command/handlers/GraphCommandHandler.java`）在处理 `/graph` 子命令时调用。另有一个 `GraphManager.initialize(JimiRuntime)` 方法内部会调 `setWorkDir`，但 `file_grep "graphManager.initialize"` / `graphManager\s*\.\s*initialize` 在全仓库内**0 条匹配**——该方法在当前代码库内没有任何调用方（与 Hooks 的 `setProjectDirectory` 情况类似，见 [07](07-Hooks自动化系统.md) §11.2）。

后果：

- 如果用户从未执行过 `/graph xxx` 而直接触发 `HybridSearch` 或 Agent 工具调用 Graph，`workDir` 会保持 `null`；等到真正落盘/加载时走 `resolveStoragePath` 的 `workDir != null ? workDir : System.getProperty("user.dir")` 兜底，路径**取决于 JVM 启动目录**，和 Jimi 的 `workDir` 可能不一致
- `auto-load` 分支也藏在 `setWorkDir` 里——没走过 `/graph` 命令，**自动加载不会真正触发**（除非外部显式调 `ensureWorkDirInitialized()`，该方法会以 `System.getProperty("user.dir")` 作为 `workDir` 兜底触发一次加载）

### 2.6 自动加载与自动保存

配置 `graph.auto_load=true`（`GraphConfig.autoLoad` 默认 true）时，`setWorkDir()` 内部会尝试从 `resolveStoragePath()` 读取已保存的图，读取成功则把 `initialized` 置 true，无需每次重新解析。真正触发需要 `setWorkDir` 先被调用一次，见 §2.5 ⚠️。

配置 `graph.auto_save=true`（`GraphConfig.autoSave` 默认 true）时，`buildGraph` 完成后会**自动**调用 `graphStore.save()` 落盘到 `resolveStoragePath()`。

配置键路径：`GraphConfig` 通过 `JimiConfig.graph` 挂载，配置文件里的 key 是 `graph.auto_load` / `graph.auto_save`（下划线风格，`@JsonProperty` 对应）；不是 `jimi.graph.*` 前缀。

### 2.7 图查询——两条并存、未打通的路径

这一块是源码里最容易被误解的地方，需要分开讲：

#### 2.7.1 `GraphQuery.QueryType` 枚举（设计层）

`query/GraphQuery.java` 定义了 **7 种**查询类型：

| QueryType | 语义（按字段注释） |
|-----------|------|
| `SEARCH_BY_SYMBOL` | 按符号名搜索 |
| `FIND_DEPENDENCIES` | "我依赖谁"（出边）|
| `FIND_DEPENDENTS` | "谁依赖我"（入边）|
| `IMPACT_ANALYSIS` | 影响范围分析 |
| `CALL_CHAIN` | 调用链分析 |
| `INHERITANCE_TREE` | 继承层次分析 |
| `GET_ENTITY_DETAIL` | 获取实体详情 |

⚠️ **重要事实**：`GraphSearchEngine` 的对外方法**完全不根据 `QueryType` 分派**——它只提供 `searchBySymbol` / `searchByRelation` / `searchByFile` / `searchByContext` 四个固定入口；`GraphQuery.QueryType` 的这 7 个枚举值**在 `file_grep` 检索全仓库后没有任何 `switch (queryType)` 或对应分派代码**。也就是说，`QueryType` 枚举当前**更像一份"能力路线图"**，真正在跑的是 `GraphSearchEngine` / `GraphNavigator` / `ImpactAnalyzer` 这三个类上面各自暴露的硬编码方法。

#### 2.7.2 `GraphSearchEngine`（实现层）

真正对外的搜索能力：

| 方法 | 行为 |
|------|------|
| `searchBySymbol(name, entityTypes, limit)` | 遍历 `entityTypes` 覆盖的所有实体，按 `scoreEntities` 打分（精确=1.0 / 前缀=0.8 / 包含=0.6 / 限定名含=0.4 / 驼峰=0.5），降序取 topK |
| `searchByRelation(entityId, relationTypes, direction, limit)` | 委托 `GraphNavigator.getNeighbors` |
| `searchByFile(filePath, limit)` | 按 `filePath.contains(query)` 匹配，分数按路径长度比例计算 |
| `searchByContext(ContextQuery)` | 聚合 1+2（**无条件**：按 `symbols` 每项各自调 `searchBySymbol(..., limit=50)`、按 `filePaths` 每项各自调 `searchByFile(..., limit=50)`）；仅当 `contextQuery.isIncludeRelated()=true` 时，再对已有结果的每一项沿相关边调 `searchByRelation(..., BOTH, 10)` 扩展，**相关实体分数打 0.5 折**；最后按分数降序取 `contextQuery.getLimit()` |

#### 2.7.3 `GraphNavigator`（导航层）

| 方法 | 行为 |
|------|------|
| `getNeighbors(entityId, Direction, relationTypes?)` | 取单跳邻居，`Direction=OUTGOING/INCOMING/BOTH`；`BOTH` 通过 `Mono.zip` 并两个方向然后 `HashSet` 去重 |
| `multiHopNavigation(start, relationTypes, maxHops, entityFilter)` | BFS 多跳，结果按跳数分组到 `NavigationResult.entitiesByHop` 中；`entityFilter` 对每个遍历到的节点做过滤，不过滤则不计入 |
| `findCallChains(from, to, maxDepth)` | DFS 沿 `CALLS` 出边找**所有** from→to 路径；到达目标时把当前 `path` 快照为一条 `CallChain` |
| `getInheritanceHierarchy(classId, Direction)` | `Direction.OUTGOING/BOTH` 时沿 `EXTENDS` 出边递归收集**父类链**；`Direction.INCOMING/BOTH` 时沿 `EXTENDS` 入边递归收集**子类链**；**不管传入哪个 Direction，都会额外沿 `IMPLEMENTS` 出边递归收集一次已实现接口**（这段是无条件执行，不受 direction 控制）|
| `findCallers(methodId, maxDepth)` | 调用 `graphStore.bfs(methodId, entityFilter=METHOD or CONSTRUCTOR, maxDepth)`，结果中剔除 `methodId` 自身 |
| `findCallees(methodId, maxDepth)` | 调用 `graphStore.getNeighbors(methodId, RelationType.CALLS, outgoing=true)`（**只取一跳**，忽略传入的 `maxDepth` 参数），再过滤类型为 `METHOD`/`CONSTRUCTOR` 的邻居 |

这些方法被 LLM 工具（如 `CallGraphTool`，见 §7）和 `GraphManager` 的转发方法（如 `graphManager.findCallers/findCallees/findCallChains/exportCallGraphToMermaid`）使用。

⚠️ **`findCallees` 的 `maxDepth` 参数是"摆设"**：方法签名上有 `maxDepth`，但实现里没有任何递归/BFS，只走了一跳。想要多跳被调用关系，得自己拼 `multiHopNavigation(id, Set.of(CALLS), maxDepth, ...)`。

#### 2.7.4 `ImpactAnalyzer`（影响分析层）

独立类（不由 `QueryType.IMPACT_ANALYSIS` 驱动），对外三个入口：

| 方法 | 行为 |
|------|------|
| `analyzeImpact(entityId, AnalysisType, maxDepth)` | `AnalysisType=DOWNSTREAM/UPSTREAM/BOTH`，递归 BFS 沿入/出边收集实体和关系 |
| `analyzeFileImpact(filePath, maxDepth)` | 对文件内所有实体做 `DOWNSTREAM` 分析并聚合 |
| `analyzeMethodCallImpact(methodId, maxDepth)` | 专门分析方法级调用影响（直接/间接调用者 + 直接被调用者）|

### 2.8 可视化——`GraphVisualizer`

`visualization/GraphVisualizer` 提供三个 Mermaid 导出方法，**输出格式不同**：

| 方法 | 输出头 | 语义 |
|------|--------|------|
| `exportToMermaid(entityIds, relationTypes, maxNodes)` | ```` ```mermaid\ngraph TD ```` | 通用节点/边图 |
| `exportClassHierarchyToMermaid(classId, includeInterfaces)` | ```` ```mermaid\nclassDiagram ```` | 类图（`<|--`/`<|..`）|
| `exportCallGraphToMermaid(methodId, depth)` | ```` ```mermaid\ngraph LR ```` | 调用图（从左到右 + 中心节点粉色高亮 `style xxx fill:#f9f`）|

`GraphManager.exportMermaid(startEntityId, maxDepth)` 只委托**第三个**——即 `exportCallGraphToMermaid`，产出的是 `graph LR` 不是 `graph TD`。若想要通用图或类图，得直接从 `graphManager.getVisualizer()` 取 `GraphVisualizer` 手动调。

节点形状（`getNodeShape`）：`CLASS=[]` / `INTERFACE=()` / `METHOD,CONSTRUCTOR=(())` / `FIELD=[[]]` / `ENUM={}`。
边样式（`getEdgeStyle`）：`EXTENDS=--|>` / `IMPLEMENTS=..|>` / `CALLS=-->` / `REFERENCES=-.->`。

---

## 3. 向量检索（RAG）

### 3.1 核心类关系

```text
RagManager
 ├── VectorStore           （接口；默认实现 InMemoryVectorStore）
 ├── EmbeddingProvider     （接口；实现 MockEmbeddingProvider / QwenEmbeddingProvider）
 ├── Chunker               （接口；实现 SimpleChunker）
 └── VectorIndexConfig     （配置）
```

`RagManager` 是 Facade，统一编排"分块 → 嵌入 → 存储 → 检索"四个阶段。

### 3.2 分块——`SimpleChunker`

策略：**固定行数滑动窗口**。逻辑（`SimpleChunker.chunk`）：

```text
while (startLine < totalLines) {
    endLine = min(startLine + chunkSize, totalLines);
    chunkContent = lines[startLine..endLine-1] 拼接;
    if (trim().isEmpty()) { startLine = endLine; continue; }   // 跳空片段
    chunks.add(new CodeChunk(...));
    startLine = endLine - overlap;                              // 重叠滑动
    if (startLine >= totalLines - overlap) break;
}
```

- `chunkSize` / `overlap` 由 `VectorIndexConfig` 提供（默认 50 / 5）
- 伪代码里的 `startLine`/`endLine` 是**循环内的 0-base 本地变量**；写入 `CodeChunk` 时 `chunk.startLine = startLine + 1`（转成 1-base），`chunk.endLine = endLine`（已经是半开区间的上界，相当于 1-base 的闭区间末行号）
- `CodeChunk` 的实际字段：`id`、`content`、`filePath`、`symbol`（类名/方法名，可为 null）、`startLine`/`endLine`（**字段上行号从 1 开始**）、`language`、`contentHash`（MD5 十六进制小写）、`updatedAt`（毫秒时间戳）、`embedding: float[]`、`metadata: Map<String,String>`
- `CodeChunk.id` 源码注释是"`唯一标识符（通常是 file_path + offset 的哈希）`"——**但 `SimpleChunker.generateChunkId` 并未做哈希**，实际是 `String.format("%s_%d_%d", filePath.replace("/","_").replace("\\","_"), startLineZeroBased, endLineZeroBased)` 的直接字符串拼接。注释与实现存在落差，这里以实现为准
- `SimpleChunker.chunk` **不会**在 `CodeChunk.builder()` 中设 `.embedding(...)`，留给上游（当前由 `RagManager.buildIndex` 直接落盘，未回填，见 §3.7 落差 1）处理；同样 `symbol` 字段在 `SimpleChunker` 中也**不设置**，始终为 null
- `detectLanguage(filePath)` 用后缀名查内置表识别，支持 `.java/.kt/.py/.js/.ts/.go/.rs/.cpp/.c/.h/.hpp` 共 **11 种扩展名**（但只映射到 **10 种语言标签**——`.h` 返回 `"c"` 与 `.c` 一致、`.hpp` 返回 `"cpp"` 与 `.cpp` 一致）；其他后缀返回 `"unknown"`

**注意**：`SimpleChunker` 是**非**语言感知的纯行窗口，不会按函数/类边界切——一个大方法可能被切成多片，反之多个小函数可能挤在一片里。接口上预留了未来实现 AST 级分块的可能，但当前只有这一种实现。

### 3.3 嵌入——`EmbeddingProvider`

接口共 4 个方法：`getDimension() → int` / `embed(text) → Mono<float[]>` / `embedBatch(texts) → Mono<List<float[]>>` / `getProviderName() → String`。

当前两个实现：

#### `MockEmbeddingProvider`

- 用 `text.hashCode()` 做种子 `new Random(seed)`，生成固定维度的高斯分布向量，再 L2 归一化
- **确定性**：同文本总是同向量。但向量本身没有语义——它只能保证"相同文本被召回"，不能保证"相似文本被召回"
- 构造时会 `log.warn("...NOT suitable for production")`
- 用途：本地开发、集成测试、没有外部 Embedding Key 时的占位

#### `QwenEmbeddingProvider`

- 调用**DashScope** HTTP Embedding 接口（`POST /embeddings`），请求体 `{model, input.texts: [...], encoding_format: "float"}`，读 `output.embeddings[i].embedding`
- 批量上限硬编码为 **25 条/次**（DashScope 规格），超过会自动分批 `Mono.zip`
- 通过 `LLMProviderConfig` 注入 `baseUrl` / `apiKey` / `customHeaders`，与 LLM 接入层共用一套 Provider 配置

### 3.4 存储——`InMemoryVectorStore`

默认且当前**唯一**的 `VectorStore` 实现（`VectorStore` 接口共 **10 个方法**：`add(CodeChunk)` / `addBatch(List<CodeChunk>)` / `delete(String id)` / `deleteByFilePath(String)` / `search(float[], int topK)` / `search(float[], int topK, SearchFilter)` / `getStats()` / `clear()` / `save()` / `load(Path)`）。内部结构：

```text
ConcurrentHashMap<String, CodeChunk> chunks        // chunkId → CodeChunk
ConcurrentHashMap<String, String>   fileMD5Cache  // filePath → 文件 MD5
```

- **检索**（`search(queryVector, topK, filter?)`）：**线性扫描** `chunks.values()`，对每个 chunk 的 `embedding` 算**余弦相似度**（`cosineSimilarity` 自实现），应用可选 `SearchFilter`（语言/文件/符号/更新时间），最后按分数降序取 TopK。没有 HNSW/IVF 之类的向量索引
- **过滤**规则（`matchesFilter`）：
  - `language` 做**全等比较**
  - `filePattern` / `symbolPattern` 调用 `String.matches(regex)`——**这两个参数在实现里被当作 Java 正则使用**；但 `VectorStore` 接口里 `SearchFilter.filePattern` 的字段注释写的是 `"文件路径模式（glob）"`、`symbolPattern` 注释写的是 `"符号名称模式（正则）"`——**接口文档与实现对 `filePattern` 的语义不一致**，当前版本以实现为准（两者都是正则）
  - `minUpdatedAt` 做时间戳 ≥ 比较
- **持久化**`save()` 产物（以 `resolveIndexPath()` 解析出的目录为根）：
  - `chunks.jsonl`：每行一个 chunk 的 JSON。字段通过私有静态内部类 `ChunkWrapper` 包装（`ChunkWrapper(CodeChunk)` 拷贝除 `embedding` 外的全部字段，`embedding` 字段标 `@JsonIgnore` 不序列化到 JSON）
  - `vectors.bin`：`DataOutputStream` 紧凑二进制，**每个 chunk 一段**：`idLength:int` / `idBytes` / `dim:int`（`embedding==null` 时写 0）/ `dim` 个 `float`
  - `md5_cache.json`：`objectMapper.writeValue(file, fileMD5Cache)` 直接输出的 Map 序列化结果
- **路径**：`resolveIndexPath()` 优先返回已经组合好的 `indexPath`；若 `indexPath` 未被 `setWorkDir`/`load(indexPath)` 赋值，则按 `(workDir != null ? workDir : user.dir).resolve(configuredIndexPath)` 兜底。`configuredIndexPath` 由 `JimiConfiguration.vectorStore(...)` 在创建 `InMemoryVectorStore` 后主动调用 `setConfiguredIndexPath(VectorIndexConfig.indexPath)` 注入（默认 `.jimi/index`）
- **`getStats()` 返回值的已知简化**：
  - `indexSizeBytes`：**不是真实磁盘大小**，源码硬编码 `chunks.size() * 1024L`（注释"假设每个 chunk 约 1KB"）。`/index stats` 打印的"索引大小"用 `formatBytes` 格式化该估算值，和 `chunks.jsonl`+`vectors.bin` 的真实大小没有对应关系
  - `storageType`：**硬编码字符串** `"in-memory"`，与 `VectorIndexConfig.storage_type` 配置字段完全无关——即使配置里写 `storage_type: file`，`getStats()` 仍返回 `"in-memory"`
  - `totalFiles`：按 `chunks.values().stream().map(CodeChunk::getFilePath).collect(toSet()).size()` 实时计算，能反映真实情况
  - `lastUpdated`：取所有 chunk 的 `updatedAt` 字段中的最大值（`chunks.values().stream().mapToLong(CodeChunk::getUpdatedAt).max().orElse(0L)`）

### 3.5 构建索引——`RagManager.buildIndex`

```text
findSourceFiles(projectRoot, extensions)            // Files.walk + 过滤扩展名 + isExcluded
 └── .flatMap(file -> chunker.chunk(relativePath, content, chunkSize, overlap))
        │
        ▼
.collectList()
 └── vectorStore.addBatch(chunks)                   // ⚠️ 见 §3.7 落差 1
```

`extensions` 取自 `config.fileExtensions`（逗号分隔，默认 `.java,.kt,.py,.js,.ts,.go,.rs`）。

`isExcluded` 逻辑**极简且有缺陷**：

```text
for (String pattern : excludePatterns.split(",")) {
    pattern = pattern.trim();
    if (pattern.startsWith("**")) {          // ← 只处理以 "**" 开头的 pattern
        String suffix = pattern.substring(2);
        if (relativePath.contains(suffix.replace("**", ""))) return true;
    }
    // 其他形式的 pattern：完全忽略，直接跳过
}
```

这意味着：

- `**/target/**` 这类模式会被识别，按 `contains("/target/")` 匹配（`**` 会被 `replace` 掉）
- `target/` 或 `*.bak` 之类**不以 `**` 开头**的模式**会被完全忽略**，起不到任何排除作用
- 不是标准 glob，比 `GraphBuilder` 的 `FileSystems.getPathMatcher("glob:...")` 简陋得多

### 3.6 检索——`RagManager.retrieve`

```text
embeddingProvider.embed(query.getQuery())         // 文本 → 查询向量
 └── vectorStore.search(queryVector, topK, filter?)
        │
        ▼
filter by score >= query.minScore                  // 再次过滤
 └── 转 RetrievalResult.CodeChunkResult
```

过滤条件从 `RetrievalQuery.RetrievalFilter` 映射到 `VectorStore.SearchFilter`，字段一一对应：`language`、`filePattern`、`symbolPattern`、`minUpdatedAt`。

`RetrievalQuery.includeContent=false` 时返回的 `CodeChunkResult.content` 为 `null`，仅带元信息（id/filePath/行号等），适合只需要定位不需要预览的场景。

### 3.7 两个已知的行为落差

1. **`buildIndex` 没有调用 Embedding**——代码里 `chunker.chunk(...)` 产出的 `CodeChunk` 没设 `embedding` 字段，随后直接 `vectorStore.addBatch(chunks)` 落库。等到 `InMemoryVectorStore.search` 遍历时，会走到"`chunk.getEmbedding() == null || length==0` 则跳过"的分支（`InMemoryVectorStore.java` §`search`）——也就是说，**仅靠 `/index build` 构建出来的索引，检索时不会命中任何结果**，必须由调用方（或未来的增量流水线）额外把 `embedding` 回填进去才能用。生产用法请注意这一点。
2. **没有 `update` 的流水线实现**——`RagManager` 只有 `buildIndex`（全量）和 `retrieve`；`InMemoryVectorStore` 虽然有 `fileMD5Cache` 字段和 `fileNeedsUpdate(path, md5)` 判断方法，但**没有任何代码路径**调用它们做增量更新。`/index update` 在 `docs/RAG配置指南.md` 里也标注着"开发中"。想做增量要么等后续实现，要么自己在外面 diff 文件 MD5 再调 `deleteByFilePath` + `add`。

---

## 4. 混合检索——`HybridSearch`

### 4.1 入口

`HybridSearch` 是 `knowledge` 包顶层的 `@Component`，组合 `GraphManager` + `RagManager`，对外提供三个方法：

| 方法 | 策略 |
|------|------|
| `search(HybridQuery)` | 并行跑 Graph + Retrieval，按 `query.strategy` 融合 |
| `searchGraphOnly(HybridQuery)` | 只跑 Graph |
| `searchRetrievalOnly(HybridQuery)` | 只跑 Retrieval |

`isEnabled()` 规则：`graphManager.isEnabled() || ragManager.isEnabled()`——**只要有一条管线开着，混合入口就算开着**，融合时缺的那一侧按空结果处理。

### 4.2 执行流程

```text
Mono.zip(executeGraphSearch, executeRetrievalSearch)    // 并行
      │
      ▼
fuseResults(graphEntities, retrievalChunks, query)
      │  按 query.strategy 分派：
      ├── RRF                → fuseWithRRF
      ├── WEIGHTED_AVERAGE   → fuseWithWeightedAverage
      └── UNION / default    → fuseWithUnion
      ▼
HybridResult（按分数降序取 topK）
```

### 4.3 三种融合策略细节

#### RRF（Reciprocal Rank Fusion）

```text
for i in graphEntities:  key = filePath + ":" + startLine;  score = 1/(60 + i+1)
for j in retrievalChunks: key = filePath + ":" + startLine;
                           若 key 已存在 → score += 1/(60 + j+1)，source 改为 "BOTH"
                           否则新建一条 RETRIEVAL 项
```

- **融合 key** = `filePath + ":" + startLine`——Graph 项的 `startLine` 是**符号定义起始行**（类/方法定义所在行），Retrieval 项的 `startLine` 是**分块窗口起始行**（`chunk_size` 滑窗的边界）。两者语义不同，实际很难撞到同一 key，`source="BOTH"` 在真实数据中几乎不出现
- **k=60** 硬编码在 `HybridSearch.RRF_K`
- 忽略 `graphWeight`/`retrievalWeight`——RRF 的核心就是不依赖原始分数，只看排名

#### 加权平均

- Graph 项：`score = (1 - i/N) * graphWeight`（把排名归一化为 `[0,1]` 再乘权重）
- Retrieval 项：`score = chunk.score * retrievalWeight`（**直接乘** chunk 原始余弦分）
- **不去重**——graphEntities 和 retrievalChunks 简单并在一起排序

⚠️ 这意味着：Graph 项走"排名归一化"、Retrieval 项走"原始分加权"，**两套分数不是同一量纲**，`graphWeight/retrievalWeight` 调节效果与直觉会有差距。要精确可比的归一化，RRF 更合适。

#### UNION

最简单——Graph 项 `score=1.0`、Retrieval 项 `score=chunk.score`，直接拼成一个列表 `limit(topK)`，**不排序**。适合只想看"两边各召回了什么"。

### 4.4 `FusionStrategy` 的实现落差

`HybridQuery.FusionStrategy` 枚举列了 6 个值：`RRF / WEIGHTED_AVERAGE / CASCADE_GRAPH_FIRST / CASCADE_RETRIEVAL_FIRST / INTERSECTION / UNION`。

但 `HybridSearch.fuseResults` 的 `switch` 只有 3 个 `case`：

```text
switch (query.getStrategy()) {
    case RRF:                return fuseWithRRF(...);
    case WEIGHTED_AVERAGE:   return fuseWithWeightedAverage(...);
    case UNION:
    default:                 return fuseWithUnion(...);
}
```

也就是说，**`CASCADE_GRAPH_FIRST`、`CASCADE_RETRIEVAL_FIRST`、`INTERSECTION` 这三个策略目前等价于 UNION**。不要基于这三个做假设，等后续实现到位再用。

### 4.5 `HybridItem` 结果结构

`HybridResult.HybridItem` 的关键字段：

| 字段 | 说明 |
|------|------|
| `source` | `"GRAPH"` / `"RETRIEVAL"` / `"BOTH"`（仅 RRF 合并时出现）|
| `id / name / content` | 内容标识 |
| `filePath / startLine / endLine` | 代码定位 |
| `score` | 融合后分数（语义依策略而定）|
| `graphRank / retrievalRank` | 该项在各自召回列表中的排名；没出现的一边填 `-1` |

---

## 5. 配置详解

以下所有默认值均来自 `config/info/GraphConfig.java` 和 `config/info/VectorIndexConfig.java` 的 `@Builder.Default` 实际赋值（不是 docs 文档里可能过时的描述）。

**配置文件里的 key 路径**：`GraphConfig` 通过 `JimiConfig.graph`（`@JsonProperty("graph")`）挂载，即 YAML/JSON 里写 `graph.enabled`、`graph.auto_load` 等；`VectorIndexConfig` 通过 `JimiConfig.vectorIndex`（`@JsonProperty("vector_index")`）挂载，写 `vector_index.enabled`、`vector_index.index_path` 等。二者都**没有** `jimi.` 前缀。

### 5.1 `GraphConfig`（JSON key：`graph.*`）

| 字段（JSON/字段名） | 默认 | 说明 |
|---------------------|------|------|
| `enabled` / `enabled` | `true` | 总开关 |
| `auto_build` / `autoBuild` | `false` | **不被业务逻辑消费**：`file_grep "autoBuild"` / `auto_build` 在业务代码里仅出现在 `GraphManager` 构造函数的 `log.info` 字符串里，全仓库没有 `WatchService`/`FileWatcher` 相关实现。即使设为 `true` 也不会触发任何自动重建 |
| `build_on_startup` / `buildOnStartup` | `false` | **同样不被业务逻辑消费**：`file_grep "buildOnStartup"` / `build_on_startup` 在业务代码里同样只出现在 `GraphManager` 构造函数的 `log.info` 字符串里，没有任何 `buildGraph` 的启动时自动调用路径 |
| `auto_load` / `autoLoad` | `true` | 启动时尝试加载已保存的图（见 §2.6，但真正触发依赖 `setWorkDir` 被调用，见 §2.5 ⚠️）|
| `auto_save` / `autoSave` | `true` | `buildGraph` 完成后自动落盘 |
| `storage_path` / `storagePath` | `.jimi/code_graph` | 持久化相对路径（**不是** `.jimi/graph`）|
| `include_patterns` / `includePatterns` | `["**/*.java"]` | glob 白名单（`FileSystems.getDefault().getPathMatcher("glob:...")`）|
| `exclude_patterns` / `excludePatterns` | `["**/test/**","**/tests/**","**/target/**","**/build/**","**/node_modules/**","**/.git/**"]` | glob 黑名单 |
| `cache.enabled / ttl / max_size` | `true / 3600 / 10000` | **子结构整体未被消费**：`GraphConfig.CacheConfig` 的三个字段在全仓库业务代码中无任何读取点，只是配置预留 |
| `search.max_results / enable_hybrid / graph_weight / vector_weight / min_similarity` | `50 / true / 0.6 / 0.4 / 0.3` | **子结构整体未被消费**：`GraphConfig.SearchConfig` 的全部字段在业务代码中无任何读取点（`graphWeight`/`vectorWeight` 出现在 `HybridQuery` 中是 `HybridQuery` 自己定义的同名字段，与 `GraphConfig.SearchConfig.graphWeight` 不是同一个 Bean），只是配置预留 |

⚠️ 如 §2.2 所述，`GraphBuilder.matchesExcludePatterns` 会在每次 glob 迭代时 `||` 上 `pathStr.contains("/target/"|"/build/"|"/.git/"|"/node_modules/"|"/test/"|"/tests/")` 这 6 个判断。**默认的 6 条 exclude glob 恰好一一对应这 6 个目录片段**，即使用户修改 `exclude-patterns` 把其中某条去掉，只要 `excludeMatchers` 非空，这 6 个目录仍被硬编码兜底排除，修改配置也挡不住。只有把 `exclude-patterns` 完全清空才能绕过这段兜底（但代价是所有 glob 排除都失效）。如果确实需要对 `test/` 目录建图，得改 `GraphBuilder.matchesExcludePatterns` 源码。

### 5.2 `VectorIndexConfig`（JSON key：`vector_index.*`）

| 字段（JSON/字段名） | 默认 | 说明 |
|---------------------|------|------|
| `enabled` / `enabled` | `false` | 总开关 |
| `index_path` / `indexPath` | `.jimi/index` | 相对工作目录的索引目录 |
| `chunk_size` / `chunkSize` | `50` | 分块窗口（行数）|
| `chunk_overlap` / `chunkOverlap` | `5` | 分块重叠（行数）|
| `top_k` / `topK` | `5` | 默认召回条数 |
| `embedding_provider` / `embeddingProvider` | `"qwen"` | 嵌入提供者标识。装配逻辑在 `config/JimiConfiguration.embeddingProvider(...)`：`switch(providerType.toLowerCase())` → `"qwen"` 生成 `QwenEmbeddingProvider`（需要 `providers.qwen` 存在，否则 fallback 到 Mock）；`"mock"` / `"local"` 生成 `MockEmbeddingProvider`；其他值走 default 也 fallback 到 `MockEmbeddingProvider`。另外 `vector_index.enabled=false` 时直接返回 `MockEmbeddingProvider(dim=1024, name="disabled")`，不走 switch |
| `embedding_model` / `embeddingModel` | `"text-embedding-v2"` | DashScope 模型名 |
| `embedding_dimension` / `embeddingDimension` | `1024` | 向量维度（与 Qwen `text-embedding-v2` 默认输出对齐）|
| `storage_type` / `storageType` | `"file"` | 装配逻辑在 `config/JimiConfiguration.vectorStore(...)`：`switch(storageType.toLowerCase())` 对 `"memory"` / `"file"` / default 均返回 `InMemoryVectorStore`（仅 log 里提示"目前只支持内存存储，后续可扩展"）。设置该字段对当前版本行为**没有区别**，两种值都走同一实现；持久化由 `save()/load()` 是否被调用决定 |
| `auto_load` / `autoLoad` | `true` | **不被业务逻辑消费**：`file_grep "isAutoLoad"` 在业务代码中无匹配，`VectorIndexConfig.autoLoad` 字段只是配置预留。索引实际是否自动加载取决于 `InMemoryVectorStore.setWorkDir` 是否被外部调用，而 `vectorStore.setWorkDir` 在全仓库中也**没有任何调用方**（只有 `InMemoryVectorStore.ensureWorkDirInitialized` 自身会兜底调 `setWorkDir(user.dir)`，但 `ensureWorkDirInitialized` 外部同样无人调用）|
| `file_extensions` / `fileExtensions` | `".java,.kt,.py,.js,.ts,.go,.rs"` | 参与分块的扩展名 |
| `exclude_patterns` / `excludePatterns` | `"**/target/**,**/build/**,**/node_modules/**,**/.git/**"` | 见 §3.5 的简化实现——**仅 `**` 开头的模式生效**，其他形式被忽略 |

> 📌 `docs/RAG配置指南.md` 里写的默认 `embedding_provider: mock` / `embedding_model: all-minilm-l6-v2` / `embedding_dimension: 384` 是**早期文档的过时版本**，以源码 `VectorIndexConfig.java` 为准。

---

## 6. 命令行入口

### 6.1 `/graph` —— `GraphCommandHandler`

实现类：`command/handlers/GraphCommandHandler.java`。别名 `/g`。源码 `execute()` 的 `switch` 支持**8 个**子命令：

一级子命令（`switch` 的 8 个 `case`）：`build / rebuild / stats / clear / status / save / load / query`；其中 `query` 再自行分派 4 个二级查询：

```text
/graph                                   # 空参数时打印 Usage
/graph build [path]                      # 构建；不传 path 时取 context.getEngineClient().getWorkDir()
/graph rebuild                           # 清空 + 重建；要求 isInitialized()=true，否则报错返回
/graph stats                             # 实体数/关系数/初始化状态/项目路径
/graph status                            # 启用状态/初始化状态 + 调 getGraphStats
/graph clear                             # 直接清空（⚠️ 无二次确认，与 /index clear 的行为不同）
/graph save                              # 持久化到 storage_path
/graph load                              # 从磁盘加载

# /graph query 的 4 个二级子命令（都要求 isInitialized()=true，否则报错返回）：
/graph query symbol <name> [-t TYPE] [-l N]
    # 调 GraphSearchEngine.searchBySymbol(name, entityTypes, limit)
    # -t 取值必须是 EntityType 枚举名（不区分大小写，源码 .toUpperCase() 后 EntityType.valueOf）：
    #    PACKAGE / FILE / CLASS / INTERFACE / ENUM / METHOD / CONSTRUCTOR / FIELD / ANNOTATION
    # -l 默认 20；-t 不传则搜全部 9 种 EntityType
/graph query file <path> [limit]
    # 调 GraphSearchEngine.searchByFile(path, limit)；limit 位置参数，默认 50
/graph query callers <methodId> [maxDepth]
    # 调 GraphNavigator.findCallers(methodId, maxDepth)；maxDepth 默认 3
/graph query callees <methodId> [maxDepth]
    # 调 GraphNavigator.findCallees(methodId, maxDepth)；maxDepth 默认 3
    # ⚠️ 见 §2.7.3：findCallees 的 maxDepth 参数在实现中被静默忽略（只取一跳），
    #    即使命令行传 5 也等价于传 1
```

`execute()` 进入后会先 `graphManager.setWorkDir(context.getEngineClient().getWorkDir())`——这是 §2.5 提到的**唯一真实的 `graphManager.setWorkDir` 外部调用点**。

另外 `GraphCommandHandler.execute()` 在未启用时会打印：`请在配置文件中启用: jimi.graph.enabled=true`——这个字符串是源码字面量，但**实际配置 key 是 `graph.enabled`**（无 `jimi.` 前缀，见 §5.1 配置文件路径说明），提示文字本身带有一个口误。

### 6.2 `/index` —— `IndexCommandHandler`

实现类：`command/handlers/IndexCommandHandler.java`。源码 `execute()` 的 `switch` 只支持 **4 个**子命令：

```text
/index build [path]                 # 构建索引；成功后自动 ragManager.save().block() 落盘
/index query <文本...>              # 检索，topK=5、includeContent=true 固定写死
                                    # 输出时 content 字段会截断到前 100 字符 + "..." 做预览
/index stats                        # 打印片段数/文件数/索引大小/最后更新
                                    # ⚠️ 索引大小与存储类型是估算/硬编码值，见 §3.4 的"getStats() 已知简化"
/index clear                        # 第一次：只打印"请重新输入命令确认: /index clear --confirm"，不做实际清空
                                    # 第二次（带 --confirm）：clear + save，并报告删除的片段数
```

⚠️ `docs/RAG配置指南.md` 里提到的 `/index build [options]` 的 `--chunk-size=N` / `--overlap=N` 选项**在 `handleBuild` 中没有解析**——它只取 `args[1]` 作为 path，分块参数仍来自 `VectorIndexConfig`。同样 docs 提到的 `/index update` **在 switch 中没有对应 case**，当前版本完全不存在该子命令。

> 命令分派与 `CommandHandler` SPI 的通用机制详见 [09 · 自定义命令与 CLI 交互](09-自定义命令与CLI交互.md)。

---

## 7. 与其它子系统的关系

| 上游/下游 | 关系 |
|----------|------|
| [02 · 核心引擎](02-系统架构与核心引擎.md) | `HybridSearch` / `GraphManager` / `RagManager` 都是 Spring `@Component`，Spring 启动阶段完成依赖注入；但 `workDir` 是**懒绑定**——首次执行 `/graph` 子命令（或外部主动调 `setWorkDir`）时才会注入，详见 §2.5 ⚠️ |
| [04 · 工具系统](04-工具系统与ToolRegistry.md) | `tool/core/graph/CallGraphTool` 暴露 `queryType` 参数给 LLM，取值 `callers/callees/callchain/visualize` **默认 `visualize`**，分别路由到 `GraphManager.findCallers/findCallees/findCallChains/exportCallGraphToMermaid`——其中 `queryType=callchain` **必须**同时传 `targetMethodId`（否则 `findCallChain` 直接 return error：`"错误: targetMethodId 参数为空"`）；`tool/core/graph/CodeLocateTool` 把 `HybridSearch.search/searchGraphOnly/searchRetrievalOnly` 暴露给 LLM（详见 §7.1）|
| [09 · 自定义命令](09-自定义命令与CLI交互.md) | `/graph`（`GraphCommandHandler`）、`/index`（`IndexCommandHandler`）命令实现 `CommandHandler` SPI |
| [05 · LLM 接入](05-LLM接入层与多模型支持.md) | `QwenEmbeddingProvider` 复用 `LLMProviderConfig` 的 `baseUrl/apiKey/customHeaders`，和 Chat Model 共用一套 Provider 配置 |

### 7.1 `CodeLocateTool` 的模式与参数落差

`CodeLocateTool.Params` 中 `mode` 有 4 个取值，但在 `execute()` 的 `switch` 中：

| 传入 `mode` | 实际行为 |
|-------------|----------|
| `GRAPH_ONLY` | `hybridSearch.searchGraphOnly(query)` |
| `VECTOR_ONLY` | `hybridSearch.searchRetrievalOnly(query)` |
| `HYBRID` | `hybridSearch.search(query)` |
| `SMART`（`@Builder.Default` 即**工具默认值**）| 与 `HYBRID` 并列进入 `switch` 的 `case HYBRID: case SMART: default:` 同一分支 → `hybridSearch.search(query)`，**没有额外的"智能自动选择"逻辑**。LLM 不显式传 `mode` 时就走这一路径 |

另一个常见陷阱：`CodeLocateTool.Params.fusionStrategy` 的 `@JsonPropertyDescription` 里写的可选值 `WEIGHTED_SUM / RRF / MAX / MULTIPLICATIVE`，**`@Builder.Default` 默认值也是 `"WEIGHTED_SUM"`**——但 `WEIGHTED_SUM` / `MAX` / `MULTIPLICATIVE` 三个在 `HybridQuery.FusionStrategy` 枚举里**根本不存在**。`parseFusionStrategy()` 通过 `FusionStrategy.valueOf(strategyStr.toUpperCase())` 转换，对抛出的 `IllegalArgumentException` **静默 catch 回退为 `RRF`**。

所以实际运行时的等价表如下：

| LLM 在 schema 里看到的值 | 最终运行时策略 |
|--------------------------|----------------|
| `WEIGHTED_SUM`（默认） | **fallback → RRF** |
| `RRF` | RRF |
| `MAX` | fallback → RRF |
| `MULTIPLICATIVE` | fallback → RRF |
| `WEIGHTED_AVERAGE`（枚举真实存在，但工具 schema 未写） | WEIGHTED_AVERAGE |
| `UNION` / `CASCADE_*` / `INTERSECTION` | 走 `HybridSearch.fuseResults` 的 default → UNION（见 §4.4）|

同时，`Params` 里还有 4 个字段在 `execute()` 里**完全没有被读取**——`multiSourceBonus`、`entityTypes`、`relationTypes`、`includeRelated`。它们只影响 LLM 看到的工具 schema 文案，传什么值对执行路径都没有影响。

---

## 8. 扩展点

想做二次开发时，按这几个入口切入：

### 8.1 新增语言解析器

实现 `knowledge.graph.parser.LanguageParser` 接口（主要方法：`getLanguageName()` / `getSupportedExtensions()` / `parseFile(Path, Path) → ParseResult` / `isAvailable()` / `getPriority()` / `supportsFile(Path)`），标注 `@Component`，Spring 自动注入到 `LanguageParserRegistry(List<LanguageParser>)` 构造函数里。

同扩展名多解析器时按 `priority` 取最大值。

### 8.2 替换 Embedding

实现 `EmbeddingProvider`（方法：`embed(String) → Mono<float[]>` / `embedBatch(List<String>) → Mono<List<float[]>>` / `getDimension() → int` / `getProviderName() → String`）。Bean 装配入口在 `config/JimiConfiguration.embeddingProvider(...)`——在该 `@Bean` 方法的 `switch(providerType.toLowerCase())` 中新增一个 `case`，对应自己的 provider 名，并让用户在 `vector_index.embedding_provider` 中填入该名字即可。注意 `vector_index.enabled=false` 时该 `@Bean` 会直接返回 `MockEmbeddingProvider(1024, "disabled")` 短路掉 switch，新增的 provider 想生效必须把 `enabled` 开启。

### 8.3 替换向量存储

实现 `VectorStore` 接口（9 个方法，见 §3.4），替换 `InMemoryVectorStore`。注意 `RagManager` 的 `VectorStore`、`EmbeddingProvider`、`Chunker`、`VectorIndexConfig` 都通过 `@Autowired(required=false)` 注入，缺任一 Bean 时 `ragManager.isEnabled()` 会因 `config == null || !config.isEnabled()` 返回 `false`，整个 RAG 管线会被视作"未启用"。

### 8.4 自定义融合策略

目前要改动 `HybridSearch.fuseResults` 源码增加 `case`；没有提供策略 SPI。想做 `INTERSECTION` / `CASCADE_*` 的真实实现，补齐对应分支即可。

---

## 9. 故障排查

| 现象 | 可能原因 / 检查点 |
|------|----------------|
| `/graph build` 产出实体数为 0 | ① `graph.enabled=false`（`GraphCommandHandler` 会提示 `请在配置文件中启用: jimi.graph.enabled=true` 字样，但实际配置 key 是 `graph.enabled`——这是源码打印字符串的已知口误）；② `graph.include_patterns` 未覆盖；③ 目录命中硬编码黑名单（`/test/`、`/target/` 等）|
| `/graph build` 看不到跨包的调用关系 | §2.4 `resolveMethodCallTarget` 的启发式只覆盖三种形式；`obj.method()` 形式返回 `null` 不建边 |
| `/graph build` 后重载进程图消失 | 未启用 `auto-save`，或 `workDir` 未正确传入导致 `resolveStoragePath` 指错 |
| `/index build` 成功但 `/index query` 无结果 | §3.7 落差 1——`buildIndex` 没写回 `embedding`，`search` 里空 embedding 的 chunk 会被跳过 |
| Hybrid 搜索返回数远小于预期 | 确认 `graphManager.isEnabled()` 和 `ragManager.isEnabled()` 是否都为 true；有一侧为 false 时该侧返回空列表 |
| 选了 `CASCADE_GRAPH_FIRST` 但行为像并集 | §4.4——该策略未实现，走 default 分支 ≡ UNION |
| `QwenEmbeddingProvider` 报 401 | 检查 `LLMProviderConfig.apiKey`（DashScope 需要 `Authorization: Bearer <APIKEY>`）|

---

## 10. 精确位置索引

便于二次开发时直接跳转：

| 关键点 | 位置 |
|--------|------|
| Graph Facade | `knowledge/graph/GraphManager.java` |
| Graph 构建器 | `knowledge/graph/builder/GraphBuilder.java`（§2.2）|
| 扫描时的硬编码目录黑名单 | `GraphBuilder.matchesExcludePatterns` |
| Java 解析器 | `knowledge/graph/parser/JavaASTParser.java`（§2.3）|
| 方法调用解析盲区 | `JavaASTParser.resolveMethodCallTarget`（§2.4）|
| 解析器注册中心 | `knowledge/graph/parser/LanguageParserRegistry.java` |
| 实体/关系模型 | `knowledge/graph/model/{CodeEntity,CodeRelation,EntityType,RelationType}.java` |
| 内存图存储 | `knowledge/graph/store/InMemoryCodeGraphStore.java` |
| 搜索/导航/影响分析 | `knowledge/graph/GraphSearchEngine.java`、`knowledge/graph/navigator/{GraphNavigator,ImpactAnalyzer}.java` |
| Mermaid 可视化 | `knowledge/graph/visualization/GraphVisualizer.java` |
| RAG Facade | `knowledge/rag/RagManager.java` |
| 分块器 | `knowledge/rag/SimpleChunker.java`（§3.2）|
| 向量存储 | `knowledge/rag/InMemoryVectorStore.java`（§3.4）|
| Embedding 实现 | `knowledge/rag/{MockEmbeddingProvider,QwenEmbeddingProvider}.java`（§3.3）|
| 混合检索 | `knowledge/HybridSearch.java`（§4）|
| RRF k 常量 | `HybridSearch.RRF_K = 60` |
| 策略 switch 的 3 个 case | `HybridSearch.fuseResults`（§4.4）|
| Graph CLI 命令 | `command/handlers/GraphCommandHandler.java`（§6.1）|
| Graph 工具暴露给 LLM | `tool/core/graph/CallGraphTool.java` |
