
# Skill 逐步披露（Progressive Disclosure）改造方案

## 一、背景与目标

### 1.1 当前问题

Jimi adk-skill 当前的 Skill 注入流程是**一次性全量注入**：

```
用户输入 → SkillMatcher 关键词匹配 → SkillInjector 全量注入 content → 消耗大量上下文 token
```

核心问题：
- **Token 浪费**：每次匹配到 Skill 就注入完整 SKILL.md 正文，Skill 内容越长浪费越大
- **缺乏自主性**：LLM 无法"看到菜单后自己点菜"，只能被动接收系统塞给它的 Skill 内容
- **扩展性瓶颈**：Skill 数量增多时，全量注入模式会导致上下文爆炸

### 1.2 目标

对齐 Claude Code Skill 的**三层逐步披露**标准：

| 层级 | 加载时机 | 内容 | Token 成本 |
|------|---------|------|-----------|
| **L1：Skill 摘要列表** | 始终加载到系统提示 | 仅 `name` + `description` | 极低（~50 tokens/skill） |
| **L2：Skill 正文** | LLM 判断相关时按需加载 | SKILL.md 完整指令内容 | 中等（按需） |
| **L3：辅助文件** | 执行时按需读取 | `resources/`、`scripts/` 下的文件 | 按需 |

### 1.3 设计原则

1. **向后兼容**：通过 `SkillConfig.disclosureMode` 配置切换，默认保持现有行为（`EAGER`），可选启用逐步披露（`PROGRESSIVE`）
2. **最小侵入**：复用现有 `SkillRegistry`、`SkillLoader`、`SkillSpec` 等核心模型，不改变数据结构
3. **LLM 驱动**：第二层加载由 LLM 通过工具调用主动触发，而非系统自动注入
4. **渐进式改造**：分三个阶段实施，每个阶段独立可交付

---

## 二、整体架构

### 2.1 改造前后对比

**改造前（EAGER 模式）：**
```
用户输入 → SkillMatcher → 匹配到 Skills → SkillInjector 全量注入 content → LLM 收到完整内容
```

**改造后（PROGRESSIVE 模式）：**
```
启动时 → SkillSummaryBuilder 生成 <available_skills> → 注入系统提示（L1）
                                                          ↓
用户输入 → LLM 根据 <available_skills> 判断需要哪个 Skill
                                                          ↓
         → LLM 调用 ReadSkill 工具 → 加载 SKILL.md 正文（L2）
                                                          ↓
         → LLM 调用 ReadFile/Bash 工具 → 读取辅助文件/执行脚本（L3）
```

### 2.2 新增/改造组件一览

| 组件 | 类型 | 模块 | 说明 |
|------|------|------|------|
| `SkillSummaryBuilder` | **新增** | adk-skill | 生成 `<available_skills>` XML 摘要 |
| `ReadSkillTool` | **新增** | adk-skill | LLM 可调用的工具，按需加载 Skill 正文 |
| `ReadSkillResourceTool` | **新增** | adk-skill | LLM 可调用的工具，按需读取 Skill 辅助文件 |
| `SkillToolProvider` | **新增** | adk-skill | 工具提供者 SPI 实现，注册 Skill 相关工具 |
| `DisclosureMode` | **新增** | adk-skill | 枚举：`EAGER` / `PROGRESSIVE` |
| `SkillConfig` | **改造** | adk-skill | 新增 `disclosureMode` 配置项 |
| `SkillService` | **改造** | adk-api | 新增 `getSkillsSummary()` 和 `getSkillContent()` 方法 |
| `SkillDescriptor` | **改造** | adk-api | 新增 `hasResources`、`hasScripts` 字段 |
| `DefaultSkillService` | **改造** | adk-skill | 实现新增的接口方法 |
| `SkillInjector` | **改造** | adk-skill | 支持按模式切换注入策略 |

---

## 三、详细设计

