# WikiCommandHandler 优化方案

## 一、调研总结

### 1.1 DeepWiki/CodeWiki 核心理念

基于对 Google Code Wiki、DeepWiki 等系统的调研，主要设计理念包括：

#### **自动化与持续更新**
- 每次代码提交后自动扫描和重新生成文档
- 文档与代码保持实时同步
- 避免手动维护导致的文档过期

#### **AI 驱动的智能分析**
- 利用 LLM 理解代码语义和架构设计意图
- 自动生成架构图、类图、序列图等可视化内容
- 基于代码关系自动构建文档链接

#### **交互式导航体验**
- 文档与代码双向超链接
- 从高层概念直接跳转到具体代码实现
- 支持自然语言问答(集成聊天机器人)

#### **向量化检索增强**
- 将代码库向量化建立语义索引
- 支持语义搜索而非简单文本匹配
- 基于向量相似度推荐相关文档

#### **增量更新策略**
- 识别变更影响范围，仅更新受影响文档
- 使用内容哈希/时间戳跟踪变更
- 最小化重新生成成本

---

## 二、当前实现分析

### 2.1 优点
✅ **幂等性设计** - init 操作支持重复执行
✅ **结构化文档** - 预定义清晰的目录结构
✅ **AI 辅助生成** - 利用 LLM 分析和撰写文档
✅ **变更检测** - 基于文件修改时间检测变更
✅ **增量更新** - update 命令支持部分更新

### 2.2 不足与改进空间

#### **问题 1: 变更检测不够智能**
- ❌ 仅基于文件修改时间判断，容易误报
- ❌ 无法识别变更影响范围(如改了接口，实现类文档是否需要更新?)
- ❌ 无法区分重要变更(架构调整) vs 微小变更(注释修改)

#### **问题 2: 缺少语义检索能力**
- ❌ 未利用项目已有的向量索引系统(`VectorStore`, `RetrievalPipeline`)
- ❌ 文档生成时无法基于语义检索相关代码
- ❌ 无法支持用户对 Wiki 的语义搜索

#### **问题 3: 文档质量难以保证**
- ❌ 完全依赖 LLM 一次性生成，缺少验证机制
- ❌ 无法检测文档与代码的一致性
- ❌ 缺少文档质量评估指标

#### **问题 4: 用户交互性不足**
- ❌ 无法在 Wiki 中直接提问
- ❌ 缺少代码与文档的双向跳转链接
- ❌ 无可视化图表更新机制

#### **问题 5: 性能与扩展性问题**
- ❌ 初始化时对所有代码一次性分析，大项目耗时长
- ❌ 未使用并发/流式处理优化生成速度
- ❌ 无法支持多语言项目

---

## 三、优化方案设计

### 3.1 整体架构升级

```
┌─────────────────────────────────────────────────────────────┐
│                    WikiCommandHandler                        │
├─────────────────────────────────────────────────────────────┤
│  核心组件                                                     │
│  ├─ WikiIndexManager      (向量索引管理)                     │
│  ├─ WikiGenerator         (文档生成引擎)                     │
│  ├─ ChangeDetector        (智能变更检测)                     │
│  ├─ DiagramGenerator      (图表生成器)                       │
│  ├─ WikiValidator         (文档验证器)                       │
│  └─ WikiSearchEngine      (Wiki 语义搜索)                    │
├─────────────────────────────────────────────────────────────┤
│  依赖服务                                                     │
│  ├─ VectorStore           (代码向量存储)                     │
│  ├─ EmbeddingProvider     (向量化服务)                       │
│  ├─ RetrievalPipeline     (检索增强)                         │
│  └─ Engine                (LLM 执行引擎)                      │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 核心优化点

#### **优化 1: 智能变更检测 (ChangeDetector)**

##### 实现策略
```java
public class ChangeDetector {
    
    // 1. 基于内容哈希的变更检测
    public List<FileChange> detectChanges(Path wikiPath) {
        // 使用 MD5/SHA256 而非文件修改时间
        // 配合 Git diff 识别实际变更行
    }
    
