# 06 · Skills 技能包系统

> Skills 是 Jimi 赋予 Agent 的"**可插拔领域知识**"。它用 Markdown + YAML Front Matter 的简单格式封装专家指令，通过**渐进式披露**（progressive disclosure）让 LLM 在需要时才加载完整内容，**不浪费上下文 token**。

---

## 1. 设计目标与核心机制

| 目标 | 实现手段 |
|------|----------|
| 用 Markdown 写专家知识 | `SKILL.md` = YAML Front Matter（元数据）+ Markdown 正文（正文指令） |
| 摘要永驻系统提示词 | `SkillRegistry.generateSkillsSummary()` 生成 `<available_skills>` 块 → 注入 `${JIMI_SKILLS_SUMMARY}` |
| 完整内容按需加载 | LLM 通过 `Skills(action=invoke, name=xxx)` 工具主动加载 |
| 支持用户与项目两个层级 | `SkillScope` 枚举：`GLOBAL` / `PROJECT`，同名时 PROJECT 覆盖 GLOBAL |
| 可从 GitHub / URL 安装 | `SkillsInstaller.installFromGitHub` / `installFromUrl` |
| 可让 AI 自己沉淀经验 | `Skills(action=create/edit, ...)` 由 Agent 自主写技能 |

**核心机制：渐进式披露（Progressive Disclosure）**

```
启动时                                    运行时
─────                                    ─────
扫描 Skills 目录                          用户发消息
    ↓                                        ↓
SkillRegistry 加载全部 SKILL.md           LLM 收到 System Prompt：
    ↓                                     "有这些 skills 可用，<triggers>..."
generateSkillsSummary()                       ↓
    ↓                                     LLM 判断：当前问题需要 code-review 技能
${JIMI_SKILLS_SUMMARY} 注入 System Prompt      ↓
    ↓                                     主动调 Skills(action=invoke, name=code-review)
LLM 只看到**技能名+描述+触发词**                 ↓
不看完整内容（节省 token）                  返回完整 Markdown 正文 + 技能所在目录路径
                                             ↓
                                        LLM 按技能指令执行
```

> **关键事实**：`ContextManager` 源码里保留了一个已废弃的 `matchAndInjectSkills` 方法，其 JavaDoc 明确写着"**已废弃，改为渐进式披露模式**"。也就是说旧版本曾尝试"关键字匹配后自动注入完整内容"，新版本**完全交给 LLM 自主决策**，框架不做自动注入。

---

## 2. 源码目录结构

```
io.leavesfly.jimi.skill/
├── SkillSpec.java         // 数据模型（9 字段，不可变）+ SkillScope 枚举
├── SkillLoader.java       // @Service：从 classpath / 用户目录 / 项目目录加载 SKILL.md
├── SkillIndex.java        // 包私有：3 维度 ConcurrentHashMap 索引（name/category/triggers）
├── SkillRegistry.java     // @Service：对外注册表，提供 @PostConstruct 自动初始化
└── SkillsInstaller.java   // @Service：从 GitHub / URL 安装技能包

io.leavesfly.jimi.tool.core.SkillsTool.java    // @Prototype Tool：LLM 对外操作入口（6 种 action）
io.leavesfly.jimi.wire.message.SkillsActivated.java  // Wire 消息：技能激活通知 UI
```

---

## 3. `SkillSpec`：技能的数据模型

`io.leavesfly.jimi.skill.SkillSpec` 是不可变的技能描述 POJO。

