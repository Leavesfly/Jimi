package io.leavesfly.jimi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.agent.AgentSpec;
import io.leavesfly.jimi.soul.approval.Approval;
import io.leavesfly.jimi.soul.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.soul.runtime.Runtime;
import io.leavesfly.jimi.tool.bash.Bash;
import io.leavesfly.jimi.tool.file.*;
import io.leavesfly.jimi.tool.task.Task;
import io.leavesfly.jimi.tool.think.Think;
import io.leavesfly.jimi.tool.todo.SetTodoList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

/**
 * ToolRegistry 工厂类
 * 负责创建配置好的 ToolRegistry 实例
 * 使用 Spring 容器获取 Tool 原型 Bean
 */
@Slf4j
@Service
public class ToolRegistryFactory {
    
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    
    @Autowired
    public ToolRegistryFactory(ApplicationContext applicationContext, ObjectMapper objectMapper) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
    }
    
    /**
     * 创建标准工具注册表
     * 包含所有内置工具
     * 
     * @param builtinArgs 内置系统提示词参数
     * @param approval    审批对象
     * @return 配置好的 ToolRegistry 实例
     */
    public ToolRegistry createStandardRegistry(BuiltinSystemPromptArgs builtinArgs, Approval approval) {
        ToolRegistry registry = new ToolRegistry(objectMapper);
        
        // 注册文件工具
        registry.register(createReadFile(builtinArgs));
        registry.register(createWriteFile(builtinArgs, approval));
        registry.register(createStrReplaceFile(builtinArgs, approval));
        registry.register(createGlob(builtinArgs));
        registry.register(createGrep(builtinArgs));
        registry.register(createPatchFile(builtinArgs, approval));
        
        // 注册 Bash 工具
        registry.register(createBash(approval));
        
        // 注册 Think 工具
        registry.register(createThink());
        
        // 注册 Todo 工具
        registry.register(createSetTodoList());
        
        log.info("Created standard tool registry with {} tools", registry.getToolNames().size());
        return registry;
    }
    
    /**
     * 创建 ReadFile 工具实例
     */
    private ReadFile createReadFile(BuiltinSystemPromptArgs builtinArgs) {
        ReadFile tool = applicationContext.getBean(ReadFile.class);
        tool.setBuiltinArgs(builtinArgs);
        return tool;
    }
    
    /**
     * 创建 WriteFile 工具实例
     */
    private WriteFile createWriteFile(BuiltinSystemPromptArgs builtinArgs, Approval approval) {
        WriteFile tool = applicationContext.getBean(WriteFile.class);
        tool.setBuiltinArgs(builtinArgs);
        tool.setApproval(approval);
        return tool;
    }
    
    /**
     * 创建 StrReplaceFile 工具实例
     */
    private StrReplaceFile createStrReplaceFile(BuiltinSystemPromptArgs builtinArgs, Approval approval) {
        StrReplaceFile tool = applicationContext.getBean(StrReplaceFile.class);
        tool.setBuiltinArgs(builtinArgs);
        tool.setApproval(approval);
        return tool;
    }
    
    /**
     * 创建 Glob 工具实例
     */
    private Glob createGlob(BuiltinSystemPromptArgs builtinArgs) {
        Glob tool = applicationContext.getBean(Glob.class);
        tool.setBuiltinArgs(builtinArgs);
        return tool;
    }
    
    /**
     * 创建 Grep 工具实例
     */
    private Grep createGrep(BuiltinSystemPromptArgs builtinArgs) {
        Grep tool = applicationContext.getBean(Grep.class);
        tool.setBuiltinArgs(builtinArgs);
        return tool;
    }
    
    /**
     * 创建 PatchFile 工具实例
     */
    private PatchFile createPatchFile(BuiltinSystemPromptArgs builtinArgs, Approval approval) {
        PatchFile tool = applicationContext.getBean(PatchFile.class);
        tool.setBuiltinArgs(builtinArgs);
        tool.setApproval(approval);
        return tool;
    }
    
    /**
     * 创建 Bash 工具实例
     */
    private Bash createBash(Approval approval) {
        Bash tool = applicationContext.getBean(Bash.class);
        tool.setApproval(approval);
        return tool;
    }
    
    /**
     * 创建 Think 工具实例
     */
    private Think createThink() {
        return applicationContext.getBean(Think.class);
    }
    
    /**
     * 创建 SetTodoList 工具实例
     */
    private SetTodoList createSetTodoList() {
        return applicationContext.getBean(SetTodoList.class);
    }
    
    /**
     * 创建 Task 工具实例
     * Task 工具需要 AgentSpec 和 Runtime 参数
     * 
     * @param agentSpec Agent 规范
     * @param runtime   运行时对象
     * @return 配置好的 Task 工具实例
     */
    public Task createTask(AgentSpec agentSpec, Runtime runtime) {
        Task tool = applicationContext.getBean(Task.class);
        tool.setRuntimeParams(agentSpec, runtime);
        return tool;
    }
}
