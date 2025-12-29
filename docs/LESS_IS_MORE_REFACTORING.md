# Jimi 精简重构方案 - Less is More

> 本文档基于"Less is More"原则，对Jimi项目进行系统性精简，减少模块数量、降低复杂度、提升可维护性。

## 一、现状分析

### 1.1 模块统计

| 维度 | 当前数量 | 问题 |
|------|----------|------|
| 顶级模块（包） | 19个 | 模块划分过细，职责边界模糊 |
| 配置类 | 14个 | 配置碎片化，增加理解成本 |
| 命令处理器 | 17个 | 部分功能可合并 |
| Memory相关类 | 14个 | Store类冗余 |
| Retrieval系统 | 12个类 | 多个Provider实现 |
| 内置Agent | 11个 | 功能存在重叠 |

### 1.2 巨型类问题

| 文件 | 大小 | 问题 |
|------|------|------|
| `AgentExecutor.java` | 54.5KB | 单一类承担过多职责 |
| `WikiCommandHandler.java` | 42.1KB | 业务逻辑与命令处理耦合 |
| `MemoryManager.java` | 21.6KB | 管理职责过于集中 |
| `SkillLoader.java` | 18.1KB | 加载逻辑复杂 |
| `SkillMatcher.java` | 15.6KB | 匹配逻辑可简化 |

### 1.3 当前模块结构

```
jimi/
├── agent/          # Agent定义与注册
├── cli/            # 命令行入口
├── command/        # 命令系统（含17个handler）
├── config/         # 配置类（14个）
├── engine/         # 执行引擎（含7个子模块）
├── exception/      # 异常定义
├── graph/          # 代码图谱
├── hook/           # 钩子系统
├── llm/            # LLM提供商
├── mcp/            # MCP协议
├── memory/         # 记忆系统（14个类）
├── retrieval/      # RAG检索（12个类）
├── session/        # 会话管理
├── skill/          # 技能系统
├── tool/           # 工具系统
├── ui/             # 用户界面
├── wiki/           # 知识库
└── wire/           # 通信协议
```

---

## 二、精简目标

### 2.1 量化目标

| 指标 | 当前 | 目标 | 精简比例 |
|------|------|------|----------|
| 顶级模块 | 19 | 7 | -63% |
| 配置类 | 14 | 4 | -71% |
| 命令处理器 | 17 | 10 | -41% |
| Memory类 | 14 | 6 | -57% |
| 内置Agent | 11 | 6 | -45% |

### 2.2 核心原则

1. **一个概念一个归属** - 消除skill/tool/hook等多个扩展点的混淆
2. **配置扁平化** - 用嵌套结构替代多文件配置
3. **Store统一化** - 一个Store + 类型区分，替代多个Store实现
4. **Agent精而不多** - 用prompt工程替代Agent增殖

---

## 三、精简方案

### 3.1 模块合并

#### 3.1.1 知识系统合并

**当前：** `memory` + `retrieval` + `graph` + `wiki` = 4个独立模块

**目标：** 合并为 `knowledge` 模块

```
knowledge/
├── store/              # 统一存储
│   ├── KnowledgeStore.java
│   └── KnowledgeType.java     # 枚举：MEMORY, GRAPH, WIKI, VECTOR
├── retrieval/          # 检索能力
│   ├── VectorRetrieval.java
│   ├── GraphRetrieval.java
│   └── RetrievalPipeline.java
├── embedding/          # 向量化
│   └── EmbeddingProvider.java
└── KnowledgeManager.java       # 统一入口
```

**合并理由：**
- `memory`、`wiki`、`graph`、`retrieval` 都围绕"知识存储与检索"
- 统一后可共享存储基础设施
- 减少跨模块调用的复杂度

#### 3.1.2 技能系统合并到工具

**当前：** `skill` 作为独立模块存在

**目标：** 合并到 `tool` 模块下

