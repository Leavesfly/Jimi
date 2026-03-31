package io.leavesfly.jimi.tool.core.ask;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人工交互响应
 * 封装用户对人工交互请求的响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HumanInputResponse {
    
    /**
     * 响应状态
     */
    private Status status;
    
    /**
     * 用户输入内容
     */
    private String content;
    
    /**
     * 响应状态枚举
     */
    public enum Status {
        /**
         * 确认通过/满意
         */
        APPROVED,
        
        /**
         * 拒绝
         */
        REJECTED,
        
        /**
         * 需要修改
         */
        NEEDS_MODIFICATION,
        
        /**
         * 已提供输入
         */
        INPUT_PROVIDED
    }
    
    /**
     * 是否已批准
     */
    public boolean isApproved() {
        return status == Status.APPROVED;
    }
    
    /**
     * 是否需要修改
     */
    public boolean needsModification() {
        return status == Status.NEEDS_MODIFICATION;
    }
    
    /**
     * 是否被拒绝
     */
    public boolean isRejected() {
        return status == Status.REJECTED;
    }
    
    /**
     * 创建批准响应
     */
    public static HumanInputResponse approved() {
        return HumanInputResponse.builder()
                .status(Status.APPROVED)
                .build();
    }
    
    /**
     * 创建拒绝响应
     */
    public static HumanInputResponse rejected() {
        return HumanInputResponse.builder()
                .status(Status.REJECTED)
                .build();
    }
    
    /**
     * 创建需要修改响应
     */
    public static HumanInputResponse needsModification(String feedback) {
        return HumanInputResponse.builder()
                .status(Status.NEEDS_MODIFICATION)
                .content(feedback)
                .build();
    }
    
    /**
     * 创建已提供输入响应
     */
    public static HumanInputResponse inputProvided(String input) {
        return HumanInputResponse.builder()
                .status(Status.INPUT_PROVIDED)
                .content(input)
                .build();
    }
}
