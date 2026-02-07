package io.leavesfly.jimi.adk.api.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Image content part.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ImagePart extends ContentPart {
    
    /**
     * Image URL or base64 data
     */
    private String url;
    
    /**
     * Image media type (e.g., "image/png", "image/jpeg")
     */
    private String mediaType;
    
    /**
     * Base64-encoded image data (alternative to URL)
     */
    private String base64Data;
    
    @Override
    public String getType() {
        return "image";
    }
    
    /**
     * Create an image part from URL
     */
    public static ImagePart fromUrl(String url) {
        return ImagePart.builder().url(url).build();
    }
    
    /**
     * Create an image part from base64 data
     */
    public static ImagePart fromBase64(String base64Data, String mediaType) {
        return ImagePart.builder()
                .base64Data(base64Data)
                .mediaType(mediaType)
                .build();
    }
}