### 3.1 字段全集

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `name` | `String` | ✓ | 技能唯一标识；重名时后加载覆盖先加载 |
| `description` | `String` | ✓ | 简短描述（建议 50 字以内），进入 `<available_skills>` 摘要 |
| `version` | `String` | （默认 `"1.0.0"`） | 语义化版本号 |
| `category` | `String` | 否 | 分类标签（如 `development`、`testing`），用于按类查询 |
| `triggers` | `List<String>` | 否（默认空列表） | 触发关键词，进入摘要，帮 LLM 判断何时调用 |
| `dependencies` | `List<String>` | 否（默认空列表） | 依赖的其他 Skill 名称（**目前仅作为元数据存储，框架未在加载时做依赖解析**） |
| `content` | `String` | ✓ | SKILL.md 的 Markdown 正文——`invoke` 时返给 LLM |
| `scope` | `SkillScope` | ✓ | `GLOBAL`（从 `resources/skills` 或 `~/.jimi/skills/`）/ `PROJECT`（从项目 `.jimi/skills/`） |
| `skillFilePath` | `Path` | 否 | SKILL.md 绝对路径；用于调试日志和 `invoke` 返回目录信息 |

> ⚠️ `dependencies` 字段**当前仅被序列化/反序列化**，`SkillRegistry.register()` / `SkillLoader` 都没有对它进行拓扑排序或递归加载。也就是说：声明依赖**不会自动拉取其他 Skill**，LLM 需要自行通过 `invoke` 加载多个技能。

### 3.2 `SkillScope` 枚举

```java
public enum SkillScope {
    GLOBAL,   // 从 resources/skills/ 或 ~/.jimi/skills/ 加载
    PROJECT   // 从项目根目录 .jimi/skills/ 加载
}
```

只有这两个值——**不存在"SESSION"或更细粒度的作用域**。

---

## 4. `SKILL.md` 文件格式

### 4.1 文件结构

```markdown
---
name: code-review
description: 代码审查专家指令，专注于可读性、安全性、性能
version: 1.2.0
category: development
triggers:
  - review
  - 代码审查
  - code-review
dependencies: []
---

# Code Review Skill

## 审查清单
1. 命名是否清晰
2. 错误处理是否完整
3. ...

## 操作脚本
如需运行自动检测，请使用 `./scripts/lint.sh`（相对技能目录）
```

- **分隔符**：两行 `---` 包裹 YAML 块，**必须顶格**（`SkillLoader.FRONT_MATTER_PATTERN = "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$"`）
- **YAML 块**：由 `yamlObjectMapper`（Spring 注入的 YAML Jackson）解析为 `Map<String, Object>`，然后按字段填充 `SkillSpec.builder`
- **Markdown 正文**：被整体放入 `SkillSpec.content`
- **缺 Front Matter 的容错**：若文件不符合正则，会打 `WARN` 日志，但仍尝试把全文当作 `content`（`name`/`description` 会是 `null`，这种 skill 实际不可用）

### 4.2 目录布局

每个 Skill 是**一个目录**，以 `SKILL.md` 为入口：

```
~/.jimi/skills/
├── code-review/
│   ├── SKILL.md           ← 主入口
│   ├── scripts/
│   │   └── lint.sh        ← 辅助文件（invoke 时返回目录路径，LLM 可按路径调用）
│   └── templates/
│       └── review.md
└── unit-testing/
    └── SKILL.md
```

**关键**：`invoke` 返回消息里会带上 `**目录路径**: /Users/x/.jimi/skills/code-review`——LLM 可以用这个绝对路径去读取 `scripts/` 或 `templates/` 下的辅助文件。

---

## 5. `SkillLoader`：三来源扫描

`io.leavesfly.jimi.skill.SkillLoader`（`@Service`）负责把 SKILL.md 解析为 `SkillSpec`。

### 5.1 Front Matter 正则

```java
private static final Pattern FRONT_MATTER_PATTERN =
    Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
```

- `DOTALL` 标记让 `.` 可以匹配换行
- 非贪婪 `.*?` 保证 YAML 块最短匹配
- 捕获组 1 = YAML，捕获组 2 = Markdown 正文

### 5.2 三个加载来源

