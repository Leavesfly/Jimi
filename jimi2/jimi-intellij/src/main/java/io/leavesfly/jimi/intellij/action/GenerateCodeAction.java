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
 * 生成代码 Action
 * <p>
 * 将选中的代码发送给 Jimi，请求生成或补全代码。
 * </p>
 *
 * @author Jimi2 Team
 */
public class GenerateCodeAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);

        if (project == null || editor == null) {
            return;
        }

        String selectedText = editor.getSelectionModel().getSelectedText();

        // 弹出输入对话框
        String instruction = Messages.showInputDialog(
                project,
                "请描述你想要生成的代码：",
                "Jimi - 生成代码",
                Messages.getQuestionIcon()
        );

        if (instruction == null || instruction.trim().isEmpty()) {
            return;
        }

        String message = "请根据以下指令生成代码：" + instruction.trim();
        String context = (selectedText != null && !selectedText.isEmpty())
                ? "当前选中的代码上下文：\n```\n" + selectedText + "\n```"
                : null;

        JimiProjectService service = JimiProjectService.getInstance(project);
        service.sendMessage(message, context).subscribe(
                result -> { /* 结果通过 Wire 显示 */ },
                error -> Messages.showErrorDialog(project,
                        "代码生成失败: " + error.getMessage(), "Jimi 错误")
        );
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
