package io.leavesfly.jimi.tool.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP 配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPConfig {
    @JsonProperty("mcpServers")
    private Map<String, ServerConfig> mcpServers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerConfig {
        @JsonProperty("command")
        private String command;
        @JsonProperty("args")
        private List<String> args;
        @JsonProperty("env")
        private Map<String, String> env;
        @JsonProperty("url")
        private String url;
        @JsonProperty("headers")
        private Map<String, String> headers;

        public boolean isStdio() { return command != null && !command.isEmpty(); }
        public boolean isHttp() { return url != null && !url.isEmpty(); }
    }
}
