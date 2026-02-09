package io.leavesfly.jimi.adk.core.sandbox;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.regex.Pattern;

/**
 * 沙箱验证器
 * 
 * 职责：
 * - 验证文件路径访问权限
 * - 验证 Shell 命令安全性
 * - 验证网络访问权限
 * - 根据沙箱配置返回验证结果
 */
@Slf4j
public class SandboxValidator {
    
    private final SandboxConfig config;
    private final Path workspaceRoot;
    private final FileSystem fileSystem;
    
    public SandboxValidator(SandboxConfig config, Path workspaceRoot) {
        this.config = config != null ? config : SandboxConfig.disabled();
        this.workspaceRoot = workspaceRoot != null ? workspaceRoot.toAbsolutePath().normalize() : null;
        this.fileSystem = FileSystems.getDefault();
    }
    
    /**
     * 验证文件路径是否允许操作
     */
    public ValidationResult validateFilePath(Path targetPath, FileOperation operation) {
        if (!config.isEnabled()) {
            return ValidationResult.allowed();
        }
        
        try {
            Path normalizedPath = targetPath.toAbsolutePath().normalize();
            
            // 1. 检查是否在拒绝列表中
            for (String deniedPattern : config.getFilesystem().getDeniedPaths()) {
                if (matchesPattern(normalizedPath, deniedPattern)) {
                    log.warn("Sandbox: Path {} matches denied pattern: {}", normalizedPath, deniedPattern);
                    return ValidationResult.denied(
                        "Path is in denied list: " + deniedPattern,
                        SandboxViolationType.DENIED_PATH
                    );
                }
            }
            
            // 2. 只对写操作检查工作区限制
            if (operation == FileOperation.WRITE || operation == FileOperation.DELETE) {
                if (workspaceRoot != null && !isWithinWorkspace(normalizedPath)) {
                    if (!config.getFilesystem().isAllowWriteOutsideWorkspace()) {
                        boolean inAllowedPath = false;
                        for (String allowedPattern : config.getFilesystem().getAllowedWritePaths()) {
                            if (matchesPattern(normalizedPath, allowedPattern)) {
                                inAllowedPath = true;
                                break;
                            }
                        }
                        
                        if (!inAllowedPath) {
                            log.warn("Sandbox: Write outside workspace detected: {}", normalizedPath);
                            return ValidationResult.requiresApproval(
                                "Write outside workspace: " + normalizedPath,
                                SandboxViolationType.OUTSIDE_WORKSPACE
                            );
                        }
                    }
                }
            }
            
            return ValidationResult.allowed();
            
        } catch (Exception e) {
            log.error("Sandbox: Error validating file path: {}", targetPath, e);
            return ValidationResult.denied(
                "Invalid path: " + e.getMessage(),
                SandboxViolationType.INVALID_PATH
            );
        }
    }
    
    /**
     * 验证 Shell 命令是否安全
     */
    public ValidationResult validateShellCommand(String command) {
        if (!config.isEnabled()) {
            return ValidationResult.allowed();
        }
        
        String trimmedCommand = command.trim();
        
        // 1. 检查危险模式
        if (!config.getShell().isAllowDangerousCommands()) {
            for (String pattern : config.getShell().getDangerousPatterns()) {
                if (containsPattern(trimmedCommand, pattern)) {
                    log.warn("Sandbox: Dangerous command pattern detected: {}", pattern);
                    return ValidationResult.denied(
                        "Dangerous command pattern detected: " + pattern,
                        SandboxViolationType.DANGEROUS_COMMAND
                    );
                }
            }
        }
        
        // 2. 白名单模式检查
        if (!config.getShell().getAllowedCommands().isEmpty()) {
            String commandName = extractCommandName(trimmedCommand);
            if (!config.getShell().getAllowedCommands().contains(commandName)) {
                log.warn("Sandbox: Command not in whitelist: {}", commandName);
                return ValidationResult.requiresApproval(
                    "Command not in whitelist: " + commandName,
                    SandboxViolationType.NOT_IN_WHITELIST
                );
            }
        }
        
        // 3. 检查危险的重定向
        if (Pattern.matches(".*>\\s*/dev/.*", trimmedCommand) ||
            Pattern.matches(".*>\\s*/etc/.*", trimmedCommand) ||
            Pattern.matches(".*>\\s*/usr/.*", trimmedCommand) ||
            Pattern.matches(".*>\\s*/System/.*", trimmedCommand)) {
            log.warn("Sandbox: Dangerous redirect detected in command");
            return ValidationResult.denied(
                "Redirect to system directory not allowed",
                SandboxViolationType.DANGEROUS_REDIRECT
            );
        }
        
        return ValidationResult.allowed();
    }
    