### 3.1 L1：Skill 摘要列表（始终加载）

#### 3.1.1 新增 `SkillSummaryBuilder`

**位置**：`jimi2/jimi-adk/adk-skill/src/main/java/io/leavesfly/jimi/adk/skill/SkillSummaryBuilder.java`

**职责**：将 SkillRegistry 中所有已注册的 Skills 生成 `<available_skills>` XML 摘要，注入系统提示。

```java
public class SkillSummaryBuilder {

    private final SkillRegistry skillRegistry;

    /**
     * 生成 <available_skills> 摘要文本
     * 仅包含 name + description，极低 token 成本
     */
    public String buildSummary() {
        List<SkillSpec> allSkills = skillRegistry.getAllSkills();
        if (allSkills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<available_skills>\n");

        for (SkillSpec skill : allSkills) {
            sb.append("<skill>\n");
            sb.append("  <name>").append(skill.getName()).append("</name>\n");
            sb.append("  <description>").append(skill.getDescription()).append("</description>\n");

            // 提示 LLM 该 Skill 有哪些可用资源
            if (skill.getResourcesPath() != null) {
                sb.append("  <has_resources>true</has_resources>\n");
            }
            if (skill.getScriptsPath() != null) {
                sb.append("  <has_scripts>true</has_scripts>\n");
            }

            sb.append("</skill>\n");
        }

        sb.append("</available_skills>\n\n");
        sb.append("When a user's task matches one of the above skills, ");
        sb.append("use the `ReadSkill` tool to load its full instructions before proceeding.\n");

        return sb.toString();
    }
}
```

#### 3.1.2 系统提示注入

摘要文本通过 `SkillService.getSkillsSummary()` 获取，由上层（如 `AgentExecutor` 或系统提示构建器）在构建系统提示时追加到末尾。

**注入位置**：系统提示的末尾，作为 LLM 的"菜单"。

**示例输出**：
```xml
<available_skills>
<skill>
  <name>code-review</name>
  <description>代码审查最佳实践指南，涵盖安全性、性能、可读性等维度</description>
</skill>
<skill>
  <name>unit-testing</name>
  <description>Java单元测试编写指南，基于JUnit5和Mockito框架</description>
  <has_scripts>true</has_scripts>
</skill>
</available_skills>

When a user's task matches one of the above skills,
use the `ReadSkill` tool to load its full instructions before proceeding.
```

### 3.2 L2：Skill 正文按需加载

#### 3.2.1 新增 `ReadSkillTool`

**位置**：`jimi2/jimi-adk/adk-skill/src/main/java/io/leavesfly/jimi/adk/skill/tool/ReadSkillTool.java`

**职责**：LLM 通过此工具按需加载指定 Skill 的完整指令内容。

