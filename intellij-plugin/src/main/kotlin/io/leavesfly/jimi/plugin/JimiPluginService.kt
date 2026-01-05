package io.leavesfly.jimi.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Jimi插件服务
 */
@Service(Service.Level.PROJECT)
class JimiPluginService(private val project: Project) {
    
    private val logger = Logger.getInstance(JimiPluginService::class.java)
    
    private var processManager: JimiProcessManager? = null
    private var mcpClient: McpClient? = null
    private var isStarted = false
    
    companion object {
        fun getInstance(project: Project): JimiPluginService {
            return project.getService(JimiPluginService::class.java)
        }
    }
    
    /**
     * 启动Jimi服务
     */
    fun start(): Boolean {
        if (isStarted) {
            return true
        }
        
        try {
            logger.info("Starting Jimi service...")
            
            // 查找Jimi JAR
            val jimiJar = findJimiJar()
            if (jimiJar == null) {
                logger.error("Jimi JAR not found")
                return false
            }
            
            logger.info("Found Jimi JAR: $jimiJar")
            
            // 启动进程
            processManager = JimiProcessManager()
            val process = processManager!!.start(jimiJar, project.basePath ?: ".")
            
            // 等待启动
            Thread.sleep(2000)
            
            // 创建MCP客户端
            mcpClient = McpClient(process)
            
            // 初始化
            val initResult = mcpClient!!.initialize()
            logger.info("MCP initialized: ${initResult.serverInfo}")
            
            isStarted = true
            logger.info("Jimi service started")
            true
            
        } catch (e: Exception) {
            logger.error("Failed to start Jimi", e)
            cleanup()
            false
        }
    }
    
    /**
     * 执行任务
     */
    fun executeTask(input: String): String {
        if (!isStarted) {
            if (!start()) {
                return "Error: Failed to start Jimi service"
            }
        }
        
        return try {
            val result = mcpClient!!.callTool(
                "jimi_execute",
                mapOf(
                    "input" to input,
                    "workDir" to (project.basePath ?: ".")
                )
            )
            
            result.content.firstOrNull()?.text ?: "No response"
            
        } catch (e: Exception) {
            logger.error("Task failed", e)
            "Error: ${e.message}"
        }
    }
    
    /**
     * 查找Jimi JAR
     */
    private fun findJimiJar(): File? {
        // 1. 检查项目相邻目录
        val projectJar = File(project.basePath, "../Jimi/target/jimi-0.1.0.jar")
        if (projectJar.exists()) {
            return projectJar
        }
        
        // 2. 检查用户目录
        val userHome = System.getProperty("user.home")
        val homeJar = File(userHome, ".jimi/jimi-0.1.0.jar")
        if (homeJar.exists()) {
            return homeJar
        }
        
        return null
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        isStarted = false
        mcpClient?.close()
        mcpClient = null
        processManager?.stop()
        processManager = null
    }
    
    fun dispose() {
        cleanup()
    }
}
