package io.leavesfly.jimi.tool.file;

import io.leavesfly.jimi.soul.approval.Approval;
import io.leavesfly.jimi.soul.runtime.BuiltinSystemPromptArgs;
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
import java.util.ArrayList;
import java.util.List;

/**
 * StrReplaceFile 工具 - 字符串替换文件内容
 * 支持单个或多个替换操作
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class StrReplaceFile extends AbstractTool<StrReplaceFile.Params> {
    
    private static final String EDIT_ACTION = "EDIT";
    
    private Path workDir;
    private Approval approval;
    
    /**
     * 编辑操作
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edit {
        /**
         * 要替换的旧字符串（可以是多行）
         */
        private String old;
        
        /**
         * 替换后的新字符串（可以是多行）
         */
        @Builder.Default
        private String newText = "";
        
        /**
         * 是否替换所有出现的位置
         */
        @Builder.Default
        private boolean replaceAll = false;
    }
    
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
         * 编辑操作（单个或列表）
         */
        private List<Edit> edits;
    }
    
    public StrReplaceFile() {
        super(
            "StrReplaceFile",
            "Apply string replacements to a file. Supports single or multiple edits.",
            Params.class
        );
    }
    
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
                        String.format("`%s` is not an absolute path. You must provide an absolute path to edit a file.", params.path),
                        "Invalid path"
                    ));
                }
                
                ToolResult pathError = validatePath(targetPath);
                if (pathError != null) {
                    return Mono.just(pathError);
                }
                
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
                
                // 请求审批
                return approval.requestApproval("replace-file", EDIT_ACTION, String.format("Edit file `%s`", params.path))
                    .flatMap(response -> {
                        if (response == io.leavesfly.jimi.soul.approval.ApprovalResponse.REJECT) {
                            return Mono.just(ToolResult.rejected());
                        }
                        
                        try {
                            // 读取文件内容
                            String content = Files.readString(targetPath);
                            String originalContent = content;
                            
                            List<Edit> edits = params.edits != null ? params.edits : new ArrayList<>();
                            
                            // 应用所有编辑
                            int totalReplacements = 0;
                            for (Edit edit : edits) {
                                String oldContent = content;
                                content = applyEdit(content, edit);
                                
                                // 计算替换次数
                                if (!content.equals(oldContent)) {
                                    if (edit.replaceAll) {
                                        // 计算出现次数
                                        int count = 0;
                                        int index = 0;
                                        while ((index = oldContent.indexOf(edit.old, index)) != -1) {
                                            count++;
                                            index += edit.old.length();
                                        }
                                        totalReplacements += count;
                                    } else {
                                        totalReplacements += 1;
                                    }
                                }
                            }
                            
                            // 检查是否有变化
                            if (content.equals(originalContent)) {
                                return Mono.just(ToolResult.error(
                                    "No replacements were made. The old string was not found in the file.",
                                    "No replacements made"
                                ));
                            }
                            
                            // 写回文件
                            Files.writeString(targetPath, content);
                            
                            return Mono.just(ToolResult.ok(
                                "",
                                String.format("File successfully edited. Applied %d edit(s) with %d total replacement(s).",
                                    edits.size(), totalReplacements)
                            ));
                            
                        } catch (Exception e) {
                            log.error("Failed to edit file: {}", params.path, e);
                            return Mono.just(ToolResult.error(
                                String.format("Failed to edit. Error: %s", e.getMessage()),
                                "Failed to edit file"
                            ));
                        }
                    });
                    
            } catch (Exception e) {
                log.error("Error in StrReplaceFile.execute", e);
                return Mono.just(ToolResult.error(
                    String.format("Failed to edit file. Error: %s", e.getMessage()),
                    "Failed to edit file"
                ));
            }
        });
    }
    
    /**
     * 应用单个编辑操作
     */
    private String applyEdit(String content, Edit edit) {
        if (edit.replaceAll) {
            return content.replace(edit.old, edit.newText);
        } else {
            // 只替换第一次出现
            int index = content.indexOf(edit.old);
            if (index != -1) {
                return content.substring(0, index) + 
                       edit.newText + 
                       content.substring(index + edit.old.length());
            }
            return content;
        }
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
                    String.format("`%s` is outside the working directory. You can only edit files within the working directory.", targetPath),
                    "Path outside working directory"
                );
            }
        } catch (Exception e) {
            log.warn("Failed to validate path: {}", targetPath, e);
        }
        
        return null;
    }
}