| 来源 | 方法 | 触发时机 | 适用场景 |
|------|------|----------|----------|
| **classpath（JAR 内置）** | `loadSkillsFromClasspath(scope)` | 仅在 JAR 模式下生效（`isRunningFromJar()` 判断） | Jimi 安装包自带 Skills，免配置 |
| **`resources/skills`（开发模式）** | `getGlobalSkillsDirectories()` 的第 1 项 | 非 JAR 模式，从 classloader URL 解析 | 源码开发时直接改 `src/main/resources/skills` |
| **`~/.jimi/skills/`（用户目录）** | `getGlobalSkillsDirectories()` 的第 2 项 | 始终扫描 | 用户安装或自建全局技能 |
| **项目 `.jimi/skills/`** | `loadSkillsFromDirectory(dir, PROJECT)` | 通过 `SkillRegistry.loadProjectSkills(Path)` 显式调用 | 项目绑定的团队知识 |

### 5.3 JAR 模式的限制

源码中有一段**硬编码白名单**：

```java
// SkillLoader.loadSkillsFromClasspath
String[] skillDirs = {"code-review", "unit-testing"}; // 内置的skill目录名
```

也就是说：**从 JAR 内置加载只会扫描 `code-review` 和 `unit-testing` 两个目录**。新增内置技能需要同时修改此数组。非 JAR 模式则通过 `Files.list(directory)` 动态扫描所有子目录，没有白名单限制。

### 5.4 单文件解析流程

```
parseSkillFile(Path)
  ├── 若路径以 "classpath:" 开头 → ClassLoader.getResourceAsStream 读取
  └── 否则 → Files.readString
         ↓
parseSkillContent(text, path)
  ├── FRONT_MATTER_PATTERN.matcher(text).matches()
  │      ↓ YES
  │      ├── yamlContent = group(1)
  │      ├── markdownContent = group(2).trim()
  │      └── yamlObjectMapper.readValue(yaml, Map.class) → 填充 SkillSpec.builder
  │      ↓ NO
  │      └── WARN：missing YAML Front Matter，全文作为 content
  └── builder.content(markdown).build()
```

---

## 6. `SkillIndex`：三维度索引（包私有）

`io.leavesfly.jimi.skill.SkillIndex` **不是 Spring Bean**，是 `SkillRegistry` 内部组合使用的辅助类（package-private）。

### 6.1 三个 ConcurrentHashMap

| 字段 | 类型 | 用途 |
|------|------|------|
| `skillsByName` | `Map<String, SkillSpec>` | 主索引，按 Skill 名查找 |
| `skillsByCategory` | `Map<String, List<SkillSpec>>` | 分类索引，按 `category` 查同类技能 |
| `skillsByTrigger` | `Map<String, List<SkillSpec>>` | 触发词索引，**所有 trigger 都转小写存储** |

### 6.2 `addToIndex` 的覆盖语义

```java
if (skillsByName.containsKey(name)) {
    log.info("Skill '{}' already exists (scope: {}), overriding with new skill (scope: {})", ...);
    removeFromIndex(existing);   // 先清旧索引
}
skillsByName.put(name, skill);
// ... 新增分类和触发词索引
```

所以**同名技能后加载的会完全覆盖先加载的**——`SkillRegistry.initialize()` 按"classpath → `resources/skills` → `~/.jimi/skills/`"顺序加载 GLOBAL，后者覆盖前者；`loadProjectSkills` 再次加载时 PROJECT 覆盖 GLOBAL。

### 6.3 `findByTriggers` 的两阶段匹配

```java
List<SkillSpec> findByTriggers(Set<String> keywords) {
    Set<String> keywordsLower = toLowerCase(keywords);

    // 阶段 1：精确匹配（HashMap O(1)）
    Stream<SkillSpec> exactMatches = keywordsLower.stream()
        .filter(kw -> skillsByTrigger.containsKey(kw))
        .flatMap(kw -> skillsByTrigger.get(kw).stream());

    // 阶段 2：模糊匹配（跳过已精确匹配，避免重复）
    //   - triggerKey.contains(keyword) 或 keyword.contains(triggerKey)
    //   - 也就是说 "review" 也会匹配 "code-review" 和 "review-code"
    Stream<SkillSpec> fuzzyMatches = ...;

    return Stream.concat(exactMatches, fuzzyMatches).distinct().toList();
}
```

