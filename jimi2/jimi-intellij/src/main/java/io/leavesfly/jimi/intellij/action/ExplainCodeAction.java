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
 * 解释代码 Action
 * <p>
 * 将选中的代码发送给 Jimi，请求解释代码逻辑。
 * </p>
 *
 * @author Jimi2 Team
 */
public class ExplainCodeAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);

        if (project == null || editor == null) {
            return;
        }

        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            Messages.showInfoMessage(project, "请先选中要解释的代码", "Jimi");
            return;
        }

        String message = "请详细解释以下代码的逻辑和作用：";
        String context = "```\n" + selectedText + "\n```";

        JimiProjectService service = JimiProjectService.getInstance(project);
        service.sendMessage(message, context).subscribe(
                result -> { /* 结果通过 Wire 显示 */ },
                error -> Messages.showErrorDialog(project,
                        "代码解释失败: " + error.getMessage(), "Jimi 错误")
        );
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean hasSelection = editor != null && editor.getSelectionModel().hasSelection();
        e.getPresentation().setEnabled(hasSelection);
    }
}
