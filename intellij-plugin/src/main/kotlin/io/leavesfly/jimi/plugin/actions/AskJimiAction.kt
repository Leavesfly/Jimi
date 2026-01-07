package io.leavesfly.jimi.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class AskJimiAction : AnAction() {
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // 获取选中的文本
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrBlank()) {
            Messages.showWarningDialog(project, "请先选择代码", "Jimi")
            return
        }
        
        // 弹出输入框询问用户问题
        val question = Messages.showInputDialog(
            project,
            "请输入您关于选中代码的问题：",
            "Ask Jimi",
            Messages.getQuestionIcon(),
            "请解释这段代码",
            null
        ) ?: return
        
        // 构建完整的提问内容
        val fullInput = """
            |关于以下代码：
            |```
            |$selectedText
            |```
            |
            |问题：$question
        """.trimMargin()
        
        // 复制到剪贴板
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(fullInput), null)
        
        // 打开 Jimi ToolWindow
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Jimi")
        toolWindow?.show {
            Messages.showInfoMessage(project, "内容已复制到剪贴板，请在 Jimi 窗口中粘贴发送", "Ask Jimi")
        }
    }
    
    override fun update(e: AnActionEvent) {
        // 只有在编辑器中有选中文本时才启用
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        e.presentation.isEnabled = e.project != null && hasSelection
        // 始终显示菜单项，但没选中时置灰
        e.presentation.isVisible = e.project != null
    }
}