    // 2. 影响分析(基于依赖关系)
    public Map<String, Set<String>> analyzeImpact(List<FileChange> changes) {
        // 例如: AgentRegistry.java 改了 → agents/xxx.md 需要更新
        // 可通过静态分析或向量相似度判断
    }
    
    // 3. 变更重要性评分
    public ChangeImportance scoreChange(FileChange change) {
        // CRITICAL:  架构级变更(如新增模块)
        // MAJOR:     接口/API 变更
        // MINOR:     实现细节优化
        // TRIVIAL:   注释/格式调整
    }
}
```

##### 配置参数
```json
{
  "wiki": {
    "change_detection": {
      "use_content_hash": true,
      "use_git_diff": true,
      "ignore_trivial_changes": true,
      "min_change_lines": 5
    }
  }
}
```

---

#### **优化 2: 集成向量检索 (WikiIndexManager)**

##### 设计思路
利用现有的 `VectorStore` 和 `RetrievalPipeline` 为 Wiki 提供:
1. **代码检索增强生成** - 生成文档时自动检索相关代码示例
2. **Wiki 内容向量化** - 将生成的 Wiki 也向量化便于搜索
3. **语义搜索 Wiki** - 新增 `/wiki search <query>` 命令

##### 实现示例
```java
@Component
public class WikiIndexManager {
    
    @Autowired
    private VectorStore vectorStore;
    
    @Autowired
    private EmbeddingProvider embeddingProvider;
    
    // 为文档生成检索相关代码片段
    public List<CodeChunk> retrieveRelevantCode(String docSection, int topK) {
        float[] queryVector = embeddingProvider.embed(docSection).block();
        return vectorStore.search(queryVector, topK, null).block();
    }
    
    // 向量化 Wiki 文档
    public void indexWikiDocument(Path docPath, String content) {
        // 将 Wiki Markdown 分块并向量化
        // 存储到单独的 wiki-index 或打标签区分
    }
    
    // 搜索 Wiki
    public List<WikiSearchResult> searchWiki(String query, int topK) {
        float[] queryVector = embeddingProvider.embed(query).block();
        // 从向量库检索相关 Wiki 片段
    }
}
```

##### 新增命令
```
/wiki search <query>     # 语义搜索 Wiki 内容
/wiki related <file>     # 查找与指定文件相关的 Wiki 文档
```

---

#### **优化 3: 文档生成引擎升级 (WikiGenerator)**

##### 改进点
1. **分阶段生成** - 避免一次性生成所有文档阻塞
2. **模板化** - 定义文档生成模板提高一致性
3. **检索增强** - 在 Prompt 中注入检索到的相关代码
4. **增量更新优化** - 仅重新生成受影响的章节

##### 实现伪代码
```java
public class WikiGenerator {
    
    // 分阶段生成
    public void generateWikiInStages(Path wikiPath) {
        // Stage 1: 项目概览 (README.md)
        generateOverview(wikiPath);
        
        // Stage 2: 架构分析 (architecture/)
        generateArchitectureDocs(wikiPath);
        
        // Stage 3: API 文档 (api/)
        generateApiDocs(wikiPath);
        
        // Stage 4: 使用指南 (guides/)
        generateGuides(wikiPath);
        
        // ... 每个阶段可异步执行
    }
    
    // 检索增强生成
    public String generateWithRetrieval(String section, String template) {
        // 1. 从向量库检索相关代码
        List<CodeChunk> relevantCode = wikiIndexManager.retrieveRelevantCode(section, 5);
        
        // 2. 构建增强 Prompt
        String enhancedPrompt = template
            .replace("{{SECTION}}", section)
            .replace("{{RELEVANT_CODE}}", formatCodeChunks(relevantCode));
        
        // 3. 调用 LLM 生成
        return engine.run(enhancedPrompt).block();
    }
}
```

---

#### **优化 4: 图表自动生成 (DiagramGenerator)**

##### 功能
- 自动生成架构图、类图、序列图
- 基于代码依赖关系生成 Mermaid 图表
- 变更时自动更新图表

##### 实现
```java
public class DiagramGenerator {
    
    // 生成模块依赖图
    public String generateModuleDiagram() {
        // 分析 package 结构和 import 关系
        // 生成 Mermaid graph
    }
    
