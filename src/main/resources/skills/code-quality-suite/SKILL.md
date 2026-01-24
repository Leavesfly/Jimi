---
name: code-quality-suite
description: 代码质量综合技能包（代码审查 + 单元测试）
version: 1.0.0
category: development
triggers:
  - code quality suite
  - 代码质量整套
  - 代码审查和单元测试
  - 审查与测试
dependencies:
  - code-review
  - unit-testing
---

# 代码质量综合技能包

当你需要同时关注 **代码审查质量** 和 **单元测试质量** 时，启用本技能包。

本技能包会自动组合以下已有技能：

1. `code-review`：代码审查最佳实践清单
2. `unit-testing`：单元测试编写与覆盖率提升指南

## 使用建议

- 在提交重要改动前，请按照以下顺序进行自检：
  1. 按 `code-review` 清单检查代码设计、可维护性与潜在缺陷；
  2. 按 `unit-testing` 指南补齐关键路径、边界条件与异常场景的单测；
- 输出建议采用结构化格式，将“审查结果”和“测试说明”分别列出，以便后续追踪。

## 助手行为期望

当本技能包被激活时，助手应：

- 先根据 `code-review` 给出针对代码质量的审查建议；
- 再根据 `unit-testing` 给出单元测试补充与改进建议；
- 在回答中明确区分：
  - 代码层面的修改建议；
  - 测试层面的补充与修正建议。
