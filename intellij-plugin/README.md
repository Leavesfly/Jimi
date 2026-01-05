# Jimi IntelliJ IDEA Plugin

基于MCP协议的轻量级IDEA插件,通过进程隔离方式集成Jimi AI助手。

## 架构

```
IDEA Plugin (Java/Kotlin)
    ↓ (Process)
Jimi CLI --mcp-server
    ↓ (StdIO/JSON-RPC)
MCP Protocol
```

## 快速开始

### 1. 构建插件

```bash
cd intellij-plugin
./gradlew buildPlugin
```

### 2. 安装插件

1. 打开IDEA → Settings → Plugins
2. 点击齿轮图标 → Install Plugin from Disk
3. 选择 `build/distributions/jimi-intellij-plugin-0.1.0.zip`

### 3. 使用

1. 右键选中代码 → "Ask Jimi"
2. 或打开右侧工具窗口 "Jimi"

## 开发

### 运行调试

```bash
./gradlew runIde
```

### 项目结构

```
intellij-plugin/
├── src/main/
│   ├── kotlin/io/leavesfly/jimi/plugin/
│   │   ├── actions/          # IDEA Actions
│   │   ├── ui/               # UI组件
│   │   ├── mcp/              # MCP客户端
│   │   └── process/          # 进程管理
│   └── resources/
│       └── META-INF/
│           └── plugin.xml    # 插件配置
└── build.gradle.kts
```

## 核心功能

- ✅ 通过MCP协议与Jimi通信
- ✅ 独立进程运行,互不影响
- ✅ 实时显示任务执行进度
- ✅ 支持自然语言交互