    // 生成类图
    public String generateClassDiagram(String packageName) {
        // 解析 Java 类定义
        // 生成 Mermaid classDiagram
    }
    
    // 生成序列图
    public String generateSequenceDiagram(String methodName) {
        // 分析方法调用链
        // 生成 Mermaid sequenceDiagram
    }
}
```

---

#### **优化 5: 文档验证器 (WikiValidator)**

##### 验证内容
1. **链接有效性** - 检查文档中的内部链接是否有效
2. **代码引用准确性** - 验证引用的代码是否存在
3. **图表语法** - 检查 Mermaid 语法是否正确
4. **文档完整性** - 检查是否所有预期文档都已生成

##### 实现
```java
public class WikiValidator {
    
    public ValidationReport validate(Path wikiPath) {
        ValidationReport report = new ValidationReport();
        
        // 1. 检查链接
        report.addIssues(validateLinks(wikiPath));
        
        // 2. 检查代码引用
        report.addIssues(validateCodeReferences(wikiPath));
        
        // 3. 检查 Mermaid 语法
        report.addIssues(validateDiagrams(wikiPath));
        
        return report;
    }
}
```

##### 新增命令
```
/wiki validate    # 验证 Wiki 文档质量
```

---

#### **优化 6: 交互式 Wiki 问答**

##### 功能
类似 Google Code Wiki 的聊天机器人，基于 Wiki 内容回答问题

##### 实现
```java
// 新增子命令
private void executeAsk(CommandContext context) {
    String question = String.join(" ", Arrays.copyOfRange(context.getArgs(), 1, context.getArgs().length));
    
    // 1. 向量检索相关 Wiki 片段
    List<WikiSearchResult> results = wikiSearchEngine.searchWiki(question, 3);
    
    // 2. 构建 Prompt
    String prompt = buildAskPrompt(question, results);
    
    // 3. 调用 LLM 回答
    context.getSoul().run(prompt).block();
}
```

##### 新增命令
```
/wiki ask <question>    # 基于 Wiki 回答问题
```

---

### 3.3 性能优化

#### **并发生成**
```java
// 使用 CompletableFuture 并发生成多个文档
List<CompletableFuture<Void>> tasks = Arrays.asList(
    CompletableFuture.runAsync(() -> generateOverview(wikiPath)),
    CompletableFuture.runAsync(() -> generateArchitectureDocs(wikiPath)),
    CompletableFuture.runAsync(() -> generateApiDocs(wikiPath))
);

CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
```

#### **流式输出**
```java
// 使用 Reactor 流式处理
Flux.fromIterable(sourceFiles)
    .flatMap(file -> generateDocForFile(file))
    .doOnNext(doc -> saveDocument(doc))
    .subscribe();
```

#### **缓存机制**
```java
// 缓存文档生成结果，避免重复生成
private Map<String, CachedDocument> documentCache = new ConcurrentHashMap<>();

public String getOrGenerateDocument(String docKey, Supplier<String> generator) {
    CachedDocument cached = documentCache.get(docKey);
    if (cached != null && !cached.isExpired()) {
        return cached.getContent();
    }
    
    String content = generator.get();
    documentCache.put(docKey, new CachedDocument(content, System.currentTimeMillis()));
    return content;
}
```

---

### 3.4 配置扩展

#### 新增配置项
```json
{
  "wiki": {
    "enabled": true,
    "output_dir": ".jimi/wiki",
    
    "generation": {
      "concurrent": true,
      "max_threads": 4,
      "enable_retrieval": true,
      "retrieval_top_k": 5,
      "enable_diagrams": true
    },
    
    "change_detection": {
      "use_content_hash": true,
      "use_git_diff": true,
      "ignore_trivial_changes": true,
      "min_change_lines": 5
    },
    
    "validation": {
      "auto_validate": true,
      "check_links": true,
      "check_code_refs": true,
      "check_diagrams": true
    },
    
    "indexing": {
      "index_wiki_content": true,
      "chunk_size": 100,
      "enable_search": true
    }
  }
}
```

---

## 四、实施路线图

### Phase 1: 基础优化 (1-2周)
- [ ] 实现 `ChangeDetector` 基于内容哈希的变更检测
- [ ] 集成 `VectorStore` 支持检索增强生成
- [ ] 优化 Prompt 提高文档生成质量

### Phase 2: 核心功能 (2-3周)
- [ ] 实现 `DiagramGenerator` 自动生成图表
- [ ] 实现 `WikiValidator` 文档验证
- [ ] 实现 `WikiSearchEngine` 语义搜索
- [ ] 新增 `/wiki search` 和 `/wiki ask` 命令

### Phase 3: 性能与体验 (1-2周)
- [ ] 实现并发生成优化
- [ ] 实现文档缓存机制
- [ ] 优化增量更新策略
- [ ] 添加进度显示和用户反馈

### Phase 4: 高级功能 (可选)
- [ ] 支持多语言项目(Python, JavaScript 等)
- [ ] 集成 Git 提交钩子自动更新
- [ ] 支持自定义文档模板
- [ ] 生成 HTML/PDF 版本文档

---

## 五、核心代码示例

### 5.1 重构后的 WikiCommandHandler

```java
@Slf4j
@Component
public class WikiCommandHandler implements CommandHandler {
    
