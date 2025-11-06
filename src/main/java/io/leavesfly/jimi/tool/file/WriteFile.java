package io.leavesfly.jimi.tool.file;

import io.leavesfly.jimi.soul.approval.Approval;
import io.leavesfly.jimi.soul.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.ToolResultBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * WriteFile 工具 - 写入文件内容
 * 支持覆盖（overwrite）和追加（append）两种模式
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class WriteFile extends AbstractTool<WriteFile.Params> {
    
    private static final String EDIT_ACTION = "EDIT";
    
    private Path workDir;
    private Approval approval;
    
    /**
     * 参数模型
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        /**
         * 文件绝对路径
         */
        private String path;
        
        /**
         * 要写入的内容
         */
        private String content;
        
        /**
         * 写入模式：overwrite（覆盖）或 append（追加）
         */
        @Builder.Default
        private String mode = "overwrite";
    }
    
    /**
     * 默认构造函数（Spring 调用）
     */
    public WriteFile() {
        super(
            "WriteFile",
            "Write content to a file. Supports overwrite and append modes.",
            Params.class
        );
    }
    
    /**
     * 设置运行时参数
     */
    public void setBuiltinArgs(BuiltinSystemPromptArgs builtinArgs) {
        this.workDir = builtinArgs.getJimiWorkDir();
    }
    
    public void setApproval(Approval approval) {
        this.approval = approval;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.defer(() -> {
            try {
                Path targetPath = Path.of(params.path);
                
                // 验证路径
                if (!targetPath.isAbsolute()) {
                    return Mono.just(ToolResult.error(
                        String.format("`%s` is not an absolute path. You must provide an absolute path to write a file.", params.path),
                        "Invalid path"
                    ));
                }
                
                ToolResult pathError = validatePath(targetPath);
                if (pathError != null) {
                    return Mono.just(pathError);
                }
                
                if (!Files.exists(targetPath.getParent())) {
                    return Mono.just(ToolResult.error(
                        String.format("`%s` parent directory does not exist.", params.path),
                        "Parent directory not found"
                    ));
                }
                
                // 验证模式
                if (!"overwrite".equals(params.mode) && !"append".equals(params.mode)) {
                    return Mono.just(ToolResult.error(
                        String.format("Invalid write mode: `%s`. Mode must be either `overwrite` or `append`.", params.mode),
                        "Invalid write mode"
                    ));
                }
                
                // 请求审批
                return approval.requestApproval("write-file", EDIT_ACTION, String.format("Write file `%s`", params.path))
                    .flatMap(response -> {
                        if (response == io.leavesfly.jimi.soul.approval.ApprovalResponse.REJECT) {
                            return Mono.just(ToolResult.rejected());
                        }
                        
                        try {
                            // 写入文件
                            if ("overwrite".equals(params.mode)) {
                                Files.writeString(targetPath, params.content);
                            } else {
                                Files.writeString(targetPath, params.content, 
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            }
                            
                            // 获取文件大小
                            long fileSize = Files.size(targetPath);
                            String action = "overwrite".equals(params.mode) ? "overwritten" : "appended to";
                            
                            return Mono.just(ToolResult.ok(
                                "",
                                String.format("File successfully %s. Current size: %d bytes.", action, fileSize)
                            ));
                            
                        } catch (Exception e) {
                            log.error("Failed to write file: {}", params.path, e);
                            return Mono.just(ToolResult.error(
                                String.format("Failed to write to %s. Error: %s", params.path, e.getMessage()),
                                "Failed to write file"
                            ));
                        }
                    });
                    
            } catch (Exception e) {
                log.error("Error in WriteFile.execute", e);
                return Mono.just(ToolResult.error(
                    String.format("Failed to write file. Error: %s", e.getMessage()),
                    "Failed to write file"
                ));
            }
        });
    }
    
    /**
     * 验证路径安全性
     */
    private ToolResult validatePath(Path targetPath) {
        try {
            Path resolvedPath = targetPath.toRealPath();
            Path resolvedWorkDir = workDir.toRealPath();
            
            if (!resolvedPath.startsWith(resolvedWorkDir)) {
                return ToolResult.error(
                    String.format("`%s` is outside the working directory. You can only write files within the working directory.", targetPath),
                    "Path outside working directory"
                );
            }
        } catch (Exception e) {
            // 文件不存在时，检查父目录
            try {
                Path parentPath = targetPath.getParent();
                if (parentPath != null && Files.exists(parentPath)) {
                    Path resolvedParent = parentPath.toRealPath();
                    Path resolvedWorkDir = workDir.toRealPath();
                    
                    if (!resolvedParent.startsWith(resolvedWorkDir)) {
                        return ToolResult.error(
                            String.format("`%s` is outside the working directory. You can only write files within the working directory.", targetPath),
                            "Path outside working directory"
                        );
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to validate path: {}", targetPath, ex);
            }
        }
        
        return null;
    }
}
