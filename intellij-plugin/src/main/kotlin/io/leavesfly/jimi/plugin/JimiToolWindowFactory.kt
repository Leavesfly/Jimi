package io.leavesfly.jimi.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.*
import java.awt.BorderLayout

/**
 * Jimi工具窗口工厂
 */
class JimiToolWindowFactory : ToolWindowFactory {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JimiToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Jimi工具窗口面板
 */
class JimiToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val textArea = JTextArea()
    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    
    init {
        // 输出区域
        textArea.isEditable = false
        textArea.text = "Jimi AI Assistant\n\nType your request below and press Send.\n"
        add(JScrollPane(textArea), BorderLayout.CENTER)
        
        // 输入区域
        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)
        add(inputPanel, BorderLayout.SOUTH)
        
        // 发送按钮事件
        sendButton.addActionListener {
            val input = inputField.text
            if (input.isNotBlank()) {
                handleSend(input)
                inputField.text = ""
            }
        }
        
        // 回车发送
        inputField.addActionListener {
            sendButton.doClick()
        }
    }
    
    private fun handleSend(input: String) {
        textArea.append("\n>>> $input\n")
        
        Thread {
            try {
                val service = JimiPluginService.getInstance(project)
                val response = service.executeTask(input)
                
                SwingUtilities.invokeLater {
                    textArea.append("$response\n")
                    textArea.caretPosition = textArea.document.length
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    textArea.append("Error: ${e.message}\n")
                }
            }
        }.start()
    }
}
