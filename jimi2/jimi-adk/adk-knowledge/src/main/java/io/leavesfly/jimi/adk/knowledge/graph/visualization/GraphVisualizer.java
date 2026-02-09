package io.leavesfly.jimi.adk.knowledge.graph.visualization;

import io.leavesfly.jimi.adk.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.adk.knowledge.graph.model.CodeRelation;
import io.leavesfly.jimi.adk.knowledge.graph.model.EntityType;
import io.leavesfly.jimi.adk.knowledge.graph.model.RelationType;
import io.leavesfly.jimi.adk.knowledge.graph.store.CodeGraphStore;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图可视化器
 * <p>
 * 将代码图导出为可视化格式 (Mermaid, DOT)
 */
@Slf4j
public class GraphVisualizer {
    
    private final CodeGraphStore graphStore;
    
    public GraphVisualizer(CodeGraphStore graphStore) {
        this.graphStore = graphStore;
    }
    
    /**
     * 导出为 Mermaid 格式
     *
     * @param entityIds 要导出的实体ID列表 (null表示全部)
     * @param relationTypes 要包含的关系类型 (null表示全部)
     * @param maxNodes 最大节点数
     * @return Mermaid 图表代码
     */
    public Mono<String> exportToMermaid(List<String> entityIds, 
                                       Set<RelationType> relationTypes,
                                       int maxNodes) {
        return Mono.fromCallable(() -> {
            StringBuilder mermaid = new StringBuilder();
            mermaid.append("```mermaid\n");
            mermaid.append("graph TD\n");
            
            List<CodeEntity> entities;
            if (entityIds != null && !entityIds.isEmpty()) {
                entities = entityIds.stream()
                    .map(id -> graphStore.getEntity(id).block())
                    .filter(e -> e != null)
                    .limit(maxNodes)
                    .collect(Collectors.toList());
            } else {
                // 获取所有实体 (限制数量)
                entities = graphStore.getStats()
                    .map(stats -> graphStore.getEntitiesByType(EntityType.CLASS).block())
                    .block();
                if (entities != null) {
                    entities = entities.stream().limit(maxNodes).collect(Collectors.toList());
                }
            }
            
            if (entities == null || entities.isEmpty()) {
                mermaid.append("    empty[\"No entities to display\"]\n");
                mermaid.append("```\n");
                return mermaid.toString();
            }
            
            // 添加节点
            for (CodeEntity entity : entities) {
                String nodeId = sanitizeId(entity.getId());
                String label = entity.getName();
                String shape = getNodeShape(entity.getType());
                
                mermaid.append(String.format("    %s%s%s%s\n", 
                    nodeId, shape.charAt(0), label, shape.charAt(1)));
            }
            
            // 添加边
            for (CodeEntity entity : entities) {
                List<CodeRelation> relations = graphStore.getRelationsBySource(entity.getId()).block();
                if (relations != null) {
                    for (CodeRelation relation : relations) {
                        if (relationTypes == null || relationTypes.contains(relation.getType())) {
                            // 检查目标实体是否在实体列表中
                            boolean targetInList = entities.stream()
                                .anyMatch(e -> e.getId().equals(relation.getTargetId()));
                            
                            if (targetInList) {
                                String sourceId = sanitizeId(relation.getSourceId());
                                String targetId = sanitizeId(relation.getTargetId());
                                String edgeStyle = getEdgeStyle(relation.getType());
                                
                                mermaid.append(String.format("    %s %s %s\n",
                                    sourceId, edgeStyle, targetId));
                            }
                        }
                    }
                }
            }
            
            mermaid.append("```\n");
            return mermaid.toString();
        });
    }
    