**注意**：这个方法只暴露在 `SkillRegistry.findByTriggers(Set<String>)` 中，**不会在主循环里自动调用**——它是给 `SkillsInstaller` 或未来扩展用的查询能力，并不参与渐进式披露的主流程。

---

## 7. `SkillRegistry`：对外注册中心

`io.leavesfly.jimi.skill.SkillRegistry`（`@Service`）是对外主门面，聚合 `SkillLoader` + `SkillIndex`。

### 7.1 启动自动加载（`@PostConstruct`）

```java
@PostConstruct
public void initialize() {
    // 1. 从 classpath（JAR 内置）加载
    for (SkillSpec s : skillLoader.loadSkillsFromClasspath(GLOBAL)) register(s);

    // 2. 从 resources/skills 或 ~/.jimi/skills/ 加载
    for (Path dir : skillLoader.getGlobalSkillsDirectories()) {
        for (SkillSpec s : skillLoader.loadSkillsFromDirectory(dir, GLOBAL)) register(s);
    }

    log.info("SkillRegistry initialized with {} global skills", loadedCount);
}
```

PROJECT 级技能**不自动加载**——需要由 `JimiFactory` 或其他启动逻辑显式调用 `loadProjectSkills(projectSkillsDir)`，目录通常是 `${workDir}/.jimi/skills`。

### 7.2 对外 API 速查

| 方法 | 说明 |
|------|------|
| `register(SkillSpec)` | 注册到索引（同名覆盖） |
| `loadProjectSkills(Path)` | 加载项目级技能 |
| `findByName(String)` | 返回 `Optional<SkillSpec>` |
| `findByCategory(String)` | 返回某类技能列表（不可修改） |
| `findByTriggers(Set<String>)` | 精确 + 模糊匹配触发词 |
| `getAllSkills()` | 返回所有技能（不可修改）|
| `hasSkill(String)` | 存在性检查 |
| `getStatistics()` | 返回 `Map` 含 totalSkills、categoryCount、triggerCount、scope 分布等 |
| `install(Path)` | 从本地路径复制到 `~/.jimi/skills/<name>/` 并注册 |
| `uninstall(String)` | 删除文件 + 从索引移除；**只允许 GLOBAL** |
| `createSkill(name, description, content)` | 在用户目录生成 SKILL.md（只含 name/description/version），注册为 GLOBAL |
| `editSkill(name, newContent)` | 重写 `content`，**保留原 category/triggers 元数据**；只允许 GLOBAL |
| `generateSkillsSummary()` | 生成系统提示词注入用的 `<available_skills>` 块（见 §8） |
| `listAllInfo()` | 返回 `List<SkillInfo>` 给 UI 展示（`name / description / version / category / scope`） |

### 7.3 `createSkill` 与 `editSkill` 的关键约束

| 约束 | 原因 |
|------|------|
| `createSkill`：重名报错 | 避免意外覆盖（想覆盖请先 `remove` 再 `create`，或直接 `edit`） |
| `createSkill` 生成的 YAML **只有 `name/description/version`** | 没有 category/triggers → 新技能不会被触发词摘要推荐，需用户事后手改 SKILL.md |
| `editSkill`：只能编辑 **GLOBAL** | 源码：`if (skill.getScope() != GLOBAL) throw IllegalArgumentException` |
| `editSkill` 重写时会保留原 `category` / `triggers` | 正文改变，元数据不变 |
| `uninstall`：只能卸载 **GLOBAL** | 项目级技能由文件归属项目，不归 Jimi 管理 |

---

## 8. `generateSkillsSummary`：System Prompt 摘要格式

这是**渐进式披露机制的核心产物**——源码生成的文本直接用于 `${JIMI_SKILLS_SUMMARY}` 变量替换。

### 8.1 输出模板（源码原样）

