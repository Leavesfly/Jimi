# JWork

Jimi 的桌面 GUI 模块，参考 OpenWork 设计理念，提供可视化的 AI 代理交互体验。

## 核心特性

- 工作区选择与管理
- 会话管理与流式输出
- 执行计划时间线
- 权限审批中心
- Skills 管理器
- Templates 模板复用

## 技术栈

- **Java 17**
- **JavaFX 21** - 跨平台桌面 UI 框架
- **AtlantaFX** - 现代化 UI 主题
- **CommonMark** - Markdown 渲染
- **Jimi Core** - AI Agent 核心引擎

## 项目结构

```
jwork/
├── src/main/
│   ├── java/io/leavesfly/jwork/
│   │   ├── JWorkApplication.java    # 主应用入口
│   │   ├── Launcher.java            # 启动器
│   │   ├── model/                   # 数据模型
│   │   ├── service/                 # 业务服务
│   │   └── ui/                      # UI 组件
│   └── resources/
│       ├── css/                     # 样式文件
│       └── logback.xml              # 日志配置
├── docs/
│   └── TECHNICAL_DESIGN.md          # 技术设计文档
└── pom.xml
```

## 架构

```
┌─────────────────────────────────────────────────┐
│                  UI Layer (JavaFX)              │
│  MainView / SessionView / SkillView / Timeline  │
├─────────────────────────────────────────────────┤
│                Controller Layer                  │
│  MainController / SessionController / ...        │
├─────────────────────────────────────────────────┤
│                 Service Layer                    │
│  JWorkService / SessionManager / SkillManager    │
├─────────────────────────────────────────────────┤
│                  Jimi Core                       │
│  JimiEngine / Wire / Approval / SkillRegistry    │
└─────────────────────────────────────────────────┘
```

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.6+
- 已安装 Jimi 核心模块

### 构建

```bash
# 在项目根目录执行
mvn install -pl jwork -am
```

### 运行

```bash
# 使用 JavaFX Maven 插件运行
cd jwork
mvn javafx:run

# 或运行打包后的 JAR
java -jar target/jwork-0.1.0.jar
```

## 通信流程

```
User Input
    │
    ▼
JWorkService.execute(sessionId, input)
    │
    ▼
JimiEngine.run(input)
    │
    ├──▶ Wire.asFlux() ──▶ StreamChunk 转换 ──▶ UI 更新
    │
    └──▶ Approval 请求 ──▶ ApprovalDialog ──▶ 用户响应 ──▶ 继续执行
```

## 详细文档

- [技术设计文档](docs/TECHNICAL_DESIGN.md)