    @Autowired(required = false)
    private WikiGenerator wikiGenerator;
    
    @Autowired(required = false)
    private ChangeDetector changeDetector;
    
    @Autowired(required = false)
    private WikiValidator wikiValidator;
    
    @Autowired(required = false)
    private WikiSearchEngine wikiSearchEngine;
    
    @Override
    public void execute(CommandContext context) throws Exception {
        if (context.getArgCount() == 0) {
            executeInit(context);
            return;
        }
        
        String subCommand = context.getArg(0);
        switch (subCommand.toLowerCase()) {
            case "init":
                executeInit(context);
                break;
            case "update":
                executeUpdate(context);
                break;
            case "search":
                executeSearch(context);
                break;
            case "ask":
                executeAsk(context);
                break;
            case "validate":
                executeValidate(context);
                break;
            case "delete":
                executeDelete(context);
                break;
            default:
                showUsageHelp(context);
                break;
        }
    }
    
    private void executeInit(CommandContext context) {
        // 利用 WikiGenerator 分阶段生成
        wikiGenerator.generateWiki(context);
    }
    
    private void executeUpdate(CommandContext context) {
        // 智能变更检测 + 增量更新
        List<FileChange> changes = changeDetector.detectChanges(getWikiPath(context));
        wikiGenerator.updateWiki(context, changes);
    }
    
    private void executeSearch(CommandContext context) {
        String query = String.join(" ", Arrays.copyOfRange(context.getArgs(), 1, context.getArgs().length));
        List<WikiSearchResult> results = wikiSearchEngine.search(query, 5);
        displaySearchResults(context, results);
    }
    
    private void executeAsk(CommandContext context) {
        String question = String.join(" ", Arrays.copyOfRange(context.getArgs(), 1, context.getArgs().length));
        List<WikiSearchResult> results = wikiSearchEngine.search(question, 3);
        String prompt = buildAskPrompt(question, results);
        context.getSoul().run(prompt).block();
    }
    
    private void executeValidate(CommandContext context) {
        ValidationReport report = wikiValidator.validate(getWikiPath(context));
        displayValidationReport(context, report);
    }
}
```

---

## 六、总结

本优化方案参考了 Google Code Wiki 和 DeepWiki 的先进设计理念，结合 Jimi 项目现有的向量索引能力，提出了以下核心改进：

1. **智能变更检测** - 基于内容哈希和依赖分析，精准识别需要更新的文档
2. **向量检索增强** - 利用语义搜索提升文档生成质量和用户查询体验
3. **自动化图表生成** - 基于代码结构自动生成架构图、类图等可视化内容
4. **文档质量保证** - 通过验证器确保文档与代码的一致性
5. **交互式问答** - 支持基于 Wiki 内容的自然语言问答
6. **性能优化** - 并发生成、流式处理、缓存机制提升效率

这些优化将使 Jimi 的 Wiki 系统从"静态文档生成器"进化为"智能代码知识库"，显著提升开发者理解和使用项目的效率。