```java
/**
 * ReadSkill 工具 — 逐步披露第二层
 *
 * LLM 在看到 <available_skills> 摘要后，判断某个 Skill 与当前任务相关时，
 * 主动调用此工具加载该 Skill 的完整指令内容。
 */
public class ReadSkillTool extends AbstractTool<ReadSkillTool.Params> {

    private final SkillRegistry skillRegistry;
    private final SkillInjector skillInjector;

    public ReadSkillTool(SkillRegistry skillRegistry, SkillInjector skillInjector) {
        super("ReadSkill",
              "Load the full instructions of a skill by name. " +
              "Use this when you identify a relevant skill from <available_skills>.",
              Params.class);
        this.skillRegistry = skillRegistry;
        this.skillInjector = skillInjector;
    }

    @Override
    public ToolResult execute(Params params, ToolContext context) {
        String skillName = params.getSkillName();

        Optional<SkillSpec> skillOpt = skillRegistry.findByName(skillName);
        if (skillOpt.isEmpty()) {
            return ToolResult.error("Skill not found: " + skillName +
                ". Available skills: " + String.join(", ", skillRegistry.getAllSkillNames()));
        }

        SkillSpec skill = skillOpt.get();

        // 检查是否已激活（去重）
        if (skillInjector.isSkillActive(skillName)) {
            return ToolResult.success("Skill '" + skillName + "' is already active.");
        }

        // 构建响应：Skill 正文 + 可用资源提示
        StringBuilder response = new StringBuilder();
        response.append("## Skill: ").append(skill.getName()).append("\n\n");

        if (skill.getContent() != null && !skill.getContent().isEmpty()) {
            response.append(skill.getContent()).append("\n\n");
        }

        // 提示可用的辅助资源（L3 入口）
        if (skill.getResourcesPath() != null) {
            response.append("**Available resources**: This skill has reference files in `")
                    .append(skill.getResourcesPath().getFileName())
                    .append("`. Use `ReadSkillResource` tool to read them if needed.\n\n");
        }
        if (skill.getScriptsPath() != null) {
            response.append("**Available scripts**: This skill has executable scripts in `")
                    .append(skill.getScriptsPath().getFileName())
                    .append("`. Use Bash tool to execute them if needed.\n\n");
        }

        // 标记为已激活
        skillInjector.markAsActive(skill);

        return ToolResult.success(response.toString());
    }

    @Data
    public static class Params {
        /** 要加载的 Skill 名称 */
        private String skillName;
    }
}
```

#### 3.2.2 工具参数 Schema

```json
{
  "name": "ReadSkill",
  "description": "Load the full instructions of a skill by name. Use this when you identify a relevant skill from <available_skills>.",
  "parameters": {
    "type": "object",
    "properties": {
      "skillName": {
        "type": "string",
        "description": "The name of the skill to load (from <available_skills>)"
      }
    },
    "required": ["skillName"]
  }
}
```

### 3.3 L3：辅助文件按需读取

#### 3.3.1 新增 `ReadSkillResourceTool`

**位置**：`jimi2/jimi-adk/adk-skill/src/main/java/io/leavesfly/jimi/adk/skill/tool/ReadSkillResourceTool.java`

**职责**：LLM 通过此工具按需读取 Skill 的辅助资源文件。

```java
/**
 * ReadSkillResource 工具 — 逐步披露第三层
 *
 * LLM 在加载 Skill 正文后，如果需要参考辅助文件（如 checklist、模板等），
 * 主动调用此工具读取。
 */
public class ReadSkillResourceTool extends AbstractTool<ReadSkillResourceTool.Params> {

    private final SkillRegistry skillRegistry;

    public ReadSkillResourceTool(SkillRegistry skillRegistry) {
        super("ReadSkillResource",
              "Read a resource file from a skill's resources directory. " +
              "Use this when a skill mentions available reference files.",
              Params.class);
        this.skillRegistry = skillRegistry;
    }

