# Jimi 自定义命令使用指南

## 概述

Jimi 支持用户通过 YAML 配置文件创建自定义命令,无需编写 Java 代码即可扩展命令系统。

## 配置文件位置

自定义命令配置文件可以放置在以下位置:

```
优先级 (高 -> 低):
1. 项目目录: <project>/.jimi/commands/*.yaml
2. 用户主目录: ~/.jimi/commands/*.yaml
3. 内置示例: resources/commands/*.yaml (不可修改)
```

## 配置文件格式

### 基本结构

```yaml
# 命令基本信息
name: "command-name"          # 必需: 命令名称
description: "命令描述"        # 必需: 命令描述
category: "custom"             # 可选: 命令分类 (默认: custom)
priority: 0                    # 可选: 命令优先级 (默认: 0)

# 命令别名
aliases:                       # 可选: 命令别名列表
  - "alias1"
  - "alias2"

# 用法说明
usage: "/command-name [options]"  # 可选: 用法说明

# 参数定义
parameters:                    # 可选: 参数列表
  - name: "param1"
    type: "string"             # 类型: string, boolean, integer, path
    defaultValue: "value"      # 可选: 默认值
    required: false            # 可选: 是否必需 (默认: false)
    description: "参数描述"    # 可选: 参数描述

# 执行配置
execution:                     # 必需: 执行配置
  type: "script"               # 类型: script, agent, composite
  # ... 类型特定配置

# 前置条件
preconditions:                 # 可选: 前置条件列表
  - type: "file_exists"        # 类型: file_exists, dir_exists, env_var, command_exists
    # ... 条件特定配置

# 其他配置
requireApproval: false         # 可选: 是否需要审批 (默认: false)
enabled: true                  # 可选: 是否启用 (默认: true)
```

## 执行类型

### 1. Script 类型

执行 Shell 脚本:

```yaml
execution:
  type: "script"
  
  # 方式1: 内联脚本
  script: |
    #!/bin/bash
    echo "Hello, Jimi!"
    mvn clean install
  
  # 方式2: 脚本文件 (优先于 script 字段)
  scriptFile: "${HOME}/.jimi/scripts/build.sh"
  
  # 工作目录 (可选)
  workingDir: "${JIMI_WORK_DIR}"
  
  # 超时时间(秒) (可选, 默认: 60)
  timeout: 120
  
  # 环境变量 (可选)
  environment:
    MAVEN_OPTS: "-Xmx1024m"
    JAVA_HOME: "/usr/lib/jvm/java-17"
```

### 2. Agent 类型

委托给 Agent 执行:

```yaml
execution:
  type: "agent"
  agent: "Code-Agent"              # 必需: Agent 名称
  task: "重构 UserService 类"      # 必需: 任务描述
```

### 3. Composite 类型

组合多个命令或脚本:

```yaml
execution:
  type: "composite"
  steps:
    - type: "command"
      command: "/reset"
      description: "清除上下文"
      continueOnFailure: false
    
    - type: "script"
      script: "mvn clean"
      description: "清理项目"
      continueOnFailure: true
    
    - type: "script"
      script: "mvn install"
      description: "构建项目"
      continueOnFailure: false
```

### 4. Prompt 类型 (对齐 Claude Code 标准)

将模板内容作为 Prompt 发送给 AI 执行。使用 `$ARGUMENTS` 占位符捕获用户输入的参数:

```yaml
name: "quick-review"
description: "快速代码审查"

prompt: |
  请对当前项目最近的代码更改进行审查。
  $ARGUMENTS

  审查要点:
  1. 代码质量和可读性
  2. 潜在的 Bug 和安全问题
  3. 性能优化建议
  4. 最佳实践遵循情况

  请提供具体的改进建议。
```

> **说明**: Prompt 类型对齐了 Claude Code 的自定义命令标准。
> 在 Claude Code 中,命令文件是 Markdown 文件,内容作为 Prompt 发送给 AI。
> Jimi 通过 `prompt` 字段实现了相同的功能,同时保留了 YAML 配置的灵活性。
> 当配置了 `prompt` 字段时,无需配置 `execution` 字段。

## 参数类型

支持的参数类型:

- `string`: 字符串 (默认)
- `boolean`: 布尔值
- `integer`: 整数
- `path`: 文件路径

## 前置条件

支持的前置条件类型:

### file_exists

检查文件是否存在:

```yaml
preconditions:
  - type: "file_exists"
    path: "pom.xml"
    errorMessage: "pom.xml 文件不存在"
```

### dir_exists

检查目录是否存在:

```yaml
preconditions:
  - type: "dir_exists"
    path: ".git"
    errorMessage: "不是 Git 仓库"
```

### env_var

检查环境变量:

```yaml
preconditions:
  - type: "env_var"
    var: "JAVA_HOME"
    value: "/usr/lib/jvm/java-17"  # 可选: 期望值
    errorMessage: "JAVA_HOME 未设置"
```

### command_exists

检查命令是否可用:

```yaml
preconditions:
  - type: "command_exists"
    command: "mvn"
    errorMessage: "Maven 未安装"
```

## 变量替换

在脚本和配置中可以使用以下变量:

### 内置变量

- `${JIMI_WORK_DIR}`: 当前工作目录
- `${HOME}`: 用户主目录
- `${PROJECT_ROOT}`: 项目根目录

### 参数变量

参数会自动转换为环境变量(大写,连字符转下划线):

```yaml
parameters:
  - name: "skip-tests"
    type: "boolean"

# 在脚本中使用
script: |
  if [ "$SKIP_TESTS" = "true" ]; then
    echo "跳过测试"
  fi
```

## 命令管理

### 列出所有自定义命令

```bash
/commands
```

### 查看命令详情

```bash
/commands quick-build
```

### 重新加载命令

```bash
/commands reload
```

### 启用/禁用命令

```bash
/commands enable quick-build
/commands disable quick-build
```

## 完整示例

### 示例 1: 快速构建

```yaml
name: "quick-build"
description: "快速构建并运行测试"
category: "build"

aliases:
  - "qb"

usage: "/quick-build [--skip-tests]"

parameters:
  - name: "skip-tests"
    type: "boolean"
    defaultValue: "false"

execution:
  type: "script"
  script: |
    #!/bin/bash
    if [ "$SKIP_TESTS" = "true" ]; then
      mvn clean install -DskipTests
    else
      mvn clean install
    fi
  timeout: 300

preconditions:
  - type: "file_exists"
    path: "pom.xml"
```

### 示例 2: Git 快速提交

```yaml
name: "git-commit"
description: "添加并提交所有更改"
category: "git"

aliases:
  - "gc"

parameters:
  - name: "message"
    type: "string"
    required: true

execution:
  type: "composite"
  steps:
    - type: "script"
      script: "git add ."
    - type: "script"
      script: 'git commit -m "${MESSAGE}"'
```

### 示例 3: 代码审查

```yaml
name: "quick-review"
description: "快速代码审查"
category: "code"

execution:
  type: "agent"
  agent: "Review-Agent"
  task: "审查最近的代码更改"

requireApproval: false
```

## 最佳实践

1. **命名规范**: 使用小写字母和连字符,如 `quick-build`
2. **分类管理**: 合理使用 category 字段组织命令
3. **前置条件**: 添加必要的前置条件检查,避免执行失败
4. **超时设置**: 根据命令执行时间合理设置 timeout
5. **错误处理**: 在脚本中使用 `set -e` 遇错即停
6. **审批控制**: 危险操作设置 `requireApproval: true`

## 故障排查

### 命令未加载

1. 检查 YAML 语法是否正确
2. 查看日志: `~/.jimi/logs/jimi.log`
3. 使用 `/commands reload` 重新加载

### 参数未传递

确保参数名在脚本中使用大写且连字符转下划线:

```yaml
parameters:
  - name: "skip-tests"  # 在脚本中使用 $SKIP_TESTS
```

### 前置条件失败

使用绝对路径或 `${JIMI_WORK_DIR}` 引用文件:

```yaml
preconditions:
  - type: "file_exists"
    path: "${JIMI_WORK_DIR}/pom.xml"
```

## 高级用法

### 使用脚本文件

将复杂脚本放在单独文件中:

```yaml
execution:
  type: "script"
  scriptFile: "${HOME}/.jimi/scripts/complex-build.sh"
```

### 组合命令实现工作流

```yaml
execution:
  type: "composite"
  steps:
    - type: "command"
      command: "/reset"
    - type: "command"
      command: "/agents run design"
    - type: "script"
      script: "echo '设计阶段完成'"
```

---

更多信息请参考: https://github.com/your-repo/jimi/docs/custom-commands.md
