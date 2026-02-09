package io.leavesfly.jimi.adk.knowledge.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 用户偏好
 * 存储用户的个性化配置和习惯
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferences {
    
    @JsonProperty("version")
    private String version = "1.0";
    
    @JsonProperty("lastUpdated")
    private Instant lastUpdated;
    
    @JsonProperty("communication")
    @Builder.Default
    private CommunicationPrefs communication = new CommunicationPrefs();
    
    @JsonProperty("coding")
    @Builder.Default
    private CodingPrefs coding = new CodingPrefs();
    
    @JsonProperty("workflow")
    @Builder.Default
    private WorkflowPrefs workflow = new WorkflowPrefs();
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommunicationPrefs {
        @JsonProperty("language")
        @Builder.Default
        private String language = "中文";
        
        @JsonProperty("verbosity")
        @Builder.Default
        private String verbosity = "concise";
        
        @JsonProperty("needsConfirmation")
        @Builder.Default
        private List<String> needsConfirmation = List.of("delete", "modify_critical");
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodingPrefs {
        @JsonProperty("style")
        @Builder.Default
        private String style = "Google Java Style";
        
        @JsonProperty("testFramework")
        @Builder.Default
        private String testFramework = "JUnit 5";
        
        @JsonProperty("preferredPatterns")
        @Builder.Default
        private List<String> preferredPatterns = List.of("Builder", "Factory");
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowPrefs {
        @JsonProperty("autoFormat")
        @Builder.Default
        private boolean autoFormat = true;
        
        @JsonProperty("autoTest")
        @Builder.Default
        private boolean autoTest = false;
    }
    
    public static UserPreferences getDefault() {
        return UserPreferences.builder()
                .version("1.0")
                .lastUpdated(Instant.now())
                .communication(new CommunicationPrefs())
                .coding(new CodingPrefs())
                .workflow(new WorkflowPrefs())
                .build();
    }
}
