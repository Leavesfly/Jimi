package io.leavesfly.jimi.adk.core.sandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱配置
 * 
 * 控制文件系统、Shell 命令和网络访问的安全策略
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxConfig {
    
    /**
     * 是否启用沙箱 (默认 true)
     */
    @Builder.Default
    private boolean enabled = true;
    
    /**
     * 文件系统安全配置
     */
    @Builder.Default
    private FilesystemConfig filesystem = new FilesystemConfig();
    
    /**
     * Shell 命令安全配置
     */
    @Builder.Default
    private ShellConfig shell = new ShellConfig();
    
    /**
     * 网络访问安全配置
     */
    @Builder.Default
    private NetworkConfig network = new NetworkConfig();
    
    /**
     * 文件系统配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilesystemConfig {
        
        /**
         * 拒绝访问的路径模式列表
         */
        @Builder.Default
        private List<String> deniedPaths = new ArrayList<>(List.of(
                "/etc/shadow", "/etc/passwd", "~/.ssh/id_rsa",
                "**/.env", "**/.credentials"
        ));
        
        /**
         * 允许写入的工作区外路径
         */
        @Builder.Default
        private List<String> allowedWritePaths = new ArrayList<>();
        
        /**
         * 最大文件大小 (字节, 默认 10MB)
         */
        @Builder.Default
        private long maxFileSize = 10 * 1024 * 1024;
        
        /**
         * 是否允许工作区外写入
         */
        @Builder.Default
        private boolean allowWriteOutsideWorkspace = false;
    }
    
    /**
     * Shell 命令配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShellConfig {
        
        /**
         * 危险命令模式列表
         */
        @Builder.Default
        private List<String> dangerousPatterns = new ArrayList<>(List.of(
                "rm -rf /", "rm -rf ~", "mkfs", "dd if=",
                ":(){:|:&};:", "fork bomb", "chmod -R 777 /"
        ));
        
        /**
         * 命令白名单 (为空表示不启用白名单)
         */
        @Builder.Default
        private List<String> allowedCommands = new ArrayList<>();
        
        /**
         * 是否允许危险命令 (默认 false)
         */
        @Builder.Default
        private boolean allowDangerousCommands = false;
    }
    
    /**
     * 网络配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetworkConfig {
        
        /**
         * 是否允许外部网络访问
         */
        @Builder.Default
        private boolean allowExternalAccess = true;
        
        /**
         * 拒绝访问的域名列表
         */
        @Builder.Default
        private List<String> deniedDomains = new ArrayList<>();
    }
    
    /**
     * 创建默认禁用的沙箱配置
     */
    public static SandboxConfig disabled() {
        return SandboxConfig.builder().enabled(false).build();
    }
    
    /**
     * 创建默认启用的沙箱配置
     */
    public static SandboxConfig defaultConfig() {
        return SandboxConfig.builder().build();
    }
}
