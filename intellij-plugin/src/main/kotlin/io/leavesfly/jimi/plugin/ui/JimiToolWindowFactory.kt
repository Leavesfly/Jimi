package io.leavesfly.jimi.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.leavesfly.jimi.plugin.JimiPluginService
import java.awt.*
import javax.swing.*
import javax.swing.text.*

class JimiToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JimiToolWindowPanel(project)
        val contentFactory = ApplicationManager.getApplication().getService(ContentFactory::class.java)
        val content = contentFactory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class JimiToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val outputPane = JTextPane().apply {
        isEditable = false
        background = Color.WHITE
        border = JBUI.Borders.empty(8)
    }
    
    private val styledDoc = outputPane.styledDocument
    private val styles = mutableMapOf<String, Style>()
    
    private val inputField = JTextField().apply {
        font = Font("Monospaced", Font.PLAIN, 13)
        border = JBUI.Borders.empty(8)
    }
    
    private val sendButton = JButton("Send").apply {
        preferredSize = Dimension(80, 32)
    }
    
    private val clearButton = JButton("Clear").apply {
        preferredSize = Dimension(70, 32)
    }
    
    init {
        initStyles()
        setupUI()
        sendButton.addActionListener { executeInput() }
        clearButton.addActionListener { clearOutput() }
        inputField.addActionListener { executeInput() }
        showWelcomeMessage()
    }
    
    private fun initStyles() {
        styles["normal"] = styledDoc.addStyle("normal", null).apply {
            StyleConstants.setFontFamily(this, "Monospaced")
            StyleConstants.setFontSize(this, 13)
            StyleConstants.setForeground(this, UIUtil.getLabelForeground())
        }
        styles["user"] = styledDoc.addStyle("user", styles["normal"]).apply {
            StyleConstants.setBold(this, true)
            StyleConstants.setForeground(this, JBColor(Color(33, 150, 243), Color(100, 181, 246)))
        }
        styles["assistant"] = styledDoc.addStyle("assistant", styles["normal"]).apply {
            StyleConstants.setForeground(this, UIUtil.getLabelForeground())
        }
        styles["error"] = styledDoc.addStyle("error", styles["normal"]).apply {
            StyleConstants.setForeground(this, JBColor(Color(244, 67, 54), Color(229, 115, 115)))
        }
        styles["info"] = styledDoc.addStyle("info", styles["normal"]).apply {
            StyleConstants.setForeground(this, JBColor(Color(96, 125, 139), Color(144, 164, 174)))
        }
    }
    
    private fun setupUI() {
        val scrollPane = JBScrollPane(outputPane).apply {
            border = JBUI.Borders.empty()
        }
        add(scrollPane, BorderLayout.CENTER)
        
        val inputPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
        }
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        buttonPanel.add(clearButton)
        buttonPanel.add(sendButton)
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(buttonPanel, BorderLayout.EAST)
        add(inputPanel, BorderLayout.SOUTH)
    }
    
    private fun showWelcomeMessage() {
        appendText("=".repeat(40) + "\n", "info")
        appendText("  Jimi AI Assistant\n", "info")
        appendText("=".repeat(40) + "\n\n", "info")
        appendText("Ready. 输入问题或 /help 查看命令\n\n", "info")
    }
    
    private fun clearOutput() {
        try {
            styledDoc.remove(0, styledDoc.length)
            showWelcomeMessage()
        } catch (_: BadLocationException) {}
    }
    
    private fun executeInput() {
        val input = inputField.text.trim()
        if (input.isEmpty()) return
        
        inputField.text = ""
        sendButton.isEnabled = false
        
        // 显示用户输入
        appendText("👤 ", "user")
        appendText("$input\n\n", "user")
        
        // 显示加载
        appendText("🤖 Thinking...\n", "info")
        val loadingPos = styledDoc.length
        
        val service = JimiPluginService.getInstance(project)
        var firstChunk = true
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val error = service.executeTask(input) { chunk ->
                    SwingUtilities.invokeLater {
                        if (firstChunk) {
                            // 移除 "Thinking..."
                            try {
                                val thinkingLen = "🤖 Thinking...\n".length
                                styledDoc.remove(loadingPos - thinkingLen, thinkingLen)
                            } catch (_: Exception) {}
                            appendText("🤖 ", "info")
                            firstChunk = false
                        }
                        appendText(chunk, "assistant")
                    }
                }
                
                SwingUtilities.invokeLater {
                    if (firstChunk) {
                        try {
                            val thinkingLen = "🤖 Thinking...\n".length
                            styledDoc.remove(loadingPos - thinkingLen, thinkingLen)
                        } catch (_: Exception) {}
                    }
                    if (error != null) {
                        appendText("\nError: $error", "error")
                    }
                    appendText("\n\n", "normal")
                    sendButton.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    appendText("Error: ${e.message}\n\n", "error")
                    sendButton.isEnabled = true
                }
            }
        }
    }
    
    private fun appendText(text: String, styleName: String) {
        try {
            val style = styles[styleName] ?: styles["normal"]!!
            styledDoc.insertString(styledDoc.length, text, style)
            outputPane.caretPosition = styledDoc.length
        } catch (_: BadLocationException) {}
    }
}
