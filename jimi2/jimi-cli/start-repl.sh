#!/bin/bash

# Jimi CLI 启动脚本
# 用于快速启动 Jimi CLI 进入交互模式

echo "=== 启动 Jimi CLI REPL ==="
echo ""

# 检查 API Key
if [ -z "$OPENAI_API_KEY" ]; then
    echo "警告: 未设置 OPENAI_API_KEY"
    echo "请先设置 API Key："
    echo "  export OPENAI_API_KEY=sk-xxx"
    echo ""
    echo "如需继续测试（不实际调用 LLM），可使用假 Key："
    export OPENAI_API_KEY="sk-test-fake-key-for-testing"
    echo "已设置测试用 Key: $OPENAI_API_KEY"
    echo ""
fi

# 检查 JAR 文件
JAR_FILE="target/jimi-cli-0.1.0-SNAPSHOT-exec.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "错误: 找不到 JAR 文件"
    echo "请先运行: mvn clean package"
    exit 1
fi

echo "工作目录: $(pwd)"
echo "API Key: ${OPENAI_API_KEY:0:10}..."
echo ""
echo "启动交互式 Shell..."
echo "输入 /help 查看命令，输入 /exit 退出"
echo ""

# 启动 CLI
java -jar $JAR_FILE
