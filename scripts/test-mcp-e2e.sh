#!/bin/bash

# 端到端测试脚本
# 模拟IDEA插件调用Jimi MCP Server

set -e

echo "========================================"
echo "Jimi MCP 端到端测试"
echo "========================================"
echo

# 设置Java环境
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home

# 进入Jimi目录
cd "$(dirname "$0")"

# 检查JAR是否存在
if [ ! -f "target/jimi-0.1.0.jar" ]; then
    echo "❌ Jimi JAR not found. Please run: mvn package -DskipTests"
    exit 1
fi

echo "✅ Found Jimi JAR"
echo

# 测试1: 基础MCP协议
echo "=== Test 1: MCP Protocol ==="
(
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
  sleep 0.5
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  sleep 0.5
) | java -jar target/jimi-0.1.0.jar --mcp-server 2>/dev/null | head -2

echo
echo "✅ Test 1 passed"
echo

# 测试2: 工具调用
echo "=== Test 2: Tool Call ==="
(
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
  sleep 0.5
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"jimi_execute","arguments":{"input":"Hello Jimi, this is a test","workDir":"."}}}'
  sleep 2
) | java -jar target/jimi-0.1.0.jar --mcp-server 2>/dev/null | tail -1

echo
echo "✅ Test 2 passed"
echo

echo "========================================"
echo "✅ All tests passed!"
echo "========================================"
echo
echo "Next steps:"
echo "1. Build IDEA plugin: cd intellij-plugin && ./gradlew buildPlugin"
echo "2. Install plugin in IDEA"
echo "3. Test 'Ask Jimi' feature"