    /**
     * 验证网络访问
     */
    public ValidationResult validateNetworkAccess(String url) {
        if (!config.isEnabled()) {
            return ValidationResult.allowed();
        }
        
        if (!config.getNetwork().isAllowExternalAccess()) {
            log.warn("Sandbox: External network access not allowed");
            return ValidationResult.requiresApproval(
                "External network access: " + url,
                SandboxViolationType.NETWORK_ACCESS
            );
        }
        
        for (String deniedDomain : config.getNetwork().getDeniedDomains()) {
            if (url.contains(deniedDomain)) {
                log.warn("Sandbox: Access to denied domain: {}", deniedDomain);
                return ValidationResult.denied(
                    "Access to denied domain: " + deniedDomain,
                    SandboxViolationType.DENIED_DOMAIN
                );
            }
        }
        
        return ValidationResult.allowed();
    }
    
    /**
     * 验证文件大小
     */
    public ValidationResult validateFileSize(long contentSize) {
        if (!config.isEnabled()) {
            return ValidationResult.allowed();
        }
        
        long maxSize = config.getFilesystem().getMaxFileSize();
        if (contentSize > maxSize) {
            log.warn("Sandbox: File size {} exceeds limit {}", contentSize, maxSize);
            return ValidationResult.denied(
                String.format("File size (%d bytes) exceeds limit (%d bytes)", contentSize, maxSize),
                SandboxViolationType.FILE_SIZE_EXCEEDED
            );
        }
        
        return ValidationResult.allowed();
    }
    
    // ==================== 辅助方法 ====================
    
    private boolean isWithinWorkspace(Path path) {
        if (workspaceRoot == null) {
            return true;
        }
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return normalized.startsWith(workspaceRoot);
        } catch (Exception e) {
            log.error("Error checking workspace containment", e);
            return false;
        }
    }
    
    private boolean matchesPattern(Path path, String pattern) {
        String pathStr = path.toString();
        if (pathStr.contains(pattern)) {
            return true;
        }
        try {
            PathMatcher matcher = fileSystem.getPathMatcher("glob:" + pattern);
            return matcher.matches(path);
        } catch (Exception e) {
            log.debug("Invalid glob pattern: {}", pattern);
            return false;
        }
    }
    
    private boolean containsPattern(String command, String pattern) {
        if (command.contains(pattern)) {
            return true;
        }
        try {
            if (pattern.contains(".*") || pattern.contains("\\")) {
                return Pattern.compile(pattern).matcher(command).find();
            }
        } catch (Exception e) {
            log.debug("Invalid regex pattern: {}", pattern);
        }
        return false;
    }
    
    private String extractCommandName(String command) {
        String trimmed = command.trim();
        if (trimmed.contains("=")) {
            int equalIndex = trimmed.indexOf('=');
            int spaceAfterEqual = trimmed.indexOf(' ', equalIndex);
            if (spaceAfterEqual > 0) {
                trimmed = trimmed.substring(spaceAfterEqual + 1).trim();
            }
        }
        int spaceIndex = trimmed.indexOf(' ');
        if (spaceIndex > 0) {
            return trimmed.substring(0, spaceIndex);
        }
        return trimmed;
    }
    
    // ==================== 结果类 ====================
    
    @Data
    @Builder
    public static class ValidationResult {
        private final boolean allowed;
        private final boolean requiresApproval;
        private final String reason;
        private final SandboxViolationType violationType;
        
        public static ValidationResult allowed() {
            return ValidationResult.builder()
                    .allowed(true)
                    .requiresApproval(false)
                    .build();
        }
        
        public static ValidationResult denied(String reason, SandboxViolationType type) {
            return ValidationResult.builder()
                    .allowed(false)
                    .requiresApproval(false)
                    .reason(reason)
                    .violationType(type)
                    .build();
        }
        
        public static ValidationResult requiresApproval(String reason, SandboxViolationType type) {
            return ValidationResult.builder()
                    .allowed(false)
                    .requiresApproval(true)
                    .reason(reason)
                    .violationType(type)
                    .build();
        }
    }
    
    public enum SandboxViolationType {
        DENIED_PATH,
        OUTSIDE_WORKSPACE,
        DANGEROUS_COMMAND,
        NOT_IN_WHITELIST,
        DANGEROUS_REDIRECT,
        NETWORK_ACCESS,
        DENIED_DOMAIN,
        FILE_SIZE_EXCEEDED,
        INVALID_PATH
    }
    
    public enum FileOperation {
        READ,
        WRITE,
        DELETE
    }
}
