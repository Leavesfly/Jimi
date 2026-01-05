#!/bin/bash

# MCP Server 测试脚本
# 测试基础协议功能

echo "Testing Jimi MCP Server..."
echo

# 测试1: initialize
echo "=== Test 1: Initialize ==="
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' | \
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && \
  java -jar target/jimi-0.1.0.jar --mcp-server

echo
echo "=== Test 2: List Tools ==="
(
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
  sleep 0.5
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
) | \
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && \
  java -jar target/jimi-0.1.0.jar --mcp-server

echo
echo "Done!"