```
tool/
├── core/               # 核心工具
├── provider/           # 工具提供者
├── skill/              # 技能（作为高级工具）
│   ├── SkillProvider.java
│   └── SkillSpec.java
└── ToolRegistry.java
```

**合并理由：**
- Skill本质是"基于prompt的高级工具"
- 减少概念数量，统一扩展点

#### 3.1.3 Hook系统简化

**当前：** `hook` 作为独立模块，包含8个类

**目标：** 简化为 `engine/hook` 子模块

```
engine/
├── hook/
│   ├── Hook.java
│   ├── HookRegistry.java
│   └── HookExecutor.java
└── ...
```

**合并理由：**
- Hook是执行引擎的辅助机制
- 不需要独立顶级模块

### 3.2 配置类精简

#### 当前配置类（14个）

```
config/
├── ConfigLoader.java
├── GraphConfig.java
├── JimiConfig.java
├── JimiConfiguration.java
├── LLMModelConfig.java
├── LLMProviderConfig.java
├── LoopControlConfig.java
├── MemoryConfig.java
├── MetaToolConfig.java
├── ShellUIConfig.java
├── ThemeConfig.java
├── VectorIndexConfig.java
├── VectorIndexConfiguration.java
└── WebSearchConfig.java
```

#### 目标配置类（4个）

```
config/
├── JimiConfig.java          # 根配置，包含所有子配置
├── LLMConfig.java           # LLM相关（provider + model + loop）
├── KnowledgeConfig.java     # 知识相关（vector + graph + memory）
└── UIConfig.java            # UI相关（shell + theme）
```

#### 配置结构示例

```java
// JimiConfig.java - 根配置
public class JimiConfig {
    private LLMConfig llm;
    private KnowledgeConfig knowledge;
    private UIConfig ui;
    private ToolConfig tool;
}

// LLMConfig.java - 合并LLM相关配置
public class LLMConfig {
    private Map<String, ProviderConfig> providers;
    private String defaultProvider;
    private String defaultModel;
    private int maxSteps = 50;
    private int maxTokens = 100000;
}

// KnowledgeConfig.java - 合并知识相关配置
public class KnowledgeConfig {
    private VectorConfig vector;
    private GraphConfig graph;
    private MemoryConfig memory;
}
```

### 3.3 命令处理器合并

#### 当前处理器（17个）

| 处理器 | 功能 |
|--------|------|
| AgentsCommandHandler | Agent管理 |
| AsyncCommandHandler | 异步任务 |
| ClearCommandHandler | 清屏 |
| CommandsCommandHandler | 命令列表 |
| CompactCommandHandler | 上下文压缩 |
| ConfigCommandHandler | 配置查看 |
| GraphCommandHandler | 代码图谱 |
| HelpCommandHandler | 帮助 |
| HistoryCommandHandler | 历史记录 |
| HooksCommandHandler | 钩子管理 |
| IndexCommandHandler | 索引管理 |
| InitCommandHandler | 初始化 |
| ResetCommandHandler | 重置 |
| StatusCommandHandler | 状态查看 |
| ToolsCommandHandler | 工具列表 |
| VersionCommandHandler | 版本信息 |
| WikiCommandHandler | 知识库管理 |

#### 合并方案（10个）

| 合并后 | 包含功能 | 命令 |
|--------|----------|------|
| AgentCommandHandler | Agent管理 | /agent |
| KnowledgeCommandHandler | 图谱+索引+Wiki | /knowledge, /index, /wiki |
| SessionCommandHandler | 清屏+重置+压缩+历史 | /clear, /reset, /compact, /history |
| SystemCommandHandler | 状态+版本+配置 | /status, /version, /config |
| ToolCommandHandler | 工具+钩子 | /tools, /hooks |
| AsyncCommandHandler | 异步任务 | /async |
| HelpCommandHandler | 帮助 | /help |
| CommandsCommandHandler | 命令列表 | /commands |
| InitCommandHandler | 初始化 | /init |
| CustomCommandHandler | 自定义复合命令 | 动态 |

### 3.4 Memory系统精简

