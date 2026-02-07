package io.leavesfly.jimi.intellij.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.message.TextPart;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import io.leavesfly.jimi.adk.core.wire.messages.*;
import io.leavesfly.jimi.intellij.service.JimiProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * Jimi 聊天面板
 * <p>
 * 提供用户与 Jimi 交互的 UI 界面
 * </p>
 *
 * @author Jimi2 Team
 */
public class JimiChatPanel {
    
    private static final Logger log = LoggerFactory.getLogger(JimiChatPanel.class);
    
    /** 关联的项目 */
    private final Project project;
    
    /** 项目服务 */
    private final JimiProjectService service;
    
    /** 主面板 */
    private final JPanel mainPanel;
    
    /** 聊天显示区域 */
    private final JTextArea chatArea;
    
    /** 输入区域 */
    private final JBTextArea inputArea;
    
    /** 发送按钮 */
    private final JButton sendButton;
    
    /** 清空按钮 */
    private final JButton clearButton;
    
    /**
     * 构造函数
     *
     * @param project 项目实例
     */
    public JimiChatPanel(Project project) {
        this.project = project;
        this.service = JimiProjectService.getInstance(project);
        
        // 初始化主面板
        mainPanel = new JPanel(new BorderLayout());
        
        // 聊天显示区域
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        
        JBScrollPane chatScroll = new JBScrollPane(chatArea);
        mainPanel.add(chatScroll, BorderLayout.CENTER);
        
        // 输入区域
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        
        inputArea = new JBTextArea(3, 0);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.getEmptyText().setText("输入消息... (Ctrl+Enter 发送)");
        
        // 快捷键发送
        inputArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "send");
        inputArea.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });
        
        JBScrollPane inputScroll = new JBScrollPane(inputArea);
        inputPanel.add(inputScroll, BorderLayout.CENTER);
        
        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        clearButton = new JButton("清空");
        clearButton.addActionListener(e -> clearChat());
        buttonPanel.add(clearButton);
        
        sendButton = new JButton("发送");
        sendButton.addActionListener(e -> sendMessage());
        buttonPanel.add(sendButton);
        
        inputPanel.add(buttonPanel, BorderLayout.SOUTH);
        mainPanel.add(inputPanel, BorderLayout.SOUTH);
        
        // 订阅消息
        subscribeWire();
        
        // 显示欢迎消息
        appendToChat("Jimi", "你好！我是 Jimi AI 助手，有什么可以帮助你的？\n\n");
    }
    
    /**
     * 订阅消息总线
     */
    private void subscribeWire() {
        service.getWire().asFlux().subscribe(this::handleWireMessage);
    }
    
    /**
     * 处理消息
     *
     * @param message 消息
     */
    private void handleWireMessage(WireMessage message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (message instanceof ContentPartMessage) {
                ContentPartMessage cpm = (ContentPartMessage) message;
                var part = cpm.getContentPart();
                if (part instanceof TextPart) {
                    String text = ((TextPart) part).getText();
                    chatArea.append(text);
                    scrollToBottom();
                }
            } else if (message instanceof ToolCallMessage) {
                ToolCallMessage tcm = (ToolCallMessage) message;
                String toolName = tcm.getToolCall().getFunction().getName();
                chatArea.append("\n[调用工具: " + toolName + "]\n");
                scrollToBottom();
            }
        });
    }
    
    /**
     * 发送消息
     */
    private void sendMessage() {
        String input = inputArea.getText().trim();
        if (input.isEmpty()) {
            return;
        }
        
        // 清空输入
        inputArea.setText("");
        
        // 显示用户消息
        appendToChat("用户", input + "\n\n");
        
        // 准备助手回复
        chatArea.append("Jimi: ");
        
        // 禁用发送按钮
        sendButton.setEnabled(false);
        
        // 异步执行
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ExecutionResult result = service.sendMessage(input).block();
                
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (result != null && !result.isSuccess()) {
                        chatArea.append("\n[错误: " + result.getError() + "]\n");
                    }
                    chatArea.append("\n\n");
                    scrollToBottom();
                    sendButton.setEnabled(true);
                });
                
            } catch (Exception e) {
                log.error("执行异常", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    chatArea.append("\n[错误: " + e.getMessage() + "]\n\n");
                    scrollToBottom();
                    sendButton.setEnabled(true);
                });
            }
        });
    }
    
    /**
     * 追加消息到聊天区域
     *
     * @param role    角色
     * @param message 消息
     */
    private void appendToChat(String role, String message) {
        chatArea.append(role + ": " + message);
        scrollToBottom();
    }
    
    /**
     * 滚动到底部
     */
    private void scrollToBottom() {
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }
    
    /**
     * 清空聊天
     */
    private void clearChat() {
        chatArea.setText("");
        service.resetConversation();
        appendToChat("Jimi", "对话已重置，有什么可以帮助你的？\n\n");
    }
    
    /**
     * 获取主面板
     *
     * @return 主面板
     */
    public JPanel getMainPanel() {
        return mainPanel;
    }
}
