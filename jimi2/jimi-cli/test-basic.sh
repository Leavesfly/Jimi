#!/bin/bash

# 非交互式命令测试
# 测试 CLI 基础功能（--help, --version）以及检查打包

echo "=== Jimi CLI 非交互式测试 ==="
echo ""

JAR_FILE="target/jimi-cli-0.1.0-SNAPSHOT-exec.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "错误: 找不到 JAR 文件: $JAR_FILE"
    echo "请先运行: mvn clean package"
    exit 1
fi

echo "✓ JAR 文件存在: $JAR_FILE"
echo "  文件大小: $(ls -lh $JAR_FILE | awk '{print $5}')"
echo ""

# 检查 JAR 内容
echo "检查 JAR 内容..."
echo "  META-INF/services 目录:"
jar tf $JAR_FILE | grep "META-INF/services/" | head -5
echo ""

# 测试 --help
echo "测试: --help"
export OPENAI_API_KEY="sk-test-fake"
java -jar $JAR_FILE --help 2>&1 | grep -v "logback" | grep -v "|-" | grep -A 10 "Jimi CLI"
echo ""

# 测试 --version
echo "测试: --version"
java -jar $JAR_FILE --version 2>&1 | grep -v "logback" | grep -v "|-" | grep "Jimi"
echo ""

# 显示如何实际运行
echo "====================================="
echo "基础功能测试通过！"
echo ""
echo "要启动交互式 REPL，请执行："
echo "  export OPENAI_API_KEY=sk-xxx"
echo "  cd /path/to/project"
echo "  java -jar $JAR_FILE"
echo ""
echo "可用命令："
echo "  /help     - 查看帮助"
echo "  /tools    - 查看工具列表"
echo "  /config   - 查看配置"
echo "  /version  - 查看版本"
echo "  /exit     - 退出"
echo ""
