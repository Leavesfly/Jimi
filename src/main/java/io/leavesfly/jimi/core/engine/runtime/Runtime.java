package io.leavesfly.jimi.core.engine.runtime;

import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.core.session.SessionManager;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.core.interaction.approval.Approval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Runtime 运行时上下文
 * 包含 Agent 运行所需的所有全局状态和服务
 * 
 * 功能特性：
 * 1. 配置管理（JimiConfig）
 * 2. LLM 实例（LLM）
 * 3. 会话信息（Session）
 * 4. 内置参数（BuiltinSystemPromptArgs）
 * 5. 审批服务（Approval）
 * 
 * 设计理念：
 * - Runtime 自行构建内置参数，封装环境信息收集逻辑
 * - 对外提供 Builder，简化创建过程
 */
@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Runtime {

    /**
     * 全局配置
     */
    private JimiConfig config;

    /**
     * LLM 实例
     */
    private LLM llm;

    /**
     * 会话信息
     */
    private Session session;

    /**
     * 内置系统提示词参数
     */
    private BuiltinSystemPromptArgs builtinArgs;


    /**
     * 审批机制
     */
    private Approval approval;

    /**
     * 获取工作目录
     */
    public Path getWorkDir() {
        return session.getWorkDir();
    }

    /**
     * 获取会话 ID
     */
    public String getSessionId() {
        return session.getId();
    }

    /**
     * 检查是否为YOLO模式
     */
    public boolean isYoloMode() {
        return approval != null && approval.isYolo();
    }
    
    // ==================== Builder 模式 ====================
    
    /**
     * 创建 Runtime Builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Runtime 构建器
     * 负责构建 BuiltinSystemPromptArgs，封装环境信息收集逻辑
     */
    public static class Builder {
        private JimiConfig config;
        private LLM llm;
        private Session session;
        private Approval approval;
        private SessionManager sessionManager;  // 用于加载 AGENTS.md
        
        public Builder config(JimiConfig config) {
            this.config = config;
            return this;
        }
        
        public Builder llm(LLM llm) {
            this.llm = llm;
            return this;
        }
        
        public Builder session(Session session) {
            this.session = session;
            return this;
        }
        
        public Builder approval(Approval approval) {
            this.approval = approval;
            return this;
        }
        
        /**
         * 设置 SessionManager（用于构建 builtinArgs）
         */
        public Builder sessionManager(SessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }
        
        /**
         * 构建 Runtime 实例
         * 自动创建 BuiltinSystemPromptArgs
         */
        public Runtime build() {
            if (session == null) {
                throw new IllegalArgumentException("session is required");
            }
            
            // 自动构建 builtinArgs
            BuiltinSystemPromptArgs builtinArgs = createBuiltinArgs();
            
            return new Runtime(config, llm, session, builtinArgs, approval);
        }
        
        /**
         * 构建内置系统提示词参数
         * 封装环境信息收集逻辑：当前时间、工作目录、文件列表、Agent文档
         */
        private BuiltinSystemPromptArgs createBuiltinArgs() {
            String now = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            Path workDir = session.getWorkDir().toAbsolutePath();
            
            // 列出工作目录文件列表（非递归）
            StringBuilder lsBuilder = new StringBuilder();
            try {
                Files.list(workDir).forEach(p -> {
                    String type = Files.isDirectory(p) ? "dir" : "file";
                    lsBuilder.append(type).append("  ").append(p.getFileName().toString()).append("\n");
                });
            } catch (Exception e) {
                log.warn("Failed to list work dir: {}", workDir, e);
            }
            String workDirLs = lsBuilder.toString().trim();
            
            // 从 SessionManager 缓存加载 AGENTS.md（避免重复 I/O）
            String agentsMd = sessionManager != null 
                ? sessionManager.loadAgentsMd(workDir) 
                : "";
            
            return BuiltinSystemPromptArgs.builder()
                    .jimiNow(now)
                    .jimiWorkDir(workDir)
                    .jimiWorkDirLs(workDirLs)
                    .jimiAgentsMd(agentsMd)
                    .build();
        }
    }
}
