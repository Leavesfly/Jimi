#!/bin/bash

# CLI REPL 命令测试
# 这个脚本通过管道输入模拟 REPL 交互

echo "=== Jimi CLI REPL 命令测试 ==="
echo ""

# 设置测试环境
export OPENAI_API_KEY="sk-test-fake-key"

# 创建测试用的命令序列
TEST_COMMANDS=$(cat <<EOF
/help
/version
/tools
/config
/status
/theme
/theme dark
/clear
/exit
EOF
)

echo "测试命令序列："
echo "$TEST_COMMANDS"
echo ""
echo "开始测试..."
echo ""

# 通过管道输入命令
echo "$TEST_COMMANDS" | java -jar target/jimi-cli-0.1.0-SNAPSHOT-exec.jar 2>&1 | grep -v "logback" | grep -v "|-INFO" | grep -v "|-WARN" | grep -v "|-ERROR"

echo ""
echo "测试完成！"
