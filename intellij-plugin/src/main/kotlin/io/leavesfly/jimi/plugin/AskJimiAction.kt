package io.leavesfly.jimi.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.runBlocking

/**
 * Ask Jimi Action - 右键菜单动作
 */
class AskJimiAction : AnAction() {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // 获取选中的代码
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: ""
        
        // 弹出输入框
        val input = Messages.showInputDialog(
            project,
            "What would you like Jimi to do?\n\nSelected code:\n${selectedText.take(100)}...",
            "Ask Jimi",
            null
        ) ?: return
        
        // 异步执行任务
        runBlocking {
            try {
                val service = JimiPluginService.getInstance(project)
                val result = service.executeTask(input)
                
                Messages.showMessageDialog(
                    project,
                    result,
                    "Jimi Response",
                    Messages.getInformationIcon()
                )
            } catch (ex: Exception) {
                Messages.showErrorDialog(
                    project,
                    "Error: ${ex.message}",
                    "Jimi Error"
                )
            }
        }
    }
}
