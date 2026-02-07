package io.leavesfly.jimi.intellij.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.leavesfly.jimi.intellij.service.JimiProjectService;
import org.jetbrains.annotations.NotNull;

/**
 * 询问选中代码 Action
 *
 * @author Jimi2 Team
 */
public class AskAboutSelectionAction extends AnAction {
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        if (project == null || editor == null) {
            return;
        }
        
        // 获取选中的文本
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            Messages.showInfoMessage(project, "请先选中要询问的代码", "Jimi");
            return;
        }
        
        // 弹出询问对话框
        String question = Messages.showInputDialog(
                project,
                "请输入你的问题：",
                "询问 Jimi",
                Messages.getQuestionIcon()
        );
        
        if (question == null || question.trim().isEmpty()) {
            return;
        }
        
        // 构建带上下文的消息
        String message = question.trim();
        String context = "```\n" + selectedText + "\n```";
        
        // 发送消息
        JimiProjectService service = JimiProjectService.getInstance(project);
        service.sendMessage(message, context).subscribe(
                result -> {
                    // 结果会通过 Wire 显示在工具窗口
                },
                error -> {
                    Messages.showErrorDialog(project, "执行失败: " + error.getMessage(), "Jimi 错误");
                }
        );
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean hasSelection = editor != null && editor.getSelectionModel().hasSelection();
        e.getPresentation().setEnabled(hasSelection);
    }
}