```
<available_skills>
以下是已安装的技能列表。你可以通过 Skills 工具管理和调用技能：
- invoke: 加载技能完整内容（返回完整指令和目录路径）
- install: 安装新技能（支持 GitHub 仓库或压缩包 URL）
- create: 创建新技能 | edit: 编辑现有技能 | remove: 删除技能

## 触发规则
当用户的输入或当前任务涉及以下场景时，你应该主动使用 Skills(action='invoke', name='技能名称') 加载对应技能：
1. 用户输入中包含某个技能的触发词（triggers）
2. 用户的任务与某个技能的描述高度相关
3. 你在执行任务时遇到需要专业指导的领域，且有匹配的技能可用
加载技能后，请严格按照技能内容中的指令执行。

- **code-review**: 代码审查专家指令 [triggers: review, 代码审查, code-review]
- **unit-testing**: 单元测试编写专家 [triggers: test, 单测, unittest]
</available_skills>
```

### 8.2 空列表兜底

源码第一行：

```java
if (skills.isEmpty()) return "";
```

没有任何技能时返回**空字符串**，`${JIMI_SKILLS_SUMMARY}` 会被替换成空，System Prompt 不会多出突兀的空块。

### 8.3 注入链路（跨篇串联）

```
JimiFactory
  └── String skillsSummary = skillRegistry.generateSkillsSummary();
         ↓
  JimiRuntime.Builder.skillsSummary(skillsSummary)
         ↓
  JimiRuntime.buildBuiltinSystemPromptArgs()
         ↓
  BuiltinSystemPromptArgs.jimiSkillsSummary
         ↓
  AgentRegistry.renderSystemPrompt()
         ↓
  StringSubstitutor.put("JIMI_SKILLS_SUMMARY", value)
         ↓
  Agent 的 system prompt 里的 ${JIMI_SKILLS_SUMMARY} 被替换
```

详见 **[02 · 系统架构与核心引擎](02-系统架构与核心引擎.md)** 的 Runtime 装配与 **[03 · Agent 多智能体系统](03-Agent多智能体系统.md)** 的系统提示词章节。

---

## 9. `SkillsTool`：LLM 对外操作入口

`io.leavesfly.jimi.tool.core.SkillsTool`（`@Prototype`）是 LLM 调用的工具实体，`getName() = "Skills"`（注意首字母大写，不是 snake_case）。

### 9.1 6 种 action

| action | 必需参数 | 返回内容 | 说明 |
|--------|----------|----------|------|
| `list` | — | Markdown 列表：所有技能名 + 描述 + category + triggers | 供 LLM 自查"到底有哪些技能" |
| `invoke` | `name` | **完整 Markdown 正文 + 元信息 + 目录路径** | 渐进式披露的"核心动作"——真正把技能指令塞进 context |
| `install` | `repo` | 安装后的技能信息 | `repo` 可以是 `owner/repo`、`owner/repo/skill-name`、或 `https://xxx.zip` |
| `create` | `name`, `content`，可选 `description` | 创建的 SKILL.md 路径 | 重名会报"技能已存在" |
| `edit` | `name`, `content` | `"技能 'xxx' 更新成功！"` | 仅限 GLOBAL；保留原元数据 |
| `remove` | `name` | `"技能 'xxx' 已删除。"` | 仅限 GLOBAL；删文件 + 移索引 |

### 9.2 Invoke 返回格式（源码精确格式）

```
## 技能: <name>

**描述**: <description>

**版本**: <version>

**目录路径**: `/absolute/path/to/skill-dir`

---

<Markdown 正文...>
```

目录路径来自 `skill.getSkillFilePath().getParent()`——LLM 可用这个绝对路径调 `ReadFile` / `Bash` 等工具执行技能里附带的脚本和模板。

### 9.3 错误路径

- `action` 为空 → `ToolResult.error("action 参数是必需的。有效操作：list、invoke、install、create、edit、remove")`
- 未知 `action` → `ToolResult.error("未知操作: ...")`
- `invoke` 找不到技能 → `"技能 'xxx' 未找到。使用 action='list' 查看所有可用技能。"`
- `create` 重名 → `"技能 'xxx' 已存在。使用 action='edit' 修改现有技能，或使用其他名称。"`
- `edit`/`remove` 作用在 PROJECT 技能 → `"只能删除/编辑全局技能"`
- `install` 时 `SkillsInstaller == null` → `"技能安装器不可用"`（`@Autowired(required = false)`）