#### 当前Store类（4个独立Store）

```
memory/
├── ErrorPatternStore.java
├── ProjectInsightsStore.java
├── SessionSummaryStore.java
└── TaskHistoryStore.java
```

#### 目标结构（1个统一Store）

```
memory/
├── MemoryStore.java          # 统一存储接口
├── MemoryType.java           # 枚举类型
├── MemoryEntry.java          # 统一数据模型
├── MemoryManager.java        # 管理器（精简版）
├── MemoryExtractor.java      # 提取器
└── MemoryInjector.java       # 注入器
```

#### 统一数据模型

```java
public class MemoryEntry {
    private String id;
    private MemoryType type;      // ERROR_PATTERN, PROJECT_INSIGHT, SESSION_SUMMARY, TASK_HISTORY
    private String content;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

public enum MemoryType {
    ERROR_PATTERN,
    PROJECT_INSIGHT,
    SESSION_SUMMARY,
    TASK_HISTORY,
    USER_PREFERENCE
}
```

### 3.5 Agent精简

#### 当前Agent（11个）

| Agent | 用途 |
|-------|------|
| default | 通用对话 |
| code | 代码编写 |
| debug | 调试问题 |
| review | 代码审查 |
| test | 测试生成 |
| design | 架构设计 |
| plan | 任务规划 |
| build | 构建相关 |
| deploy | 部署相关 |
| doc | 文档生成 |
| research | 技术调研 |

#### 精简后Agent（6个）

| Agent | 合并来源 | 用途 |
|-------|----------|------|
| default | default | 通用对话与简单任务 |
| code | code + debug | 代码编写与调试 |
| architect | design + plan | 架构设计与任务规划 |
| quality | review + test | 代码审查与测试 |
| devops | build + deploy | 构建与部署 |
| doc | doc + research | 文档与调研 |

**精简原则：**
- 功能相近的Agent合并
- 通过prompt变体实现细分功能
- 保持核心能力覆盖

### 3.6 巨型类拆分

#### 3.6.1 AgentExecutor拆分

**当前：** `AgentExecutor.java` (54.5KB) - 承担过多职责

**拆分方案：**

```
engine/
├── executor/
│   ├── AgentExecutor.java       # 核心执行流程（精简版）
│   ├── ToolDispatcher.java      # 工具调度
│   ├── ContextManager.java      # 上下文管理
│   ├── ResponseProcessor.java   # 响应处理
│   └── ExecutionState.java      # 执行状态
└── ...
```

#### 3.6.2 WikiCommandHandler拆分

**当前：** `WikiCommandHandler.java` (42.1KB)

**拆分方案：**

```
command/handlers/
├── WikiCommandHandler.java      # 命令处理（精简版）
└── ...

knowledge/wiki/
├── WikiService.java             # Wiki业务逻辑
├── WikiGenerator.java           # 内容生成
└── WikiIndexer.java             # 索引管理
```

---

## 四、目标架构

### 4.1 精简后模块结构

```
jimi/
├── core/               # 核心（agent + session + engine合并）
│   ├── agent/
│   ├── session/
│   └── engine/
├── llm/                # LLM提供商
├── tool/               # 工具系统（含skill）
│   ├── core/
│   ├── provider/
│   └── skill/
├── knowledge/          # 知识系统（memory + retrieval + graph + wiki）
│   ├── store/
│   ├── retrieval/
│   ├── graph/
│   └── wiki/
├── command/            # 命令系统
├── config/             # 配置（4个核心类）
├── mcp/                # MCP协议
└── ui/                 # 用户界面
```

**模块数量：19 → 7**

### 4.2 依赖关系

```
ui → command → core → tool
                ↓      ↓
            knowledge ← llm
                ↓
              mcp
```

---

## 五、实施步骤

### 阶段一：配置精简（低风险）

