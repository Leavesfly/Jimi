#!/bin/bash

# 测试 Jimi CLI
# 这个脚本用于快速测试打包后的 CLI 应用

echo "=== Jimi CLI 测试 ==="
echo ""

# 设置工作目录
WORK_DIR="$(pwd)"
echo "工作目录: $WORK_DIR"

# 设置测试用的 API Key（假的，用于测试配置）
export OPENAI_API_KEY="sk-test-fake-api-key-for-testing-only"

# 运行打包后的 jar
echo ""
echo "运行 Jimi CLI (--help)..."
java -jar target/jimi-cli-0.1.0-SNAPSHOT-exec.jar --help

echo ""
echo "运行 Jimi CLI (--version)..."
java -jar target/jimi-cli-0.1.0-SNAPSHOT-exec.jar --version

echo ""
echo "测试完成！"
echo ""
echo "要实际运行 REPL，请执行："
echo "  export OPENAI_API_KEY=sk-xxx"
echo "  java -jar target/jimi-cli-0.1.0-SNAPSHOT-exec.jar"
