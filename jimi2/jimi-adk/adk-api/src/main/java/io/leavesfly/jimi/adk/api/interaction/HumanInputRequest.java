package io.leavesfly.jimi.adk.api.interaction;

/**
 * 人机交互请求
 */
public class HumanInputRequest {
    
    private final String requestId;
    private final String question;
    private final InputType inputType;
    private final String[] choices;
    private final String defaultValue;
    
    public HumanInputRequest(String requestId, String question, InputType inputType, 
                           String[] choices, String defaultValue) {
        this.requestId = requestId;
        this.question = question;
        this.inputType = inputType;
        this.choices = choices;
        this.defaultValue = defaultValue;
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    public String getQuestion() {
        return question;
    }
    
    public InputType getInputType() {
        return inputType;
    }
    
    public String[] getChoices() {
        return choices;
    }
    
    public String getDefaultValue() {
        return defaultValue;
    }
    
    /**
     * 输入类型
     */
    public enum InputType {
        /** 确认型：是/否 */
        CONFIRM,
        /** 自由输入 */
        FREE_INPUT,
        /** 多选项选择 */
        CHOICE
    }
    
    public static HumanInputRequest confirm(String requestId, String question) {
        return new HumanInputRequest(requestId, question, InputType.CONFIRM, null, null);
    }
    
    public static HumanInputRequest freeInput(String requestId, String question, String defaultValue) {
        return new HumanInputRequest(requestId, question, InputType.FREE_INPUT, null, defaultValue);
    }
    
    public static HumanInputRequest choice(String requestId, String question, String[] choices) {
        return new HumanInputRequest(requestId, question, InputType.CHOICE, choices, null);
    }
}
