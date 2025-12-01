package io.leavesfly.jimi.graph.model;

/**
 * 代码实体类型枚举
 * <p>
 * 定义代码库中的各种实体类型
 */
public enum EntityType {
    /**
     * 包
     */
    PACKAGE,
    
    /**
     * 文件
     */
    FILE,
    
    /**
     * 类
     */
    CLASS,
    
    /**
     * 接口
     */
    INTERFACE,
    
    /**
     * 枚举
     */
    ENUM,
    
    /**
     * 方法
     */
    METHOD,
    
    /**
     * 构造函数
     */
    CONSTRUCTOR,
    
    /**
     * 字段
     */
    FIELD,
    
    /**
     * 注解
     */
    ANNOTATION
}
