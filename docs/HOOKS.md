# Jimi Hooks 系统完整指南

## 📖 目录

1. [简介](#简介)
2. [Hook 类型](#hook-类型)
3. [配置文件结构](#配置文件结构)
4. [触发配置](#触发配置)
5. [执行配置](#执行配置)
6. [条件配置](#条件配置)
7. [变量替换](#变量替换)
8. [实战示例](#实战示例)
9. [最佳实践](#最佳实践)
10. [故障排查](#故障排查)

---

## 简介

Hooks 系统是 Jimi 的事件驱动自动化机制,允许在关键节点自动执行自定义操作。

### 特性

- ✅ **事件驱动**: 在特定事件自动触发
- ✅ **灵活配置**: 支持 YAML 配置文件
- ✅ **条件执行**: 支持多种执行条件
- ✅ **变量替换**: 丰富的内置变量
- ✅ **优先级控制**: 按优先级顺序执行
- ✅ **热加载**: 无需重启即可加载新 Hook

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

#### PRE_TOOL_CALL
**触发时机**: 工具执行前  
**用途**: 权限检查、参数验证、审批

```yaml
trigger:
  type: "PRE_TOOL_CALL"
  tools:
    - "BashTool"
    - "WriteFile"
```

#### POST_TOOL_CALL
**触发时机**: 工具执行后  
**用途**: 自动格式化、提交、清理

```yaml
trigger:
  type: "POST_TOOL_CALL"
  tools:
    - "WriteFile"
  file_patterns:
    - "*.java"
```

### 2. 用户输入 Hooks

#### PRE_USER_INPUT
**触发时机**: 用户输入处理前  
**用途**: 输入预处理、上下文准备

#### POST_USER_INPUT
**触发时机**: 用户输入处理后  
**用途**: 输入验证、自动补全

### 3. Agent 切换 Hooks

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

### 4. 错误处理 Hooks

#### ON_ERROR
**触发时机**: 系统错误发生时  
**用途**: 错误处理、日志记录、自动修复

```yaml
trigger:
  type: "ON_ERROR"
  errorPattern: ".*compilation error.*"
```

### 5. 会话生命周期 Hooks

#### ON_SESSION_START
**触发时机**: Jimi 会话启动时  
**用途**: 环境初始化、欢迎信息

#### ON_SESSION_END
**触发时机**: Jimi 会话结束时  
**用途**: 资源清理、状态保存

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
  type: "POST_TOOL_CALL"
  tools:
    - "WriteFile"
  file_patterns:
    - "*.java"
    - "*.xml"

# 执行配置
execution:
  type: "script"
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
  type: "POST_TOOL_CALL"
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
  type: "POST_TOOL_CALL"
  file_patterns:
    - "*.java"           # 所有 Java 文件
    - "*.xml"            # 所有 XML 文件
    - "src/**/*.java"    # src 目录下所有 Java 文件
    - "pom.xml"          # 特定文件
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

## 实战示例

### 示例 1: 自动代码格式化

```yaml
name: "auto-format-java"
description: "保存 Java 文件后自动格式化"
enabled: true
priority: 10

trigger:
  type: "POST_TOOL_CALL"
  tools:
    - "WriteFile"
    - "StrReplaceFile"
  file_patterns:
    - "*.java"

execution:
  type: "script"
  script: |
    #!/bin/bash
    for file in ${MODIFIED_FILES}; do
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
  type: "PRE_TOOL_CALL"
  tools:
    - "BashTool"

execution:
  type: "script"
  script: |
    #!/bin/bash
    if [[ "${TOOL_RESULT}" == *"git commit"* ]]; then
      mvn test || exit 1
    fi
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
  type: "POST_TOOL_CALL"
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
  type: "ON_SESSION_START"

execution:
  type: "script"
  script: |
    #!/bin/bash
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "🎉 欢迎使用 Jimi!"
    echo "📂 工作目录: ${JIMI_WORK_DIR}"
    
    if [ -f "pom.xml" ]; then
      project=$(grep -m 1 '<artifactId>' pom.xml | sed 's/.*<artifactId>\(.*\)<\/artifactId>/\1/')
      echo "📦 Maven 项目: $project"
    fi
    
    if [ -d ".git" ]; then
      branch=$(git branch --show-current)
      echo "🌿 Git 分支: $branch"
    fi
    
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
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
  errorPattern: ".*cannot find symbol.*"

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

```yaml
# 快速脚本
timeout: 5

# 代码格式化
timeout: 30

# 编译/测试
timeout: 300

# 长时间任务
timeout: 600
```

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

```yaml
# 特定扩展名
file_patterns:
  - "*.java"

# 多种类型
file_patterns:
  - "*.java"
  - "*.kt"

# 特定目录
file_patterns:
  - "src/**/*.java"

# 排除测试(使用条件)
conditions:
  - type: "script"
    script: |
      [[ "${MODIFIED_FILE}" != *"test"* ]]
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
     type: "POST_TOOL_CALL"
     # file_patterns:  # 注释掉
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
```

---

## 总结

Hooks 系统为 Jimi 提供了强大的自动化能力:

- 🎯 **事件驱动**: 自动响应系统事件
- 🔧 **灵活配置**: YAML 配置简单易懂
- ⚡ **高效执行**: 异步执行不阻塞
- 🛡️ **质量保证**: 自动化检查和规范

结合自定义命令,可以构建完整的自动化工作流! 🚀
