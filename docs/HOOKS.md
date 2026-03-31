# Jimi Hooks 系统完整指南

## 📖 目录

1. [简介](#简介)
2. [Hook 类型](#hook-类型)
3. [配置文件结构](#配置文件结构)
4. [触发配置](#触发配置)
5. [执行配置](#执行配置)
6. [条件配置](#条件配置)
7. [变量替换](#变量替换)
8. [JSON 输入与输出](#json-输入与输出)
9. [Exit Code 决策控制](#exit-code-决策控制)
10. [异步执行](#异步执行)
11. [实战示例](#实战示例)
12. [最佳实践](#最佳实践)
13. [故障排查](#故障排查)

---

## 简介

Hooks 系统是 Jimi 的事件驱动自动化机制,允许在关键节点自动执行自定义操作。本系统完全对齐 Claude Code 标准,提供强大的扩展能力。

### 特性

- ✅ **事件驱动**: 在特定事件自动触发
- ✅ **灵活配置**: 支持 YAML 配置文件
- ✅ **条件执行**: 支持多种执行条件
- ✅ **变量替换**: 丰富的内置变量
- ✅ **优先级控制**: 按优先级顺序执行
- ✅ **热加载**: 无需重启即可加载新 Hook
- ✅ **Matcher 机制**: 支持正则表达式精确匹配
- ✅ **JSON 通信**: 标准化的输入输出格式
- ✅ **决策控制**: 通过 Exit Code 控制执行流程
- ✅ **异步执行**: 支持后台异步任务

### 配置文件位置

Hook 配置文件支持三层加载:

```
1. 类路径 (resources/hooks/)          - 内置示例
2. 用户主目录 (~/.jimi/hooks/)        - 全局 Hooks
3. 项目目录 (<project>/.jimi/hooks/)  - 项目特定 Hooks
```

优先级: **项目 > 用户 > 类路径**

---

## Hook 类型

### 1. 工具调用 Hooks

#### PRE_TOOL_USE
**触发时机**: 工具执行前  
**对齐**: Claude Code PreToolUse  
**用途**: 权限检查、参数验证、审批

```yaml
trigger:
  type: "PRE_TOOL_USE"
  tools:
    - "BashTool"
    - "WriteFile"
```

#### POST_TOOL_USE
**触发时机**: 工具执行后  
**对齐**: Claude Code PostToolUse  
**用途**: 自动格式化、提交、清理

```yaml
trigger:
  type: "POST_TOOL_USE"
  tools:
    - "WriteFile"
  file_patterns:
    - "*.java"
```

#### POST_TOOL_USE_FAILURE
**触发时机**: 工具调用失败后  
**对齐**: Claude Code PostToolUseFailure  
**用途**: 错误恢复、回滚操作、通知

```yaml
trigger:
  type: "POST_TOOL_USE_FAILURE"
  tools:
    - "BashTool"
```

### 2. 用户输入 Hooks

#### USER_PROMPT_SUBMIT
**触发时机**: 用户输入提交时  
**对齐**: Claude Code UserPromptSubmit  
**用途**: 输入预处理、上下文准备、自动补全

```yaml
trigger:
  type: "USER_PROMPT_SUBMIT"
```

### 3. Agent 切换 Hooks (Jimi 扩展)

#### PRE_AGENT_SWITCH
**触发时机**: Agent 切换前  
**用途**: 保存状态、清理资源

```yaml
trigger:
  type: "PRE_AGENT_SWITCH"
  agentName: "Code-Agent"
```

#### POST_AGENT_SWITCH
**触发时机**: Agent 切换后  
**用途**: 加载配置、初始化环境

```yaml
trigger:
  type: "POST_AGENT_SWITCH"
  agentName: "Code-Agent"
```

### 4. 通知与停止 Hooks

#### NOTIFICATION
**触发时机**: 通知事件  
**对齐**: Claude Code Notification  
**用途**: 处理系统通知、进度更新

```yaml
trigger:
  type: "NOTIFICATION"
```

#### STOP
**触发时机**: 停止事件  
**对齐**: Claude Code Stop  
**用途**: 清理资源、保存状态

```yaml
trigger:
  type: "STOP"
```

#### SUBAGENT_STOP
**触发时机**: 子代理停止事件  
**对齐**: Claude Code SubagentStop  
**用途**: 子代理资源回收

```yaml
trigger:
  type: "SUBAGENT_STOP"
```

### 5. 会话生命周期 Hooks

#### SESSION_START
**触发时机**: Jimi 会话启动时  
**对齐**: Claude Code SessionStart  
**用途**: 环境初始化、欢迎信息

```yaml
trigger:
  type: "SESSION_START"
```

#### SESSION_END
**触发时机**: Jimi 会话结束时  
**对齐**: Claude Code SessionEnd  
**用途**: 资源清理、状态保存

```yaml
trigger:
  type: "SESSION_END"
```

### 6. 错误处理 Hooks (Jimi 扩展)

#### ON_ERROR
**触发时机**: 系统错误发生时  
**用途**: 错误处理、日志记录、自动修复

```yaml
trigger:
  type: "ON_ERROR"
  errorPattern: ".*compilation error.*"
```

---

## 配置文件结构

完整的 Hook 配置示例:

```yaml
# Hook 基本信息
name: "auto-format"
description: "自动格式化代码"
enabled: true
priority: 10

# 触发配置
trigger:
  type: "POST_TOOL_USE"
  tools:
    - "WriteFile"
  file_patterns:
    - "*.java"
    - "*.xml"
  matcher:
    pattern: ".*\\.java$"

# 执行配置
execution:
  type: "script"
  async: true
  script: |
    #!/bin/bash
    for file in ${MODIFIED_FILES}; do
      echo "格式化: $file"
    done
  workingDir: "${JIMI_WORK_DIR}"
  timeout: 30

# 执行条件
conditions:
  - type: "env_var"
    var: "AUTO_FORMAT"
    value: "true"
```

### 字段说明

| 字段 | 必需 | 说明 |
|------|------|------|
| `name` | ✅ | Hook 名称,全局唯一 |
| `description` | ✅ | Hook 描述 |
| `enabled` | ❌ | 是否启用,默认 true |
| `priority` | ❌ | 优先级,数值越大越先执行,默认 0 |
| `trigger` | ✅ | 触发配置 |
| `execution` | ✅ | 执行配置 |
| `conditions` | ❌ | 执行条件列表 |

---

## 触发配置

### 工具名称过滤

```yaml
trigger:
  type: "POST_TOOL_USE"
  tools:
    - "WriteFile"
    - "StrReplaceFile"
    - "BashTool"
```

- 为空: 匹配所有工具
- 指定工具: 仅匹配列表中的工具

### 文件模式过滤

支持 glob 模式:

```yaml
trigger:
  type: "POST_TOOL_USE"
  file_patterns:
    - "*.java"           # 所有 Java 文件
    - "*.xml"            # 所有 XML 文件
    - "src/**/*.java"    # src 目录下所有 Java 文件
    - "pom.xml"          # 特定文件
```

### Matcher 正则匹配机制

Matcher 提供更强大的正则表达式匹配能力,对齐 Claude Code 标准:

```yaml
trigger:
  type: "USER_PROMPT_SUBMIT"
  matcher:
    pattern: ".*测试.*"  # 匹配包含"测试"的输入
    caseSensitive: false
```

**Matcher 字段说明**:

| 字段 | 必需 | 说明 |
|------|------|------|
| `pattern` | ✅ | 正则表达式模式 |
| `caseSensitive` | ❌ | 是否区分大小写,默认 false |

**Matcher 使用场景**:

1. **用户输入过滤**:
```yaml
trigger:
  type: "USER_PROMPT_SUBMIT"
  matcher:
    pattern: ".*(部署|deploy).*"
```

2. **工具参数过滤**:
```yaml
trigger:
  type: "PRE_TOOL_USE"
  tools:
    - "BashTool"
  matcher:
    pattern: ".*rm -rf.*"
```

3. **错误消息过滤**:
```yaml
trigger:
  type: "ON_ERROR"
  matcher:
    pattern: ".*OutOfMemoryError.*"
```

### Agent 名称过滤

```yaml
trigger:
  type: "POST_AGENT_SWITCH"
  agentName: "Code-Agent"  # 仅匹配切换到 Code-Agent
```

### 错误模式过滤

支持正则表达式:

```yaml
trigger:
  type: "ON_ERROR"
  errorPattern: ".*compilation error.*"
```

---

## 执行配置

### 1. Script 类型

执行 Shell 脚本:

#### 内联脚本

```yaml
execution:
  type: "script"
  async: true
  script: |
    #!/bin/bash
    set -e
    echo "执行脚本"
    mvn clean install
  workingDir: "${JIMI_WORK_DIR}"
  timeout: 300
  environment:
    CUSTOM_VAR: "value"
```

#### 外部脚本文件

```yaml
execution:
  type: "script"
  scriptFile: "/path/to/script.sh"
  workingDir: "${JIMI_WORK_DIR}"
  timeout: 60
```

### 2. Agent 类型

委托给 Agent 执行:

```yaml
execution:
  type: "agent"
  agent: "Code-Agent"
  task: "分析错误并自动修复"
```

### 3. Composite 类型

组合多个步骤:

```yaml
execution:
  type: "composite"
  steps:
    - type: "script"
      script: "mvn clean"
      description: "清理"
    - type: "script"
      script: "mvn test"
      description: "测试"
    - type: "script"
      script: "mvn package"
      description: "打包"
      continueOnFailure: false
```

---

## 条件配置

### 1. 环境变量条件

```yaml
conditions:
  - type: "env_var"
    var: "JIMI_AUTO_FORMAT"
    value: "true"  # 可选,不指定则仅检查存在性
    description: "启用自动格式化"
```

### 2. 文件存在条件

```yaml
conditions:
  - type: "file_exists"
    path: "pom.xml"
    description: "必须是 Maven 项目"
```

支持变量替换:

```yaml
conditions:
  - type: "file_exists"
    path: "${JIMI_WORK_DIR}/.git"
```

### 3. 脚本条件

```yaml
conditions:
  - type: "script"
    script: |
      #!/bin/bash
      # 检查是否是工作日
      day=$(date +%u)
      if [ $day -lt 6 ]; then
        exit 0  # 满足条件
      else
        exit 1  # 不满足条件
      fi
    description: "仅在工作日执行"
```

### 4. 工具结果包含条件

```yaml
conditions:
  - type: "tool_result_contains"
    pattern: ".*git commit.*"
    description: "工具结果包含 git commit"
```

---

## 变量替换

Hook 支持丰富的内置变量:

### 通用变量

```bash
${JIMI_WORK_DIR}    # Jimi 工作目录
${HOME}             # 用户主目录
```

### 工具相关变量

```bash
${TOOL_NAME}        # 触发的工具名称
${TOOL_RESULT}      # 工具执行结果
${MODIFIED_FILES}   # 受影响的文件列表(空格分隔)
${MODIFIED_FILE}    # 第一个受影响的文件
```

### Agent 相关变量

```bash
${AGENT_NAME}       # 当前 Agent 名称
${CURRENT_AGENT}    # 当前 Agent 名称(别名)
${PREVIOUS_AGENT}   # 前一个 Agent 名称
```

### 用户输入相关变量

```bash
${USER_INPUT}               # 用户输入内容
${LAST_ASSISTANT_MESSAGE}   # 最后一条助手消息
```

### 通知相关变量

```bash
${NOTIFICATION_MESSAGE}     # 通知消息内容
```

### 错误相关变量

```bash
${ERROR_MESSAGE}    # 错误消息
```

### 使用示例

```yaml
execution:
  type: "script"
  script: |
    echo "工作目录: ${JIMI_WORK_DIR}"
    echo "工具名称: ${TOOL_NAME}"
    echo "修改文件: ${MODIFIED_FILES}"
    
    for file in ${MODIFIED_FILES}; do
      echo "处理: $file"
    done
```

---

## JSON 输入与输出

### JSON Stdin 输入

Hook 脚本通过 stdin 接收 JSON 格式的上下文数据,这是对齐 Claude Code 的标准做法。

**输入 JSON 结构示例**:

```json
{
  "hookType": "PRE_TOOL_USE",
  "toolName": "BashTool",
  "toolInput": {
    "command": "ls -la"
  },
  "context": {
    "workspace": "/path/to/project",
    "userInput": "列出文件",
    "agent": "Code-Agent"
  },
  "timestamp": "2026-04-01T02:23:00Z"
}
```

**脚本读取 JSON 示例**:

```bash
#!/bin/bash
# 读取 JSON 输入
INPUT=$(cat)

# 使用 jq 解析
TOOL_NAME=$(echo "$INPUT" | jq -r '.toolName')
USER_INPUT=$(echo "$INPUT" | jq -r '.context.userInput')
WORKSPACE=$(echo "$INPUT" | jq -r '.context.workspace')

echo "工具: $TOOL_NAME"
echo "用户输入: $USER_INPUT"
echo "工作区: $WORKSPACE"
```

### Stdout JSON 输出

Hook 脚本可以通过 stdout 输出 JSON 格式的结果,供 Jimi 解析使用。

**输出 JSON 结构示例**:

```json
{
  "status": "success",
  "message": "操作成功",
  "data": {
    "modifiedFiles": ["src/main/java/Example.java"],
    "actions": ["formatted", "validated"]
  },
  "blockExecution": false
}
```

**脚本输出 JSON 示例**:

```bash
#!/bin/bash
# 执行操作
RESULT=$(google-java-format -i "$FILE")

# 输出 JSON 结果
cat <<EOF
{
  "status": "success",
  "message": "已格式化文件",
  "data": {
    "file": "$FILE",
    "actions": ["formatted"]
  },
  "blockExecution": false
}
EOF
```

**HookResult 字段说明**:

| 字段 | 必需 | 说明 |
|------|------|------|
| `status` | ✅ | 执行状态: success/failure |
| `message` | ❌ | 执行消息 |
| `data` | ❌ | 附加数据 |
| `blockExecution` | ❌ | 是否阻塞后续操作,默认 false |

---

## Exit Code 决策控制

Hook 脚本通过 Exit Code 控制执行流程,这是对齐 Claude Code 的标准决策机制。

### Exit Code 语义

| Exit Code | 含义 | 行为 |
|-----------|------|------|
| `0` | 允许 | 允许操作继续执行 |
| `2` | 阻塞 | 阻止操作继续执行 |
| 其他 | 非阻塞错误 | 记录错误但不阻塞 |

### 使用场景

#### 1. PRE_TOOL_USE - 权限控制

```bash
#!/bin/bash
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.toolName')

# 阻止删除操作
if [[ "$TOOL_NAME" == "BashTool" ]]; then
  COMMAND=$(echo "$INPUT" | jq -r '.toolInput.command')
  if [[ "$COMMAND" == *"rm -rf"* ]]; then
    echo "❌ 危险操作被阻止: $COMMAND"
    exit 2  # 阻塞
  fi
fi

exit 0  # 允许
```

#### 2. POST_TOOL_USE - 质量检查

```bash
#!/bin/bash
FILE="${MODIFIED_FILE}"

# 检查代码质量
if ! mvn checkstyle:check > /dev/null 2>&1; then
  echo "⚠️ 代码质量检查失败"
  exit 1  # 非阻塞错误
fi

exit 0  # 允许
```

#### 3. USER_PROMPT_SUBMIT - 输入验证

```bash
#!/bin/bash
INPUT=$(cat)
USER_INPUT=$(echo "$INPUT" | jq -r '.context.userInput')

# 检查敏感信息
if [[ "$USER_INPUT" == *"password"* ]]; then
  echo "❌ 输入包含敏感信息"
  exit 2  # 阻塞
fi

exit 0  # 允许
```

### 最佳实践

```bash
#!/bin/bash
set -e

INPUT=$(cat)
# 解析输入
# 执行检查

# 明确返回决策
if [[ "$CHECK_RESULT" == "pass" ]]; then
  exit 0  # 允许
elif [[ "$CHECK_RESULT" == "block" ]]; then
  exit 2  # 阻塞
else
  exit 1  # 非阻塞错误
fi
```

---

## 异步执行

Hook 支持异步执行,避免阻塞主流程。

### 启用异步执行

在 `execution` 配置中设置 `async: true`:

```yaml
execution:
  type: "script"
  async: true
  script: |
    #!/bin/bash
    # 长时间运行的任务
    mvn clean install
  timeout: 600
```

### 异步执行特性

- **非阻塞**: Hook 在后台执行,不阻塞主流程
- **独立超时**: 异步任务有独立的超时控制
- **状态跟踪**: 可以通过命令查看异步任务状态
- **结果通知**: 执行完成后会通知用户

### 适用场景

**长时间编译：**

```yaml
name: "async-build"
execution:
  type: "script"
  async: true
  script: "mvn clean install"
  timeout: 600
```

**后台测试：**

```yaml
name: "async-test"
execution:
  type: "script"
  async: true
  script: "mvn test"
  timeout: 300
```

**自动部署：**

```yaml
name: "async-deploy"
execution:
  type: "agent"
  async: true
  agent: "DevOps-Agent"
  task: "部署到测试环境"
```

### 注意事项

1. **状态管理**: 异步任务无法通过 Exit Code 控制主流程
2. **错误处理**: 异步任务失败仅记录日志
3. **资源竞争**: 注意避免多个异步任务同时修改同一文件
4. **结果获取**: 使用管理命令查看异步任务结果

---

## 实战示例

### 示例 1: 自动代码格式化

```yaml
name: "auto-format-java"
description: "保存 Java 文件后自动格式化"
enabled: true
priority: 10

trigger:
  type: "POST_TOOL_USE"
  tools:
    - "WriteFile"
    - "StrReplaceFile"
  file_patterns:
    - "*.java"

execution:
  type: "script"
  async: true
  script: |
    #!/bin/bash
    INPUT=$(cat)
    MODIFIED_FILES=$(echo "$INPUT" | jq -r '.context.modifiedFiles[]')
    
    for file in $MODIFIED_FILES; do
      google-java-format -i "$file"
      echo "✅ 已格式化: $file"
    done
  workingDir: "${JIMI_WORK_DIR}"
  timeout: 30
```

### 示例 2: Git 提交前测试

```yaml
name: "pre-commit-test"
description: "Git 提交前自动运行测试"
enabled: true
priority: 100

trigger:
  type: "PRE_TOOL_USE"
  tools:
    - "BashTool"
  matcher:
    pattern: ".*git commit.*"

execution:
  type: "script"
  script: |
    #!/bin/bash
    INPUT=$(cat)
    COMMAND=$(echo "$INPUT" | jq -r '.toolInput.command')
    
    if [[ "$COMMAND" == *"git commit"* ]]; then
      if ! mvn test > /dev/null 2>&1; then
        echo "❌ 测试失败,阻止提交"
        exit 2  # 阻塞
      fi
    fi
    
    exit 0  # 允许
  workingDir: "${JIMI_WORK_DIR}"
  timeout: 300

conditions:
  - type: "file_exists"
    path: ".git"
```

### 示例 3: 自动导入优化

```yaml
name: "auto-optimize-imports"
description: "保存 Java 文件后自动优化 import"
enabled: true
priority: 5

trigger:
  type: "POST_TOOL_USE"
  tools:
    - "WriteFile"
  file_patterns:
    - "*.java"

execution:
  type: "agent"
  agent: "Code-Agent"
  task: "优化 ${MODIFIED_FILE} 的 import 语句,移除未使用的导入"
```

### 示例 4: 会话启动欢迎

```yaml
name: "session-welcome"
description: "会话启动时显示项目信息"
enabled: true

trigger:
  type: "SESSION_START"

execution:
  type: "script"
  script: |
    #!/bin/bash
    INPUT=$(cat)
    WORKSPACE=$(echo "$INPUT" | jq -r '.context.workspace')
    
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "🎉 欢迎使用 Jimi!"
    echo "📂 工作目录: $WORKSPACE"
    
    if [ -f "pom.xml" ]; then
      project=$(grep -m 1 '<artifactId>' pom.xml | sed 's/.*<artifactId>\(.*\)<\/artifactId>/\1/')
      echo "📦 Maven 项目: $project"
    fi
    
    if [ -d ".git" ]; then
      branch=$(git branch --show-current)
      echo "🌿 Git 分支: $branch"
    fi
    
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    exit 0
  timeout: 5
```

### 示例 5: 错误自动修复

```yaml
name: "auto-fix-compilation"
description: "编译错误时自动修复 import"
enabled: true
priority: 50

trigger:
  type: "ON_ERROR"
  matcher:
    pattern: ".*cannot find symbol.*"

execution:
  type: "agent"
  agent: "Code-Agent"
  task: |
    分析编译错误并自动修复:
    ${ERROR_MESSAGE}
    
    重点关注:
    1. 缺失的 import 语句
    2. 类型拼写错误
    3. 包名错误
```

### 示例 6: 用户输入验证

```yaml
name: "validate-user-input"
description: "验证用户输入,防止敏感信息泄露"
enabled: true
priority: 100

trigger:
  type: "USER_PROMPT_SUBMIT"
  matcher:
    pattern: ".*(password|secret|token).*"

execution:
  type: "script"
  script: |
    #!/bin/bash
    INPUT=$(cat)
    USER_INPUT=$(echo "$INPUT" | jq -r '.context.userInput')
    
    if [[ "$USER_INPUT" =~ (password|secret|token) ]]; then
      echo "❌ 输入包含敏感信息,请避免在对话中分享密码或密钥"
      exit 2  # 阻塞
    fi
    
    exit 0  # 允许
  timeout: 5
```

### 示例 7: 工具调用失败处理

```yaml
name: "handle-tool-failure"
description: "工具调用失败时自动恢复"
enabled: true
priority: 50

trigger:
  type: "POST_TOOL_USE_FAILURE"
  tools:
    - "BashTool"

execution:
  type: "script"
  script: |
    #!/bin/bash
    INPUT=$(cat)
    ERROR=$(echo "$INPUT" | jq -r '.error')
    
    echo "⚠️ 工具调用失败: $ERROR"
    echo "正在尝试恢复..."
    
    # 执行恢复操作
    # ...
    
    exit 0
  timeout: 30
```

### 示例 8: 通知处理

```yaml
name: "handle-notification"
description: "处理系统通知事件"
enabled: true
priority: 10

trigger:
  type: "NOTIFICATION"

execution:
  type: "script"
  script: |
    #!/bin/bash
    INPUT=$(cat)
    MESSAGE=$(echo "$INPUT" | jq -r '.notification.message')
    TYPE=$(echo "$INPUT" | jq -r '.notification.type')
    
    echo "📢 收到通知 [$TYPE]: $MESSAGE"
    
    # 根据通知类型执行不同操作
    case $TYPE in
      "warning")
        echo "⚠️ 警告通知"
        ;;
      "error")
        echo "❌ 错误通知"
        ;;
      "info")
        echo "ℹ️ 信息通知"
        ;;
    esac
    
    exit 0
  timeout: 10
```

---

## 最佳实践

### 1. 命名规范

- 使用有意义的名称: `auto-format-java` 而非 `hook1`
- 使用连字符分隔: `pre-commit-test`
- 包含操作类型: `auto-`, `pre-`, `post-`

### 2. 优先级设置

```
100+  - 关键检查 (阻塞操作)
50-99 - 重要自动化
10-49 - 一般自动化
0-9   - 辅助功能
```

### 3. 超时设置

| 场景 | 推荐超时 |
|------|---------|
| 快速脚本 | `timeout: 5` |
| 代码格式化 | `timeout: 30` |
| 编译/测试 | `timeout: 300` |
| 长时间任务 | `timeout: 600` |

### 4. 错误处理

```yaml
execution:
  type: "script"
  script: |
    #!/bin/bash
    set -e  # 遇到错误立即退出
    
    # 检查依赖
    if ! command -v google-java-format &> /dev/null; then
      echo "⚠️  google-java-format 未安装,跳过格式化"
      exit 0  # 正常退出,不阻塞
    fi
    
    # 执行操作
    google-java-format -i "${MODIFIED_FILE}"
```

### 5. 条件使用

优先使用条件而非脚本内检查:

```yaml
# ✅ 推荐
conditions:
  - type: "env_var"
    var: "ENABLE_AUTO_FORMAT"
    value: "true"

# ❌ 不推荐
execution:
  script: |
    if [ "$ENABLE_AUTO_FORMAT" != "true" ]; then
      exit 0
    fi
```

### 6. 文件模式

**特定扩展名：**

```yaml
file_patterns:
  - "*.java"
```

**多种类型：**

```yaml
file_patterns:
  - "*.java"
  - "*.kt"
```

**特定目录：**

```yaml
file_patterns:
  - "src/**/*.java"
```

**排除测试（使用条件）：**

```yaml
conditions:
  - type: "script"
    script: |
      [[ "${MODIFIED_FILE}" != *"test"* ]]
```

### 7. Matcher 使用

```yaml
# ✅ 推荐: 使用 matcher 进行精确匹配
trigger:
  type: "USER_PROMPT_SUBMIT"
  matcher:
    pattern: ".*(部署|deploy).*"

# ❌ 不推荐: 在脚本中手动检查
execution:
  script: |
    if [[ "$USER_INPUT" != *"deploy"* ]]; then
      exit 0
    fi
```

### 8. Exit Code 规范

```bash
#!/bin/bash
# 明确的 Exit Code 决策
if [[ "$SHOULD_BLOCK" == "true" ]]; then
  exit 2  # 阻塞
elif [[ "$HAS_ERROR" == "true" ]]; then
  exit 1  # 非阻塞错误
else
  exit 0  # 允许
fi
```

### 9. JSON 输入处理

```bash
#!/bin/bash
# 使用 jq 解析 JSON
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.toolName')
USER_INPUT=$(echo "$INPUT" | jq -r '.context.userInput')

# 安全检查
if [[ "$TOOL_NAME" == "null" ]]; then
  echo "❌ 无法解析工具名称"
  exit 2
fi
```

### 10. 异步执行使用

**✅ 推荐：长时间任务使用异步**

```yaml
execution:
  type: "script"
  async: true
  script: "mvn clean install"
  timeout: 600
```

**❌ 不推荐：快速任务使用异步**

```yaml
execution:
  type: "script"
  async: true
  script: "echo 'hello'"
  timeout: 5
```

---

## 故障排查

### Hook 未触发

1. **检查 Hook 是否启用**
   ```bash
   /hooks
   # 查看状态列
   ```

2. **检查触发条件**
   ```yaml
   # 添加调试日志
   execution:
     script: |
       echo "DEBUG: Hook triggered!"
       echo "TOOL_NAME: ${TOOL_NAME}"
       echo "MODIFIED_FILES: ${MODIFIED_FILES}"
   ```

3. **检查文件模式**
   ```yaml
   # 暂时移除 file_patterns 测试
   trigger:
     type: "POST_TOOL_USE"
     # file_patterns:  # 注释掉
   ```

4. **检查 Matcher 模式**
   ```yaml
   # 添加调试信息
   execution:
     script: |
       echo "DEBUG: Checking matcher pattern"
       echo "USER_INPUT: $USER_INPUT"
   ```

### Hook 执行失败

1. **查看日志**
   ```
   检查 Jimi 日志输出
   ```

2. **测试脚本**
   ```bash
   # 手动执行脚本测试
   bash -c "your script here"
   ```

3. **增加超时**
   ```yaml
   execution:
     timeout: 600  # 增加到 10 分钟
   ```

4. **检查 JSON 解析**
   ```bash
   # 测试 JSON 输入
   echo '{"test": "value"}' | jq -r '.test'
   ```

### 变量未替换

```yaml
# 检查变量名拼写
${JIMI_WORK_DIR}  # ✅ 正确
${WORK_DIR}       # ❌ 错误

# 检查上下文是否包含该变量
# 例如 ${MODIFIED_FILES} 仅在文件操作工具时可用
```

### 条件不生效

```yaml
# 添加调试条件
conditions:
  - type: "script"
    script: |
      echo "Checking condition..."
      echo "ENV VAR: ${MY_VAR}"
      [ -n "${MY_VAR}" ]
```

### Exit Code 决策不生效

```bash
#!/bin/bash
# 添加调试日志
echo "DEBUG: Checking decision..."
echo "SHOULD_BLOCK: $SHOULD_BLOCK"

if [[ "$SHOULD_BLOCK" == "true" ]]; then
  echo "DEBUG: Blocking execution"
  exit 2
fi

echo "DEBUG: Allowing execution"
exit 0
```

### JSON 输入解析失败

```bash
#!/bin/bash
# 添加错误处理
INPUT=$(cat)
if ! echo "$INPUT" | jq '.' > /dev/null 2>&1; then
  echo "❌ 无效的 JSON 输入"
  exit 2
fi

# 继续解析
TOOL_NAME=$(echo "$INPUT" | jq -r '.toolName')
```

### 异步任务状态查询

```bash
# 查看所有异步任务
/hooks async list

# 查看特定异步任务
/hooks async status <task-id>

# 取消异步任务
/hooks async cancel <task-id>
```

---

## 管理命令

```bash
# 列出所有 Hooks
/hooks
/hooks list

# 查看 Hook 详情
/hooks <hook-name>

# 重新加载 Hooks
/hooks reload

# 启用 Hook
/hooks enable <hook-name>

# 禁用 Hook
/hooks disable <hook-name>

# 测试 Hook
/hooks test <hook-name>

# 查看异步任务
/hooks async list

# 查看异步任务状态
/hooks async status <task-id>

# 取消异步任务
/hooks async cancel <task-id>
```

---

## 总结

Hooks 系统为 Jimi 提供了强大的自动化能力,完全对齐 Claude Code 标准:

- 🎯 **事件驱动**: 自动响应系统事件
- 🔧 **灵活配置**: YAML 配置简单易懂
- ⚡ **高效执行**: 异步执行不阻塞
- 🛡️ **质量保证**: 自动化检查和规范
- 🎨 **Matcher 机制**: 精确的正则匹配
- 📊 **JSON 通信**: 标准化的输入输出
- 🚦 **决策控制**: Exit Code 精确控制流程
- 🔄 **异步支持**: 长时间任务后台执行

结合自定义命令,可以构建完整的自动化工作流! 🚀
