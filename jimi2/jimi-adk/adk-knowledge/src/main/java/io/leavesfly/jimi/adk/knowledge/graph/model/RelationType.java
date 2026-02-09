package io.leavesfly.jimi.adk.knowledge.graph.model;

/**
 * 代码关系类型枚举
 * <p>
 * 定义代码实体之间的各种关系类型
 */
public enum RelationType {
    /** 包含关系 (如: 文件包含类, 类包含方法) */
    CONTAINS,
    /** 导入关系 */
    IMPORTS,
    /** 继承关系 */
    EXTENDS,
    /** 实现接口关系 */
    IMPLEMENTS,
    /** 方法调用关系 */
    CALLS,
    /** 字段引用关系 */
    REFERENCES,
    /** 方法覆写关系 */
    OVERRIDES,
    /** 类型使用关系 (如: 方法参数类型, 返回值类型) */
    USES_TYPE,
    /** 注解标注关系 */
    ANNOTATED_WITH,
    /** 抛出异常关系 */
    THROWS
}