---

## 10. `SkillsInstaller`：从 GitHub / URL 装包

`io.leavesfly.jimi.skill.SkillsInstaller`（`@Service`）处理远程安装。

### 10.1 `installFromGitHub(repoSpec)`

输入格式支持：

| 输入 | 解析 |
|------|------|
| `owner/repo` | `owner=owner, repo=repo, skillPath=""` → 在仓库根目录找 SKILL.md |
| `owner/repo/skill-name` | `skillPath=skill-name` → 在 `repo-main/skill-name/SKILL.md` 找 |

流程：

```
1. 构造 archive URL: https://github.com/{owner}/{repo}/archive/refs/heads/main.zip
2. 下载到临时目录 /tmp/skill-install-xxx/repo.zip
3. 解压到 /tmp/skill-install-xxx/extracted/
4. findSkillDirectory(extractDir, repo, skillPath)
     └── 在解压根目录（通常是 repo-main/）或子目录 skillPath/ 下找含 SKILL.md 的目录
5. installFromDirectory(skillDir) → 复制到 ~/.jimi/skills/<skill-name>/ 并注册
6. deleteDirectory(tempDir)   // finally 清理
```

> **注意**：源码**硬编码分支名 `main`**——如果目标仓库默认分支是 `master` 或其他，此安装会 404 失败。当前版本不提供回退重试。

### 10.2 `installFromUrl(url, skillName?)`

直接下载任意 `.zip` URL → 解压 → 找 SKILL.md → 复制安装。可选 `skillName` 参数允许在安装时重命名技能目录。

### 10.3 底层工具

- **下载**：`HttpURLConnection` 配合 `HttpClientConstants` 的超时常量
- **解压**：`java.util.zip.ZipInputStream`（标准库，无额外依赖）
- **清理**：`Files.walk(...).sorted(Comparator.reverseOrder()).forEach(Files::delete)` 逆序删除

---

## 11. 与 Wire / UI 的联动

`io.leavesfly.jimi.wire.message.SkillsActivated` 是一个 Wire 消息类型，用于**通知 UI 有哪些 skill 已被激活**。但在渐进式披露模式下，"激活"事件的触发点集中在 `SkillsTool.invoke`——UI 可以监听这类消息做高亮显示（详见 **02 篇 §7** 的 Wire 消息总线）。

---

## 12. 扩展指南

### 12.1 场景 A · 手写一个全局技能

**步骤**：

```bash
mkdir -p ~/.jimi/skills/my-skill
```

创建 `~/.jimi/skills/my-skill/SKILL.md`：

```markdown
---
name: my-skill
description: 我的私人技能，描述要简洁（<50 字）
version: 1.0.0
category: personal
triggers:
  - mykw
  - 我的关键词
---

# My Skill

## 使用说明
当用户询问 X 时，按以下步骤：
1. ...
2. ...
```

重启 Jimi，即可在 `Skills(action=list)` 里看到，LLM 也能 `invoke` 加载。

### 12.2 场景 B · 项目绑定技能

在项目根目录创建 `.jimi/skills/project-review/SKILL.md`：

```markdown
---
name: project-review
description: 本项目专属代码审查规则
version: 1.0.0
category: project
triggers:
  - review this project
---

# Project Review

本项目禁止使用 ...
本项目必须 ...
```

**注意**：项目级技能加载需要 `SkillRegistry.loadProjectSkills(projectSkillsDir)` 被调用。该调用通常由 `JimiFactory`/启动链路处理，若当前版本未自动调用，可通过自定义命令或启动钩子显式调用。

### 12.3 场景 C · 从 GitHub 共享

发布者：

```
my-org/jimi-skills-xxx/
└── SKILL.md
```

