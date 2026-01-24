---
name: backend-dev-suite
description: 后端开发综合技能包（Java 编码 + 数据库设计 + 安全加固）
version: 1.0.0
category: development
triggers:
  - backend dev suite
  - 后端开发整套
  - 后端最佳实践
  - Java 后端综合
  - 后端代码与数据库
dependencies:
  - java-best-practices
  - database-design
  - security-checklist
---

# 后端开发综合技能包

当你在进行后端功能开发或重构时，需要同时关注 **Java 编码规范**、**数据库设计** 与 **安全加固**，请启用本技能包。

本技能包会自动组合以下已有技能：

1. `java-best-practices`：Java 编码规范、设计模式与异常/并发最佳实践；
2. `database-design`：表结构设计、索引策略与 SQL 优化指南；
3. `security-checklist`：基于 OWASP 的安全检查清单与防护示例。

## 使用建议

- 在设计或修改后端接口/服务时：
  1. 先根据 `java-best-practices` 规划代码结构与异常处理；
  2. 再根据 `database-design` 设计数据模型和索引策略，避免 N+1、字段类型不当等问题；
  3. 最后按 `security-checklist` 检查认证授权、输入校验、敏感信息保护等安全要点。
- 回答时建议分三块输出：编码层面、数据层面、安全层面，方便你逐项检查和落地。
