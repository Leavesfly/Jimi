package io.leavesfly.jimi.adk.api.interaction;

/**
 * 人机交互接口
 * 定义 Agent 与用户交互的能力
 */
public interface HumanInteraction {
    
    /**
     * 请求确认
     * 
     * @param question 确认问题
     * @return 用户响应
     */
    HumanInputResponse requestConfirmation(String question);
    
    /**
     * 请求自由输入
     * 
     * @param question 提问内容
     * @param defaultValue 默认值（可选）
     * @return 用户响应
     */
    HumanInputResponse requestInput(String question, String defaultValue);
    
    /**
     * 请求选择
     * 
     * @param question 提问内容
     * @param choices 选项列表
     * @return 用户响应
     */
    HumanInputResponse requestChoice(String question, String[] choices);
}
