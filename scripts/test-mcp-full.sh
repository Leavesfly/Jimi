#!/bin/bash

# 完整的端到端测试 - 包含实际任务执行

export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
cd "$(dirname "$0")"

echo "🚀 Starting Jimi MCP Server..."
echo

# 创建测试输入
cat > /tmp/mcp-test-input.jsonl << 'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"idea-plugin-test","version":"0.1.0"}}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"jimi_execute","arguments":{"input":"简单测试:输出hello world","workDir":"."}}}
EOF

echo "📤 Sending MCP requests..."
echo

# 执行测试
cat /tmp/mcp-test-input.jsonl | java -jar target/jimi-0.1.0.jar --mcp-server 2>&1 | tee /tmp/mcp-test-output.log

echo
echo "✅ Test completed. Check /tmp/mcp-test-output.log for details"
echo
echo "Summary:"
grep -c '"jsonrpc":"2.0"' /tmp/mcp-test-output.log && echo "responses received" || echo "No responses"

# 清理
rm -f /tmp/mcp-test-input.jsonl
