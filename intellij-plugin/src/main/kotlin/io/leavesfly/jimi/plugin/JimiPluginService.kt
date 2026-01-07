package io.leavesfly.jimi.plugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Jimi 插件服务 - Less is more
 * 通过简单的 JSON 行协议与 Jimi 进程通信
 */
@Service(Service.Level.PROJECT)
class JimiPluginService(private val project: Project) : Disposable {
    
    companion object {
        fun getInstance(project: Project): JimiPluginService {
            return project.getService(JimiPluginService::class.java)
        }
    }
    
    private val projectPath: String get() = project.basePath ?: "."
    private val mapper = jacksonObjectMapper()
    
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    
    /**
     * 启动 Jimi 服务
     */
    @Synchronized
    fun start(): Boolean {
        if (process?.isAlive == true) return true
        
        return try {
            val jimiJar = findJimiJar() ?: run {
                println("[Jimi] ERROR: JAR not found")
                return false
            }
            
            val pb = ProcessBuilder(
                "java", 
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-jar", jimiJar.absolutePath, 
                "--simple-server"
            ).directory(File(projectPath))
            
            // 设置 JAVA_HOME
            findJavaHome()?.let { pb.environment()["JAVA_HOME"] = it }
            
            process = pb.start()
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream, StandardCharsets.UTF_8))
            reader = BufferedReader(InputStreamReader(process!!.inputStream, StandardCharsets.UTF_8))
            
            Thread.sleep(2000) // 等待进程启动
            
            println("[Jimi] Service started")
            true
            
        } catch (e: Exception) {
            println("[Jimi] ERROR: ${e.message}")
            cleanup()
            false
        }
    }
    
    /**
     * 执行任务（流式输出）
     */
    fun executeTask(input: String, onChunk: (String) -> Unit): String? {
        if (!start()) {
            onChunk("Error: Failed to start Jimi service")
            return "Error: Failed to start Jimi service"
        }
        
        return try {
            val request = mapOf("input" to input, "workDir" to projectPath)
            val requestJson = mapper.writeValueAsString(request)
            println("[Jimi] Sending request: $requestJson")
            
            synchronized(this) {
                writer?.write(requestJson)
                writer?.newLine()
                writer?.flush()
                println("[Jimi] Request sent, waiting for response...")
                
                // 流式读取
                var error: String? = null
                while (true) {
                    val line = reader?.readLine()
                    println("[Jimi] Received line: ${line?.take(100)}...")
                    if (line == null) break
                    val response: Map<String, Any?> = mapper.readValue(line)
                    
                    // 处理 chunk
                    response["chunk"]?.toString()?.let { onChunk(it) }
                    
                    // 检查错误
                    response["error"]?.toString()?.let { error = it }
                    
                    // 检查完成
                    if (response["done"] == true) break
                }
                error
            }
        } catch (e: Exception) {
            onChunk("Error: ${e.message}")
            "Error: ${e.message}"
        }
    }
    
    /**
     * 执行任务（同步，简单版）
     */
    fun executeTask(input: String): String {
        val result = StringBuilder()
        val error = executeTask(input) { result.append(it) }
        return if (error != null) "Error: $error" else result.toString()
    }
    
    /**
     * 查找 Jimi JAR
     */
    private fun findJimiJar(): File? {
        listOf(
            File(projectPath, "../Jimi/target/jimi-0.1.0.jar"),
            File(System.getProperty("user.home"), ".jimi/jimi-0.1.0.jar")
        ).forEach { if (it.exists()) return it }
        return null
    }
    
    /**
     * 查找 Java 17+
     */
    private fun findJavaHome(): String? {
        System.getenv("JAVA_HOME")?.takeIf { File(it).exists() }?.let { return it }
        
        try {
            val proc = ProcessBuilder("/usr/libexec/java_home", "-v", "17").start()
            val result = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0 && result.isNotEmpty()) return result
        } catch (_: Exception) {}
        
        return null
    }
    
    private fun cleanup() {
        try {
            writer?.close()
            reader?.close()
            process?.let {
                it.destroy()
                if (!it.waitFor(3, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                    println("[Jimi] Process force killed")
                }
            }
        } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
    }
    
    override fun dispose() {
        println("[Jimi] Disposing service, killing process...")
        cleanup()
    }
}