| 步骤 | 任务 | 预估工时 |
|------|------|----------|
| 1.1 | 合并LLM相关配置类 | 2h |
| 1.2 | 合并Knowledge相关配置类 | 2h |
| 1.3 | 合并UI相关配置类 | 1h |
| 1.4 | 更新ConfigLoader | 2h |
| 1.5 | 更新配置文件格式 | 1h |

### 阶段二：Memory系统精简（中等风险）

| 步骤 | 任务 | 预估工时 |
|------|------|----------|
| 2.1 | 创建统一MemoryEntry模型 | 1h |
| 2.2 | 创建统一MemoryStore | 2h |
| 2.3 | 迁移现有Store实现 | 3h |
| 2.4 | 精简MemoryManager | 2h |
| 2.5 | 删除冗余Store类 | 1h |

### 阶段三：命令处理器合并（中等风险）

| 步骤 | 任务 | 预估工时 |
|------|------|----------|
| 3.1 | 创建KnowledgeCommandHandler | 3h |
| 3.2 | 创建SessionCommandHandler | 2h |
| 3.3 | 创建SystemCommandHandler | 1h |
| 3.4 | 迁移并删除原处理器 | 2h |

### 阶段四：Skill合并到Tool（中等风险）

| 步骤 | 任务 | 预估工时 |
|------|------|----------|
| 4.1 | 在tool下创建skill子模块 | 1h |
| 4.2 | 迁移Skill相关类 | 2h |
| 4.3 | 更新ToolRegistry集成 | 2h |
| 4.4 | 删除原skill模块 | 1h |

### 阶段五：知识系统合并（高风险）

| 步骤 | 任务 | 预估工时 |
|------|------|----------|
| 5.1 | 创建knowledge模块结构 | 1h |
| 5.2 | 迁移memory相关类 | 3h |
| 5.3 | 迁移retrieval相关类 | 3h |
| 5.4 | 迁移graph相关类 | 2h |
| 5.5 | 迁移wiki相关类 | 2h |
| 5.6 | 创建KnowledgeManager统一入口 | 3h |
| 5.7 | 删除原模块 | 1h |

### 阶段六：巨型类拆分（高风险）

| 步骤 | 任务 | 预估工时 |
|------|------|----------|
| 6.1 | AgentExecutor职责拆分 | 4h |
| 6.2 | WikiCommandHandler拆分 | 3h |
| 6.3 | MemoryManager精简 | 2h |

### 阶段七：Agent精简（低风险）

| 步骤 | 任务 | 预估工时 |
|------|------|----------|
| 7.1 | 合并相近Agent配置 | 2h |
| 7.2 | 更新Agent prompt | 2h |
| 7.3 | 删除冗余Agent目录 | 1h |

---

## 六、风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 模块合并导致循环依赖 | 高 | 先画依赖图，渐进式合并 |
| 配置格式变更影响用户 | 中 | 提供配置迁移工具 |
| 巨型类拆分引入bug | 高 | 充分的单元测试覆盖 |
| Agent合并影响用户习惯 | 低 | 保留命令别名 |

---

## 七、预期收益

### 7.1 量化收益

| 指标 | 优化前 | 优化后 | 改进 |
|------|--------|--------|------|
| 顶级模块数 | 19 | 7 | -63% |
| 配置类数量 | 14 | 4 | -71% |
| Java文件数 | ~150 | ~100 | -33% |
| 代码行数 | ~15000 | ~11000 | -27% |

### 7.2 质性收益

1. **降低认知负担** - 新开发者更容易理解项目结构
2. **减少维护成本** - 更少的代码意味着更少的bug
3. **提升扩展性** - 统一的扩展点让扩展更简单
4. **加快构建速度** - 更少的模块减少编译时间

---

## 八、验收标准

1. 所有现有测试通过
2. 核心功能（Agent执行、工具调用、知识检索）正常工作
3. 命令系统响应正确
4. 配置加载兼容旧格式（或提供迁移方案）
5. 代码覆盖率不低于优化前

---

*文档版本: 1.0*
*创建日期: 2024年*
*状态: 待评审*