    @Override
    public ToolResult execute(Params params, ToolContext context) {
        String skillName = params.getSkillName();
        String fileName = params.getFileName();

        Optional<SkillSpec> skillOpt = skillRegistry.findByName(skillName);
        if (skillOpt.isEmpty()) {
            return ToolResult.error("Skill not found: " + skillName);
        }

        SkillSpec skill = skillOpt.get();
        if (skill.getResourcesPath() == null) {
            return ToolResult.error("Skill '" + skillName + "' has no resources directory.");
        }

        Path resourceFile = skill.getResourcesPath().resolve(fileName);

        // 安全检查：防止路径穿越
        if (!resourceFile.normalize().startsWith(skill.getResourcesPath().normalize())) {
            return ToolResult.error("Invalid file path: path traversal detected.");
        }

        if (!Files.exists(resourceFile)) {
            // 列出可用文件
            try {
                String availableFiles = Files.list(skill.getResourcesPath())
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", "));
                return ToolResult.error("File not found: " + fileName +
                    ". Available files: " + availableFiles);
            } catch (IOException e) {
                return ToolResult.error("File not found: " + fileName);
            }
        }

        try {
            String content = Files.readString(resourceFile);
            return ToolResult.success(content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    @Data
    public static class Params {
        /** Skill 名称 */
        private String skillName;
        /** 要读取的资源文件名 */
        private String fileName;
    }
}
```

### 3.4 工具注册

#### 3.4.1 新增 `SkillToolProvider`

**位置**：`jimi2/jimi-adk/adk-skill/src/main/java/io/leavesfly/jimi/adk/skill/tool/SkillToolProvider.java`

**职责**：当 `disclosureMode=PROGRESSIVE` 时，注册 `ReadSkill` 和 `ReadSkillResource` 工具。

```java
/**
 * Skill 工具提供者
 *
 * 仅在 PROGRESSIVE 模式下注册 ReadSkill 和 ReadSkillResource 工具，
 * 使 LLM 能够按需加载 Skill 内容和辅助资源。
 */
public class SkillToolProvider implements ToolProvider {

    private final SkillRegistry skillRegistry;
    private final SkillInjector skillInjector;
    private final SkillConfig skillConfig;

    @Override
    public boolean supports(AgentSpec agentSpec, Runtime runtime) {
        // 仅在 PROGRESSIVE 模式下提供工具
        return skillConfig != null
            && skillConfig.getDisclosureMode() == DisclosureMode.PROGRESSIVE;
    }

    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, Runtime runtime) {
        return List.of(
            new ReadSkillTool(skillRegistry, skillInjector),
            new ReadSkillResourceTool(skillRegistry)
        );
    }

    @Override
    public int getOrder() {
        return 150; // 在标准工具之后、MetaTool 之前
    }
}
```

### 3.5 配置改造

#### 3.5.1 新增 `DisclosureMode` 枚举

**位置**：`jimi2/jimi-adk/adk-skill/src/main/java/io/leavesfly/jimi/adk/skill/DisclosureMode.java`

```java
/**
 * Skill 披露模式
 */
public enum DisclosureMode {
    /** 即时模式（现有行为）：匹配即全量注入 content */
    EAGER,
    /** 逐步披露模式（Claude Code 标准）：L1 摘要 → L2 按需加载 → L3 按需读取 */
    PROGRESSIVE
}
```

#### 3.5.2 改造 `SkillConfig`

```java
@Data
public class SkillConfig {
    // ... 现有字段保持不变 ...

    /** 披露模式：EAGER（默认，向后兼容）或 PROGRESSIVE */
    private DisclosureMode disclosureMode = DisclosureMode.EAGER;
}
```

**配置示例**（application.yml）：
```yaml
jimi:
  skills:
    enabled: true
    disclosure-mode: PROGRESSIVE  # 启用逐步披露
```

### 3.6 接口改造

#### 3.6.1 改造 `SkillService`

新增两个方法：

```java
public interface SkillService {
    // ... 现有方法保持不变 ...

    /**
     * 获取 Skills 摘要文本（用于系统提示注入）
     * 仅在 PROGRESSIVE 模式下有意义
     *
     * @return <available_skills> XML 摘要文本，无 Skill 时返回空字符串
     */
    String getSkillsSummary();

    /**
     * 获取指定 Skill 的完整内容（用于 ReadSkill 工具）
     *
     * @param skillName Skill 名称
     * @return Skill 完整内容，未找到时返回 null
     */
    String getSkillContent(String skillName);

    /**
     * 获取当前的披露模式
     */
    DisclosureMode getDisclosureMode();
}
```

#### 3.6.2 改造 `SkillDescriptor`

新增资源可用性标识：

```java
@Data
@Builder
public class SkillDescriptor {
    // ... 现有字段保持不变 ...

    /** 是否有辅助资源文件 */
    @Builder.Default
    private boolean hasResources = false;

