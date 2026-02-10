package io.leavesfly.jimi.adk.core.tool;

import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.tool.ToolSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册表默认实现
 */
@Slf4j
public class DefaultToolRegistry implements ToolRegistry {
    
    /**
     * 工具映射（名称 -> 工具）
     */
    private final Map<String, Tool<?>> tools;
    
    /**
     * 对象映射器
     */
    private final ObjectMapper objectMapper;
    
    /**
     * Schema 生成器
     */
    private final ToolSchemaGenerator schemaGenerator;
    
    public DefaultToolRegistry(ObjectMapper objectMapper) {
        this.tools = new ConcurrentHashMap<>();
        this.objectMapper = objectMapper;
        this.schemaGenerator = new ToolSchemaGenerator(objectMapper);
    }
    
    @Override
    public void register(Tool<?> tool) {
        if (tool != null && tool.getName() != null) {
            tools.put(tool.getName(), tool);
            log.debug("注册工具: {}", tool.getName());
        }
    }
    
    @Override
    public void registerAll(Collection<Tool<?>> toolList) {
        if (toolList != null) {
            toolList.forEach(this::register);
        }
    }
    
    @Override
    public Optional<Tool<?>> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }
    
    @Override
    public Set<String> getToolNames() {
        return new HashSet<>(tools.keySet());
    }
    
    @Override
    public Collection<Tool<?>> getAllTools() {
        return new ArrayList<>(tools.values());
    }
    
    @Override
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
    
    @Override
    public List<ToolSchema> getToolSchemas(List<String> includeTools) {
        Collection<Tool<?>> toolsToInclude;
        
        if (includeTools == null || includeTools.isEmpty()) {
            toolsToInclude = tools.values();
        } else {
            toolsToInclude = includeTools.stream()
                    .map(tools::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        
        return toolsToInclude.stream()
                .map(schemaGenerator::generateSchema)
                .collect(Collectors.toList());
    }
    
    @Override
    public void unregister(String toolName) {
        Tool<?> removed = tools.remove(toolName);
        if (removed != null) {
            log.debug("注销工具: {}", toolName);
        }
    }
    
    @Override
    public void clear() {
        tools.clear();
        log.debug("清空所有工具");
    }
}
