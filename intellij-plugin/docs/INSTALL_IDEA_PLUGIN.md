# Jimi IDEA插件安装指南

由于Gradle环境兼容性问题，提供**两种安装方式**：

## 方式一：在IDEA中开发和使用（推荐）

直接在IDEA中打开`intellij-plugin`项目并运行：

### 步骤

1. **打开项目**
   ```bash
   IDEA → Open → 选择 /Users/yefei.yf/QoderCLI/Jimi/intellij-plugin
   ```

2. **等待IDEA自动下载依赖**  
   IDEA会自动识别`build.gradle.kts`并下载IntelliJ Platform SDK

3. **运行插件**  
   右键`build.gradle.kts` → Run 'runIde'
   
   或使用Gradle面板：
   ```
   Gradle → intellij-plugin → Tasks → intellij → runIde (双击)
   ```

4. **测试插件**  
   - 会启动一个新IDEA实例,插件已加载
   - 右键代码 → "Ask Jimi"
   - 或打开右侧"Jimi"工具窗口

### 开发调试

修改代码后,重新运行`runIde`即可看到效果。

---

## 方式二：构建ZIP并安装

如果需要分发给其他人，使用IDEA构建插件ZIP：

### 步骤

1. **在IDEA中打开项目**（同方式一）

2. **构建插件**  
   Gradle面板 → intellij-plugin → Tasks → intellij → buildPlugin

3. **找到生成的ZIP**  
   ```
   intellij-plugin/build/distributions/jimi-intellij-plugin-0.1.0.zip
   ```

4. **安装到IDEA**  
   Settings → Plugins → ⚙️ → Install Plugin from Disk → 选择ZIP

5. **重启IDEA**

---

## 使用说明

插件提供两种交互方式：

### 1. 右键菜单
- 选中代码 → 右键 → "Ask Jimi"
- 输入要求 → 回车
- 查看结果对话框

### 2. 工具窗口
- 打开右侧"Jimi"面板
- 输入问题 → Send
- 实时查看响应

---

## 前置要求

**必须**：先启动Jimi MCP Server

```bash
cd /Users/yefei.yf/QoderCLI/Jimi
java -jar target/jimi-0.1.0.jar --mcp-server
```

插件会自动查找以下位置的JAR：
1. `../Jimi/target/jimi-0.1.0.jar`（相对当前项目）
2. `~/.jimi/jimi-0.1.0.jar`（用户目录）

---

## 故障排查

### Q: IDEA无法识别build.gradle.kts
**A**: File → Invalidate Caches → Restart

### Q: 依赖下载失败
**A**: 检查网络，或配置Maven镜像：
```gradle
repositories {
    maven { url 'https://maven.aliyun.com/repository/public' }
    mavenCentral()
}
```

### Q: 插件无响应
**A**: 确认Jimi JAR已运行在MCP Server模式

### Q: Gradle版本不兼容
**A**: IDEA会自动使用正确的Gradle版本，无需手动配置

---

## 技术细节

- **构建工具**: Gradle + IntelliJ Platform Plugin
- **语言**: Kotlin 1.9.22
- **IDEA版本**: 2023.2+
- **JDK**: 17
- **通信协议**: MCP (JSON-RPC 2.0 over StdIO)

