package io.leavesfly.jimi.adk.knowledge.graph.parser;

import io.leavesfly.jimi.adk.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.adk.knowledge.graph.model.CodeRelation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AST 解析结果
 * <p>
 * 封装从代码文件中解析出的实体和关系
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {
    
    /**
     * 解析出的代码实体列表
     */
    @Builder.Default
    private List<CodeEntity> entities = new ArrayList<>();
    
    /**
     * 解析出的代码关系列表
     */
    @Builder.Default
    private List<CodeRelation> relations = new ArrayList<>();
    
    /**
     * 符号表 (名称 -> 全限定名)
     */
    @Builder.Default
    private Map<String, String> symbolTable = new HashMap<>();
    
    /**
     * 源文件路径
     */
    private String filePath;
    
    /**
     * 解析是否成功
     */
    @Builder.Default
    private Boolean success = true;
    
    /**
     * 错误信息 (如果解析失败)
     */
    private String errorMessage;
    
    /**
     * 添加实体
     */
    public void addEntity(CodeEntity entity) {
        if (entities == null) {
            entities = new ArrayList<>();
        }
        entities.add(entity);
    }
    
    /**
     * 添加关系
     */
    public void addRelation(CodeRelation relation) {
        if (relations == null) {
            relations = new ArrayList<>();
        }
        relations.add(relation);
    }
    
    /**
     * 添加符号
     */
    public void addSymbol(String name, String qualifiedName) {
        if (symbolTable == null) {
            symbolTable = new HashMap<>();
        }
        symbolTable.put(name, qualifiedName);
    }
    
    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format("Entities: %d, Relations: %d, Symbols: %d", 
            entities != null ? entities.size() : 0,
            relations != null ? relations.size() : 0,
            symbolTable != null ? symbolTable.size() : 0);
    }
    
    /**
     * 创建失败结果
     */
    public static ParseResult failure(String filePath, String errorMessage) {
        return ParseResult.builder()
            .filePath(filePath)
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }
}
