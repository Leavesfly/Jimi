---
name: md-test-agent
description: A test agent in Claude Code markdown format
tools: ReadFile, WriteFile
model: test-model
maxTurns: 10
disallowedTools: BashTool
---

You are a test agent defined in Claude Code markdown format.
Your job is to verify that the markdown format is correctly parsed.

## Test Context

This agent is used by AgentModuleAdapterTest and AgentSpecLoaderTest to verify:
1. YAML frontmatter is correctly extracted and parsed
2. The Markdown body becomes the inline system prompt
3. Comma-separated tools string is converted to a list
4. The `disallowedTools` field is recognized
5. The `maxTurns` field is recognized