    /** 是否有可执行脚本 */
    @Builder.Default
    private boolean hasScripts = false;
}
```

### 3.7 改造 `SkillInjector`

新增 `markAsActive` 方法（供 `ReadSkillTool` 调用），并根据模式切换注入策略：

```java
public class SkillInjector {
    // ... 现有字段保持不变 ...

    /**
     * 标记 Skill 为已激活（不注入内容，仅记录状态）
     * 供 ReadSkillTool 在 PROGRESSIVE 模式下调用
     */
    public void markAsActive(SkillSpec skill) {
        if (skill != null && skill.getName() != null) {
            activeSkillNames.add(skill.getName());
            activeSkills.add(skill);
            log.info("Marked skill as active: {}", skill.getName());
        }
    }

    // formatSkillsForInjection() 保持不变，仅在 EAGER 模式下被调用
}
```

### 3.8 改造 `DefaultSkillService`

```java
public class DefaultSkillService implements SkillService {
    // ... 现有字段 ...
    private final SkillSummaryBuilder summaryBuilder;

    @Override
    public String matchAndFormat(String inputText, Path workDir) {
        if (!initialized) {
            return null;
        }

        // 根据模式选择不同的策略
        if (getDisclosureMode() == DisclosureMode.PROGRESSIVE) {
            // PROGRESSIVE 模式：不主动注入，由 LLM 通过 ReadSkill 工具按需加载
            // 这里可以做一些辅助匹配（如 SkillMatcher 的结果用于日志/监控）
            List<SkillSpec> matched = skillMatcher.matchFromInput(inputText);
            if (!matched.isEmpty()) {
                log.info("[PROGRESSIVE] Potential skills for input: {}",
                    matched.stream().map(SkillSpec::getName).collect(Collectors.joining(", ")));
            }
            return null; // 不注入，等 LLM 自己决定
        }

        // EAGER 模式：保持现有行为
        List<SkillSpec> matched = skillMatcher.matchFromInput(inputText);
        if (matched.isEmpty()) {
            return null;
        }
        return skillInjector.formatSkillsForInjection(matched, workDir);
    }

    @Override
    public String getSkillsSummary() {
        if (!initialized) {
            return "";
        }
        return summaryBuilder.buildSummary();
    }

    @Override
    public String getSkillContent(String skillName) {
        return skillRegistry.findByName(skillName)
            .map(SkillSpec::getContent)
            .orElse(null);
    }

    @Override
    public DisclosureMode getDisclosureMode() {
        if (skillConfig != null) {
            return skillConfig.getDisclosureMode();
        }
        return DisclosureMode.EAGER;
    }
}
```

---

## 四、数据流图

### 4.1 PROGRESSIVE 模式完整流程

```
┌─────────────────────────────────────────────────────────────────┐
│                        系统启动                                  │
│                                                                 │
│  SkillRegistry.init()                                           │
│       ↓                                                         │
│  SkillSummaryBuilder.buildSummary()                             │
│       ↓                                                         │
│  生成 <available_skills> 摘要 ──→ 注入系统提示末尾 (L1)          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      用户对话                                    │
│                                                                 │
│  用户: "帮我做一下代码审查"                                       │
│       ↓                                                         │
│  LLM 看到系统提示中的 <available_skills>                         │
│  LLM 判断: "code-review" skill 与任务相关                        │
│       ↓                                                         │
│  LLM 调用: ReadSkill(skillName="code-review")  (L2)             │
│       ↓                                                         │
│  ReadSkillTool 返回 SKILL.md 完整正文                            │
│  + 提示: "此 Skill 有 resources/checklist.txt 可用"              │
│       ↓                                                         │
│  LLM 根据正文指导执行代码审查                                     │
│       ↓                                                         │
│  (可选) LLM 调用: ReadSkillResource(                             │
│           skillName="code-review",                               │
│           fileName="checklist.txt")  (L3)                        │
│       ↓                                                         │
│  LLM 获取检查清单，完成审查                                      │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 Token 节省估算

