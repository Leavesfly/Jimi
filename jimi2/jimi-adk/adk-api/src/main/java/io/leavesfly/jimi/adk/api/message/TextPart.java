package io.leavesfly.jimi.adk.api.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Text content part.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class TextPart extends ContentPart {
    
    private String text;
    
    @Override
    public String getType() {
        return "text";
    }
    
    /**
     * Create a text part
     */
    public static TextPart of(String text) {
        return new TextPart(text);
    }
}
