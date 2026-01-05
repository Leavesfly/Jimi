package io.leavesfly.jimi.plugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Jimi进程管理器
 */
class JimiProcessManager {
    
    private val logger = Logger.getInstance(JimiProcessManager::class.java)
    private var process: Process? = null
    
    /**
     * 启动Jimi MCP Server进程
     */
    fun start(jimiJar: File, workDir: String): Process {
        logger.info("Starting Jimi process: $jimiJar")
        
        val command = GeneralCommandLine(
            "java",
            "-jar",
            jimiJar.absolutePath,
            "--mcp-server"
        ).apply {
            setWorkDirectory(workDir)
            environment["JAVA_HOME"] = "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
        }
        
        process = command.createProcess()
        
        logger.info("Jimi process started: PID=${process?.pid()}")
        
        return process!!
    }
    
    /**
     * 停止进程
     */
    fun stop() {
        process?.let {
            logger.info("Stopping Jimi process...")
            it.destroy()
            
            if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warn("Force killing Jimi process")
                it.destroyForcibly()
            }
            
            logger.info("Jimi process stopped")
        }
        
        process = null
    }
    
    /**
     * 检查进程是否运行
     */
    fun isRunning(): Boolean = process?.isAlive == true
}
