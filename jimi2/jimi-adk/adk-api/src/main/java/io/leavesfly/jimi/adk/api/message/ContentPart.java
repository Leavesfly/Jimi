package io.leavesfly.jimi.adk.api.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base class for message content parts.
 * Supports polymorphic serialization for different content types.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextPart.class, name = "text"),
    @JsonSubTypes.Type(value = ImagePart.class, name = "image")
})
public abstract class ContentPart {
    
    /**
     * Get the content type
     */
    public abstract String getType();
}
