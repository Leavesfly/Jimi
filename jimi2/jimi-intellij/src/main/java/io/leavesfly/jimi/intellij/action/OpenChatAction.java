package io.leavesfly.jimi.intellij.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * 打开聊天窗口 Action
 *
 * @author Jimi2 Team
 */
public class OpenChatAction extends AnAction {
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        
        // 打开 Jimi 工具窗口
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Jimi Assistant");
        
        if (toolWindow != null) {
            toolWindow.show();
        }
    }
}
