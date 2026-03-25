package io.leavesfly.jimi.tool.core.file;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.core.interaction.approval.ApprovalResponse;
import io.leavesfly.jimi.core.interaction.approval.Approval;
import io.leavesfly.jimi.core.engine.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.core.sandbox.SandboxValidator;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;

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

/**
 * StrReplaceFile 工具 - 字符串替换文件内容
 * <p>
 * 参数设计参考 Claude Code 的 str_replace_editor，采用扁平化结构：
 * - path: 文件路径
 * - old_str: 要替换的旧字符串
 * - new_str: 替换后的新字符串
 * <p>
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class StrReplaceFile extends AbstractTool<StrReplaceFile.Params> {
    
    private static final String EDIT_ACTION = "EDIT";
    
    private Path workDir;
    private Approval approval;
    private SandboxValidator sandboxValidator;
    
    /**
     * 参数模型 - 扁平化设计，便于 LLM 生成正确的 JSON
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        /**
         * 文件绝对路径
         */
        @JsonPropertyDescription("要编辑的文件绝对路径（例如：/home/user/file.txt）")
        private String path;
        
        /**
         * 要替换的旧字符串（精确匹配）
         */
        @JsonPropertyDescription("要替换的原始字符串，必须与文件内容完全匹配（包括空格和换行符）")
        private String old_str;
        
        /**
         * 替换后的新字符串
         */
        @JsonPropertyDescription("替换后的新字符串。使用空字符串可删除 old_str")
        private String new_str;
    }
    
    public StrReplaceFile() {
        super(
            "StrReplaceFile",
            "替换文件中的字符串。old_str 必须精确匹配文件内容。",
            Params.class
        );
    }

    @Override
    public boolean isConcurrentSafe() {
        return false;
    }
    
    public void setBuiltinArgs(BuiltinSystemPromptArgs builtinArgs) {
        this.workDir = builtinArgs.getJimiWorkDir();
    }
    
    public void setApproval(Approval approval) {
        this.approval = approval;
    }
    
    public void setSandboxValidator(SandboxValidator sandboxValidator) {
        this.sandboxValidator = sandboxValidator;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.defer(() -> {
            try {
                // 验证参数
                if (params.path == null || params.path.trim().isEmpty()) {
                    return Mono.just(ToolResult.error(
                        "File path is required. Please provide a valid file path.",
                        "Missing path"
                    ));
                }
                
                if (params.old_str == null || params.old_str.isEmpty()) {
                    return Mono.just(ToolResult.error(
                        "old_str is required and cannot be empty.",
                        "Missing old_str"
                    ));
                }
                
                // new_str 可以为空（表示删除），但不能为 null
                String newStr = params.new_str != null ? params.new_str : "";
                
                // 检查是否为无意义替换
                if (params.old_str.equals(newStr)) {
                    log.warn("old_str and new_str are identical, this is a no-op replacement");
                }
                
                Path targetPath = Path.of(params.path);
                
                // 验证路径
                if (!targetPath.isAbsolute()) {
                    return Mono.just(ToolResult.error(
                        String.format("`%s` is not an absolute path. You must provide an absolute path to edit a file.", params.path),
                        "Invalid path"
                    ));
                }
                
                // 先检查文件是否存在（必须在 validatePath 之前）
                if (!Files.exists(targetPath)) {
                    return Mono.just(ToolResult.error(
                        String.format("`%s` does not exist.", params.path),
                        "File not found"
                    ));
                }
                
                if (!Files.isRegularFile(targetPath)) {
                    return Mono.just(ToolResult.error(
                        String.format("`%s` is not a file.", params.path),
                        "Invalid path"
                    ));
                }
                
                // 现在验证路径安全性（文件已存在，toRealPath() 才能成功）
                ToolResult pathError = validatePath(targetPath);
                if (pathError != null) {
                    return Mono.just(pathError);
                }
                
                // 沙箱验证
                if (sandboxValidator != null) {
                    SandboxValidator.ValidationResult sandboxResult = 
                            sandboxValidator.validateFilePath(targetPath, SandboxValidator.FileOperation.WRITE);
                    
                    if (!sandboxResult.isAllowed() && !sandboxResult.isRequiresApproval()) {
                        return Mono.just(ToolResult.error(
                            "SANDBOXING: " + sandboxResult.getReason(),
                            "Sandbox violation"
                        ));
                    }
                    
                    // 沙箱需要审批
                    if (sandboxResult.isRequiresApproval()) {
                        String approvalDesc = String.format(
                            "Edit file `%s` (Sandbox: %s)",
                            params.path,
                            sandboxResult.getReason()
                        );
                        return approval.requestApproval("replace-file", EDIT_ACTION, approvalDesc)
                            .flatMap(response -> {
                                if (response == ApprovalResponse.REJECT) {
                                    return Mono.just(ToolResult.rejected());
                                }
                                return doReplaceString(targetPath, params);
                            });
                    }
                }


                // 正常审批流程
                return approval.requestApproval("replace-file", EDIT_ACTION, String.format("Edit file `%s`", params.path))
                    .flatMap(response -> {
                        if (response == ApprovalResponse.REJECT) {
                            return Mono.just(ToolResult.rejected());
                        }
                        return doReplaceString(targetPath, params);
                    });
                    
            } catch (Exception e) {
                log.error("Error in StrReplaceFile.execute", e);
                return Mono.just(ToolResult.error(
                    String.format("Failed to edit file. Error: %s", e.getMessage()),
                    "Edit failed"
                ));
            }
        });
    }
    


    /**
     * 验证路径安全性
     * 注意：调用此方法前必须确保文件存在，否则 toRealPath() 会失败
     */
    private ToolResult validatePath(Path targetPath) {
        try {
            Path resolvedPath = targetPath.toRealPath();
            Path resolvedWorkDir = workDir.toRealPath();
            
            if (!resolvedPath.startsWith(resolvedWorkDir)) {
                return ToolResult.error(
                    String.format("`%s` is outside the working directory. You can only edit files within the working directory.", targetPath),
                    "Path outside working directory"
                );
            }
        } catch (Exception e) {
            // 路径验证失败应该返回错误，而不是默认通过
            log.error("Path validation failed for: {}", targetPath, e);
            return ToolResult.error(
                String.format("Failed to validate path safety: %s", e.getMessage()),
                "Path validation failed"
            );
        }
        
        return null;
    }
    
    /**
     * 执行字符串替换
     */
    private Mono<ToolResult> doReplaceString(Path targetPath, Params params) {
        try {
            // 读取文件内容
            String content = Files.readString(targetPath);
            String originalContent = content;
            
            // 只替换第一次出现
            int index = content.indexOf(params.old_str);
            if (index != -1) {
                String finalNewStr = params.new_str != null ? params.new_str : "";
                content = content.substring(0, index) + 
                         finalNewStr + 
                         content.substring(index + params.old_str.length());
            }
            
            // 检查是否有变化
            if (content.equals(originalContent)) {
                return Mono.just(ToolResult.error(
                    "No replacements were made. The old_str was not found in the file.",
                    "String not found"
                ));
            }
            
            // 写回文件
            Files.writeString(targetPath, content);
            
            return Mono.just(ToolResult.ok(
                "",
                "File edited successfully."
            ));
            
        } catch (Exception e) {
            log.error("Failed to edit file: {}", params.path, e);
            return Mono.just(ToolResult.error(
                String.format("Failed to edit. Error: %s", e.getMessage()),
                "Edit failed"
            ));
        }
    }
}
