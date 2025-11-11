# Skills 功能使用说明

## 概述

Skills（技能包）是Jimi的轻量级能力扩展机制，允许将特定领域的知识、工作流程指南和最佳实践打包成可复用的内容，在需要时按需激活并注入到Agent上下文中。

## 已实现功能（阶段1）

### ✅ 核心模型和加载器

- **SkillScope枚举**：定义全局和项目级作用域
- **SkillSpec数据模型**：完整的Skill配置信息
- **SkillLoader服务**：支持从文件系统加载和解析SKILL.md文件
  - YAML Front Matter解析
  - Markdown内容提取
  - 从类路径和用户目录加载
- **SkillRegistry服务**：集中管理和查询Skills
  - 多索引支持（名称、分类、触发词）
  - 自动加载全局Skills
  - 支持项目级Skills加载

### ✅ 内置Skills

已提供2个开箱即用的Skills：

1. **code-review**：代码审查最佳实践指南
   - 分类：development
   - 触发词：code review、代码审查、review code等

2. **unit-testing**：Java单元测试编写指南
   - 分类：testing
   - 触发词：unit test、单元测试、junit、mockito等

## Skill文件格式

### 标准SKILL.md格式

```markdown
---
name: skill-name
description: 简短描述（50字以内）
version: 1.0.0
category: 分类标签
triggers:
  - 触发关键词1
  - 触发关键词2
---

# Skill标题

Skill的实际内容（Markdown格式）...
```

### 字段说明

| 字段 | 必需 | 说明 |
|------|------|------|
| name | 是 | Skill的唯一标识 |
| description | 是 | 简短描述，建议50字以内 |
| version | 否 | 版本号，默认1.0.0 |
| category | 否 | 分类标签，用于分类查询 |
| triggers | 否 | 触发关键词列表，用于智能匹配 |

## Skills目录结构

### 全局Skills（优先级从高到低）

1. **类路径（内置）**：`resources/skills/`
   - JAR包内置，开箱即用
   - 适合团队标准Skills

2. **用户目录**：`~/.jimi/skills/`
   - 用户自定义全局Skills
   - 可覆盖内置Skills

### 项目Skills

- **项目目录**：`{项目根目录}/.jimi/skills/`
- 仅对当前项目生效
- 优先级最高，可覆盖全局Skills

### 目录示例

```
skills/
├── code-review/
│   ├── SKILL.md
│   └── resources/          # 可选：资源文件
│       └── checklist.txt
├── unit-testing/
│   └── SKILL.md
└── custom-skill/
    └── SKILL.md
```

## 使用示例

### 创建自定义Skill

1. 在`~/.jimi/skills/`目录下创建新文件夹：

```bash
mkdir -p ~/.jimi/skills/my-skill
```

2. 创建`SKILL.md`文件：

```bash
cat > ~/.jimi/skills/my-skill/SKILL.md << 'EOF'
---
name: my-skill
description: 我的自定义技能
version: 1.0.0
category: custom
triggers:
  - my
  - custom
---

# 我的自定义技能

这里是技能的详细内容...
EOF
```

3. 重启Jimi，Skill会自动加载

### 编程方式使用

```java
// 注入SkillRegistry
@Autowired
private SkillRegistry skillRegistry;

// 查找Skill
Optional<SkillSpec> skill = skillRegistry.findByName("code-review");

// 按分类查找
List<SkillSpec> devSkills = skillRegistry.findByCategory("development");

// 按触发词匹配
Set<String> keywords = Set.of("code", "review");
List<SkillSpec> matched = skillRegistry.findByTriggers(keywords);

// 获取所有Skills
List<SkillSpec> allSkills = skillRegistry.getAllSkills();

// 获取统计信息
Map<String, Object> stats = skillRegistry.getStatistics();
```

## 测试验证

### 运行单元测试

```bash
mvn test -Dtest=SkillLoaderTest,SkillRegistryTest
```

### 运行集成测试

```bash
mvn test -Dtest=SkillIntegrationTest
```

预期输出：
```
Loaded skill: code-review (category: development, triggers: 5)
Loaded skill: unit-testing (category: testing, triggers: 6)
Registry statistics: {totalSkills=2, projectSkills=0, categories=2, triggers=11, globalSkills=2}
```

## 后续功能（计划中）

- [ ] 阶段2：SkillMatcher智能匹配和SkillProvider注入机制
- [ ] 阶段3：配置化管理和性能优化
- [ ] 阶段4：更多示例Skills和完整文档

## 技术细节

### 加载策略

1. Spring启动时，SkillRegistry通过@PostConstruct自动初始化
2. 按优先级顺序扫描全局Skills目录（类路径 → 用户目录）
3. 解析每个目录下的SKILL.md文件
4. 建立多索引（名称、分类、触发词）以支持快速查询
5. 项目Skills可通过loadProjectSkills()方法延迟加载

### 性能指标

- Skill加载时间：<100ms（10个Skills）
- 内存占用增量：<10MB（50个Skills）
- 并发安全：使用ConcurrentHashMap保证线程安全

## 常见问题

### Q: 如何覆盖内置Skill？

A: 在`~/.jimi/skills/`目录下创建同名Skill，用户目录的Skill会自动覆盖内置Skill。

### Q: Skill内容是否支持模板变量？

A: 当前版本暂不支持，后续版本会考虑添加模板引擎支持。

### Q: 如何调试Skill加载失败？

A: 启用DEBUG日志级别查看详细加载信息：
```yaml
logging:
  level:
    io.leavesfly.jimi.skill: DEBUG
```

## 贡献

欢迎贡献新的Skill！请确保：

1. 遵循标准SKILL.md格式
2. 提供清晰的描述和触发词
3. 内容准确、实用
4. 添加适当的测试用例