安装者在 Jimi 里直接让 Agent 说："帮我安装技能 my-org/jimi-skills-xxx"——LLM 会自动调 `Skills(action=install, repo='my-org/jimi-skills-xxx')`，或手动用 `/skills install my-org/jimi-skills-xxx` 命令（自定义命令见 09 篇）。

### 12.4 场景 D · AI 自动沉淀技能

Agent 在某次会话中总结出一套经验后，可以自主调 `Skills(action=create, name='xxx', content='...')`——用户确认后（若 Approval 启用）技能被固化到 `~/.jimi/skills/`，后续所有会话都能用。

### 12.5 场景 E · 新增 JAR 内置技能

需同时改两处：
1. `src/main/resources/skills/<new-skill>/SKILL.md` 落地文件
2. `SkillLoader.loadSkillsFromClasspath()` 里的白名单数组：

```java
String[] skillDirs = {"code-review", "unit-testing", "<new-skill>"};
```

---

## 13. 故障排查

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| `SkillRegistry initialized with 0 global skills` | 三个来源都没找到 SKILL.md | 确认 `~/.jimi/skills/<name>/SKILL.md` 存在；JAR 模式下检查白名单 |
| 技能加载成功但 LLM 从不调用 | `description`/`triggers` 太模糊或缺失 | 改 SKILL.md 明确 triggers 关键词 |
| `Skills action=edit` 报"只能编辑全局技能" | 目标是 PROJECT | 直接在项目的 `.jimi/skills/` 下手改文件 |
| `Skills action=install` 报"技能安装器不可用" | `SkillsInstaller` 未被 Spring 加载 | 确认依赖网络（GitHub 可达）和 Spring 组件扫描路径 |
| GitHub 安装 404 | 目标仓库默认分支非 `main` | 当前源码仅支持 main 分支；需 fork 重命名分支或手动下载安装 |
| `invoke` 返回"*技能内容为空*" | SKILL.md 只有 Front Matter 没有正文 | 在 `---` 结束线后追加 Markdown 正文 |
| 启动日志 `SKILL.md file missing YAML Front Matter` | 分隔符不是顶格的 `---` 或缺少一侧 | 确保头尾都有 `---`，且每行顶格 |
| Trigger 没法触发 | 当前框架**不自动匹配 triggers 去注入**，完全靠 LLM 自主决定 | 查看 `${JIMI_SKILLS_SUMMARY}` 是否出现在 system prompt；若出现但 LLM 不调用，则是 LLM 推理问题 |

---

## 14. 关键文件速查

| 文件 | 作用 |
|------|------|
| `skill/SkillSpec.java` | 数据模型 + `SkillScope` 枚举 |
| `skill/SkillLoader.java` | 三来源扫描 + Front Matter 解析（17.9 KB） |
| `skill/SkillIndex.java` | 3 维度索引，包私有（10.1 KB） |
| `skill/SkillRegistry.java` | 对外注册中心，`@PostConstruct` 自动加载 GLOBAL（16.7 KB） |
| `skill/SkillsInstaller.java` | GitHub/URL 远程安装（12.9 KB） |
| `tool/core/SkillsTool.java` | LLM 操作入口，6 种 action |
| `wire/message/SkillsActivated.java` | 激活事件 Wire 消息 |
| `core/engine/context/BuiltinSystemPromptArgs.java` | 含 `jimiSkillsSummary` 字段 |
| `core/engine/context/ContextManager.java` | `getSkillsSummary()`；保留已废弃的 `matchAndInjectSkills` |
| `core/agent/AgentRegistry.java` | `${JIMI_SKILLS_SUMMARY}` 变量替换 |
| `core/JimiFactory.java` | 在创建 Runtime 时生成 `skillsSummary` |

---

**[⬅ 上一篇：05 · LLM 接入层与多模型支持](05-LLM接入层与多模型支持.md)** | **[回到首页](Home.md)** | **[下一篇：07 · Hooks 自动化系统 ➡](07-Hooks自动化系统.md)**
