package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.message.ContentPart;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * 内容部分消息
 * 用于流式输出文本或其他内容
 */
@Getter
public class ContentPartMessage extends WireMessage {
    
    /**
     * 内容部分
     */
    private final ContentPart contentPart;
    
    /**
     * 内容类型
     */
    private final ContentType contentType;
    
    /**
     * 内容类型枚举
     */
    public enum ContentType {
        /**
         * 普通文本
         */
        TEXT,
        
        /**
         * 推理/思考内容
         */
        REASONING,
        
        /**
         * 图片
         */
        IMAGE
    }
    
    public ContentPartMessage(ContentPart contentPart) {
        this(contentPart, ContentType.TEXT);
    }
    
    public ContentPartMessage(ContentPart contentPart, ContentType contentType) {
        super();
        this.contentPart = contentPart;
        this.contentType = contentType;
    }
}
