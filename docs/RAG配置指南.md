# 向量索引 RAG 配置指南

## 功能概述

Jimi 现已支持本地化向量索引，通过 RAG（检索增强生成）提升大模型对项目代码的理解能力。

## 快速开始

### 1. 启用向量索引

在 `.jimi/config.json` 中添加 `vector_index` 配置：

```json
{
  "default_model": "qwen-max",
  "models": { ... },
  "providers": { ... },
  "vector_index": {
    "enabled": true,
    "index_path": ".jimi/index",
    "chunk_size": 50,
    "chunk_overlap": 5,
    "top_k": 5,
    "embedding_provider": "mock",
    "embedding_model": "all-minilm-l6-v2",
    "embedding_dimension": 384,
    "storage_type": "file",
    "auto_load": true,
    "file_extensions": ".java,.kt,.py,.js,.ts,.go,.rs",
    "exclude_patterns": "**/target/**,**/build/**,**/node_modules/**,**/.git/**"
  }
}
```

### 2. 构建索引

```bash
# 为当前项目构建索引
/index build

# 为指定目录构建索引
/index build src/main/java

# 自定义分块参数
/index build src --chunk-size=100 --overlap=10
```

### 3. 查询索引

```bash
# 查询相关代码片段
/index query 如何处理用户认证

# 查看索引统计
/index stats
```

### 4. 自动检索增强

启用后，每次对话都会自动检索相关代码片段并注入上下文，无需手动操作。

## 配置参数说明

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | boolean | false | 是否启用向量索引 |
| `index_path` | string | .jimi/index | 索引存储路径（相对工作目录） |
| `chunk_size` | int | 50 | 代码分块大小（行数） |
| `chunk_overlap` | int | 5 | 分块重叠大小（行数） |
| `top_k` | int | 5 | 每次检索返回的片段数量 |
| `embedding_provider` | string | mock | 嵌入提供者类型（mock/local/openai/dashscope） |
| `embedding_model` | string | all-minilm-l6-v2 | 嵌入模型名称 |
| `embedding_dimension` | int | 384 | 向量维度 |
| `storage_type` | string | file | 存储类型（memory/file） |
| `auto_load` | boolean | true | 启动时自动加载索引 |
| `file_extensions` | string | .java,.kt,... | 支持的文件扩展名（逗号分隔） |
| `exclude_patterns` | string | **/target/**,... | 排除的路径模式（glob，逗号分隔） |

## 命令说明

### `/index build [path] [options]`

构建向量索引。

**参数：**
- `path`: 目标路径（默认当前目录）
- `--chunk-size=N`: 分块大小（行数）
- `--overlap=N`: 重叠大小（行数）

**示例：**
```bash
/index build src/main/java --chunk-size=50 --overlap=5
```

### `/index query <text>`

查询索引并预览相关代码片段。

**示例：**
```bash
/index query Agent执行流程
```

### `/index stats`

查看索引统计信息（片段数、文件数、索引大小等）。

### `/index update [path]`

增量更新索引（开发中）。

### `/index clear`

清空索引（开发中）。

## 工作原理

1. **代码分块**：将源代码按固定行数分块，支持重叠以保留上下文
2. **向量化**：使用嵌入模型将代码片段转换为向量
3. **存储**：向量存储在本地文件（JSONL + 二进制向量文件）
4. **检索**：用户提问时，生成查询向量并进行相似度搜索
5. **注入**：将TopK相关片段格式化后注入到上下文
6. **增强**：大模型基于检索到的代码片段生成更准确的回答

## 架构设计

```
┌─────────────────┐
│  AgentExecutor  │
└────────┬────────┘
         │ 1. 提取用户查询
         v
┌─────────────────────┐
│ RetrievalPipeline   │
└──┬────────────────┬─┘
   │                │
   v                v
┌──────────────┐ ┌──────────────┐
│ Embedding    │ │ VectorStore  │
│ Provider     │ │              │
└──────────────┘ └──────────────┘
   │                │
   │ 2. 生成向量    │ 3. 相似度搜索
   v                v
   └────────┬───────┘
            │ 4. TopK结果
            v
      ┌──────────┐
      │ Context  │ 5. 注入上下文
      └──────────┘
```

## 扩展说明

### 当前实现（第一步）

- ✅ 核心接口定义（EmbeddingProvider, VectorStore, Chunker, RetrievalPipeline）
- ✅ 简单代码分块器（固定行数窗口）
- ✅ 内存型向量存储（支持文件持久化）
- ✅ 模拟嵌入提供者（基于哈希，仅用于测试）
- ✅ 索引管理命令（build/query/stats）
- ✅ AgentExecutor 自动检索注入
- ✅ Spring 自动装配

### 后续扩展方向

**第二步：本地真实嵌入模型**
- 集成 ONNX Runtime 运行本地嵌入模型
- 支持 Sentence Transformers 模型
- 提供多语言嵌入模型选择

**第三步：Skill 驱动的上下文优化**
- CodeRAGSkill：根据任务类型智能触发检索
- 结构化注入：API摘要、关键文件、依赖关系
- 与现有 SkillProvider 集成

**第四步：高级检索策略**
- 混合检索（向量 + BM25 关键词）
- 重排序（Reranker）
- 增量更新（基于 git diff）
- 符号级分块（AST 解析）

**第五步：压缩与检索融合**
- RetrievalAwareCompaction
- 保持"项目知识脉络"稳定存在
- 长会话优化

**第六步：外部向量引擎集成**
- MCP 向量检索工具
- 支持 Elasticsearch、Milvus、Qdrant 等
- 可插拔后端架构

## 注意事项

⚠️ **当前版本使用 MockEmbeddingProvider**，仅用于开发和测试，不提供真实的语义搜索能力。

✅ **生产环境建议：**
- 接入真实的嵌入服务（OpenAI Embedding API、通义千问 Embedding 等）
- 或使用本地 ONNX 模型
- 后续版本将提供开箱即用的嵌入模型

## 示例场景

### 场景1：代码理解

```
用户: Agent执行流程是什么样的？

[自动检索相关代码片段]
- AgentExecutor.java:165-215 (Agent主循环)
- AgentExecutor.java:314-328 (单步执行)
- JimiEngine.java:178-194 (Engine入口)

Jimi: 基于检索到的代码，Agent执行流程如下...
```

### 场景2：问题定位

```
用户: 上下文压缩是如何触发的？

[自动检索相关代码片段]
- AgentExecutor.java:194-233 (压缩检查与触发)
- SimpleCompaction.java:77-140 (压缩实现)

Jimi: 上下文压缩在以下情况触发...
```

## 总结

通过本地化向量索引，Jimi 能够：
- ✅ 理解项目代码结构
- ✅ 提供更准确的技术回答
- ✅ 减少幻觉和错误建议
- ✅ 支持大型项目的代码导航

这是 RAG 功能的第一步实现，后续将持续优化检索质量和用户体验。
