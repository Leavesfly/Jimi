# MCP 配置文件模板

本目录包含多个 MCP (Model Context Protocol) 配置文件模板，用于集成各种外部工具服务。

## 配置文件说明

### 基础模板

- **mcp-stdio-basic.json** - STDIO 传输方式的基础模板
  - 适用于通过命令行启动的 MCP 服务
  - 支持自定义命令、参数和环境变量

- **mcp-http-basic.json** - HTTP 传输方式的基础模板
  - 适用于通过 HTTP 协议连接的远程 MCP 服务
  - 支持自定义 URL 和请求头（如 Authorization）

### 专用模板

- **mcp-filesystem.json** - 文件系统操作工具
  - 提供文件读写、目录管理等功能
  - 需配置允许访问的目录路径

- **mcp-git.json** - Git 版本控制工具
  - 提供 Git 仓库操作功能
  - 需配置 Git 仓库路径

- **mcp-github.json** - GitHub API 集成工具
  - 提供 GitHub 仓库、Issue、PR 等操作
  - 需配置 GitHub Personal Access Token

- **mcp-database.json** - 数据库连接工具
  - 支持 PostgreSQL、MySQL 等数据库
  - 需配置数据库连接字符串

### 组合模板

- **mcp-multi-server.json** - 多服务集成示例
  - 展示如何同时配置多个 MCP 服务
  - 可根据需求选择性启用服务

- **mcp-config-template.json** - 完整配置示例
  - 包含所有常用 MCP 服务的配置示例
  - 可作为参考文档使用

## 使用方法

### 1. 选择合适的模板

根据您的需求选择对应的模板文件，或从 `mcp-config-template.json` 中提取需要的部分。

### 2. 复制并修改配置

```bash
# 复制模板到用户配置目录
cp src/main/resources/mcp/mcp-github.json ~/.config/jimi/mcp-config.json

# 编辑配置文件
vim ~/.config/jimi/mcp-config.json
```

### 3. 修改配置项

根据实际情况修改以下内容：

- **路径占位符**: 将 `/path/to/...` 替换为实际路径
- **认证信息**: 将 `your-token`、`your-api-token` 等替换为真实凭证
- **数据库连接**: 将 `username:password@localhost` 等替换为实际连接信息
- **环境变量**: 根据需要添加或修改 `env` 中的环境变量

### 4. 启动 Jimi 并加载配置

```bash
# 使用单个配置文件
./jimi --mcp-config-file ~/.config/jimi/mcp-config.json -w /path/to/project

# 使用多个配置文件
./jimi --mcp-config-file mcp-git.json --mcp-config-file mcp-github.json -w /path/to/project
```

## 配置结构说明

### STDIO 配置 (命令行方式)

```json
{
  "mcpServers": {
    "server-name": {
      "command": "执行命令（如 npx、node、python）",
      "args": ["命令参数数组"],
      "env": {
        "环境变量名": "环境变量值"
      }
    }
  }
}
```

**必填字段**:
- `command`: 启动 MCP 服务的命令
- `args`: 命令行参数数组

**可选字段**:
- `env`: 环境变量键值对

### HTTP 配置 (远程服务方式)

```json
{
  "mcpServers": {
    "server-name": {
      "url": "http://服务器地址:端口/路径",
      "headers": {
        "请求头名称": "请求头值"
      }
    }
  }
}
```

**必填字段**:
- `url`: MCP 服务的 HTTP 端点地址

**可选字段**:
- `headers`: HTTP 请求头键值对（如认证信息）

## 常见问题

### 1. 如何判断使用 STDIO 还是 HTTP？

- **STDIO**: 如果 MCP 服务需要通过命令行启动（如 npx、node、python 等），使用 STDIO 配置
- **HTTP**: 如果 MCP 服务已在远程运行并提供 HTTP 接口，使用 HTTP 配置

### 2. 环境变量如何设置？

在 `env` 字段中添加键值对，这些环境变量会在启动 MCP 服务进程时注入：

```json
"env": {
  "NODE_ENV": "production",
  "API_KEY": "your-secret-key",
  "DEBUG": "true"
}
```

### 3. 可以同时使用多个 MCP 服务吗？

可以，有两种方式：

1. 在单个配置文件中定义多个服务（参考 `mcp-multi-server.json`）
2. 使用多个 `--mcp-config-file` 参数加载多个配置文件

### 4. 如何验证配置是否正确？

启动 Jimi 时观察日志输出：

```
INFO MCPToolLoader - Loaded MCP tool: tool_name from server: server_name
```

如果看到此日志，说明 MCP 工具已成功加载。

## 安全建议

1. **不要将包含敏感信息的配置文件提交到版本控制系统**
2. **使用环境变量存储 API Token 和密码**
3. **限制文件系统工具的访问路径范围**
4. **定期轮换 API Token 和访问凭证**

## 参考资源

- [MCPConfig.java](../../java/io/leavesfly/jimi/mcp/MCPConfig.java) - 配置类定义
- [MCPToolLoader.java](../../java/io/leavesfly/jimi/tool/mcp/MCPToolLoader.java) - 加载器实现
- [MCP 官方文档](https://github.com/modelcontextprotocol) - MCP 协议规范