假设有 10 个 Skills，每个 SKILL.md 正文平均 2000 tokens：

| 模式 | 系统提示 token 成本 | 每轮对话额外成本 |
|------|-------------------|----------------|
| **EAGER** | ~500 (基础) + 20000 (10 × 2000) = **20500** | 0（已全量注入） |
| **PROGRESSIVE** | ~500 (基础) + 500 (摘要) = **1000** | ~2000（仅加载 1 个相关 Skill） |

**节省比例**：首轮对话节省约 **95%** 的 Skill 相关 token。

---

## 五、实施计划

### 阶段一：核心框架（预计 2-3 天）

1. 新增 `DisclosureMode` 枚举
2. 改造 `SkillConfig`：新增 `disclosureMode` 字段
3. 新增 `SkillSummaryBuilder`
4. 改造 `SkillService` 接口：新增 `getSkillsSummary()`、`getSkillContent()`、`getDisclosureMode()`
5. 改造 `SkillDescriptor`：新增 `hasResources`、`hasScripts`
6. 改造 `DefaultSkillService`：实现新接口方法
7. 改造 `SkillInjector`：新增 `markAsActive()` 方法

### 阶段二：工具注册（预计 1-2 天）

1. 新增 `ReadSkillTool`
2. 新增 `ReadSkillResourceTool`
3. 新增 `SkillToolProvider`（SPI 注册）
4. 系统提示构建器集成 `getSkillsSummary()`

### 阶段三：测试与优化（预计 1-2 天）

1. 单元测试：`SkillSummaryBuilder`、`ReadSkillTool`、`ReadSkillResourceTool`
2. 集成测试：端到端验证 PROGRESSIVE 模式流程
3. 配置文档更新

### 阶段四（可选增强）

1. **混合模式**：支持 `HYBRID` 模式 — 高优先级 Skill 自动注入，其余走逐步披露
2. **LLM 辅助匹配**：在 L1 阶段让 LLM 参与 Skill 选择，替代纯关键词匹配
3. **Skill 预热**：基于历史对话模式，预测并预加载可能需要的 Skills

---

## 六、兼容性与风险

### 6.1 向后兼容

- 默认 `disclosureMode=EAGER`，现有行为完全不变
- 所有现有 SKILL.md 文件无需修改
- 现有 API 方法签名不变，仅新增方法

### 6.2 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| LLM 不调用 ReadSkill 工具 | Skill 内容未被加载 | 在系统提示中明确指导 LLM 使用工具；SkillMatcher 可作为 fallback 辅助提醒 |
| LLM 加载了不相关的 Skill | 浪费 token | ReadSkill 工具有去重机制；description 写得越好，LLM 判断越准 |
| PROGRESSIVE 模式下 SkillMatcher 闲置 | 代码浪费 | SkillMatcher 可用于日志/监控/辅助提醒，不会完全闲置 |

---

## 七、文件变更清单

```
jimi2/jimi-adk/adk-skill/src/main/java/io/leavesfly/jimi/adk/skill/
├── DisclosureMode.java                          [新增] 披露模式枚举
├── SkillConfig.java                             [改造] 新增 disclosureMode 字段
├── SkillSummaryBuilder.java                     [新增] 摘要生成器
├── SkillInjector.java                           [改造] 新增 markAsActive()
├── DefaultSkillService.java                     [改造] 实现新接口方法
└── tool/
    ├── ReadSkillTool.java                       [新增] L2 按需加载工具
    ├── ReadSkillResourceTool.java               [新增] L3 辅助文件读取工具
    └── SkillToolProvider.java                   [新增] 工具注册 SPI

jimi2/jimi-adk/adk-api/src/main/java/io/leavesfly/jimi/adk/api/skill/
├── SkillService.java                            [改造] 新增 3 个方法
└── SkillDescriptor.java                         [改造] 新增 2 个字段
```