    /**
     * 导出类继承图
     *
     * @param classEntityId 类实体ID
     * @param includeInterfaces 是否包含接口
     * @return Mermaid 图表代码
     */
    public Mono<String> exportClassHierarchyToMermaid(String classEntityId, boolean includeInterfaces) {
        return Mono.fromCallable(() -> {
            StringBuilder mermaid = new StringBuilder();
            mermaid.append("```mermaid\n");
            mermaid.append("classDiagram\n");
            
            CodeEntity classEntity = graphStore.getEntity(classEntityId).block();
            if (classEntity == null) {
                mermaid.append("```\n");
                return mermaid.toString();
            }
            
            // 添加中心类
            addClassToMermaid(mermaid, classEntity);
            
            // 添加父类
            List<CodeRelation> extendsRelations = graphStore.getRelationsBySource(classEntityId).block();
            if (extendsRelations != null) {
                for (CodeRelation relation : extendsRelations) {
                    if (relation.getType() == RelationType.EXTENDS) {
                        CodeEntity parent = graphStore.getEntity(relation.getTargetId()).block();
                        if (parent != null) {
                            addClassToMermaid(mermaid, parent);
                            mermaid.append(String.format("    %s <|-- %s\n",
                                sanitizeId(parent.getName()),
                                sanitizeId(classEntity.getName())));
                        }
                    }
                    
                    if (includeInterfaces && relation.getType() == RelationType.IMPLEMENTS) {
                        CodeEntity iface = graphStore.getEntity(relation.getTargetId()).block();
                        if (iface != null) {
                            addClassToMermaid(mermaid, iface);
                            mermaid.append(String.format("    %s <|.. %s\n",
                                sanitizeId(iface.getName()),
                                sanitizeId(classEntity.getName())));
                        }
                    }
                }
            }
            
            // 添加子类
            List<CodeRelation> extendedByRelations = graphStore.getRelationsByTarget(classEntityId).block();
            if (extendedByRelations != null) {
                for (CodeRelation relation : extendedByRelations) {
                    if (relation.getType() == RelationType.EXTENDS) {
                        CodeEntity child = graphStore.getEntity(relation.getSourceId()).block();
                        if (child != null) {
                            addClassToMermaid(mermaid, child);
                            mermaid.append(String.format("    %s <|-- %s\n",
                                sanitizeId(classEntity.getName()),
                                sanitizeId(child.getName())));
                        }
                    }
                }
            }
            
            mermaid.append("```\n");
            return mermaid.toString();
        });
    }
    
    /**
     * 导出调用图
     *
     * @param methodEntityId 方法实体ID
     * @param depth 深度
     * @return Mermaid 图表代码
     */
    public Mono<String> exportCallGraphToMermaid(String methodEntityId, int depth) {
        return Mono.fromCallable(() -> {
            StringBuilder mermaid = new StringBuilder();
            mermaid.append("```mermaid\n");
            mermaid.append("graph LR\n");
            
            CodeEntity method = graphStore.getEntity(methodEntityId).block();
            if (method == null) {
                mermaid.append("```\n");
                return mermaid.toString();
            }
            
            // 添加中心方法
            String methodNode = sanitizeId(method.getId());
            mermaid.append(String.format("    %s[\"%s\"]\n", methodNode, method.getName()));
            
            // 递归添加调用关系
            addCallRelationsToMermaid(mermaid, method.getId(), depth, new HashSet<>());
            
            mermaid.append("```\n");
            return mermaid.toString();
        });
    }
    
    // ==================== 私有辅助方法 ====================
    
    private void addClassToMermaid(StringBuilder mermaid, CodeEntity classEntity) {
        String className = sanitizeId(classEntity.getName());
        mermaid.append(String.format("    class %s{\n", className));
        
        // 可以添加字段和方法 (简化版本)
        mermaid.append("    }\n");
    }
    
    private void addCallRelationsToMermaid(StringBuilder mermaid, String methodId, 
                                          int remainingDepth, Set<String> visited) {
        if (remainingDepth <= 0 || visited.contains(methodId)) {
            return;
        }
        
        visited.add(methodId);
        
        List<CodeRelation> relations = graphStore.getRelationsBySource(methodId).block();
        if (relations != null) {
            for (CodeRelation relation : relations) {
                if (relation.getType() == RelationType.CALLS) {
                    CodeEntity callee = graphStore.getEntity(relation.getTargetId()).block();
                    if (callee != null) {
                        String sourceNode = sanitizeId(methodId);
                        String targetNode = sanitizeId(relation.getTargetId());
                        
                        mermaid.append(String.format("    %s[\"%s\"]\n", targetNode, callee.getName()));
                        mermaid.append(String.format("    %s --> %s\n", sourceNode, targetNode));
                        
                        addCallRelationsToMermaid(mermaid, relation.getTargetId(), 
                                                remainingDepth - 1, visited);
                    }
                }
            }
        }
    }
    
    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }
    
    private String getNodeShape(EntityType type) {
        return switch (type) {
            case CLASS -> "[]";
            case INTERFACE -> "()";
            case METHOD, CONSTRUCTOR -> "(())";
            case FIELD -> "[[]]";
            case ENUM -> "{}";
            default -> "[]";
        };
    }
    
    private String getEdgeStyle(RelationType type) {
        return switch (type) {
            case EXTENDS -> "--|>";
            case IMPLEMENTS -> "..|>";
            case CALLS -> "-->";
            case REFERENCES -> "-.->"; 
            default -> "-->";
        };
    }
}
