package io.leavesfly.jimi.intellij.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Jimi 工具窗口工厂
 * <p>
 * 创建 IDE 右侧的 Jimi 助手面板
 * </p>
 *
 * @author Jimi2 Team
 */
public class JimiToolWindowFactory implements ToolWindowFactory {
    
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 创建聊天面板
        JimiChatPanel chatPanel = new JimiChatPanel(project);
        
        // 添加到工具窗口
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(chatPanel.getMainPanel(), "对话", false);
        toolWindow.getContentManager().addContent(content);
    }
}
