package io.leavesfly.jimi.tool.file;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.engine.approval.ApprovalResponse;
import io.leavesfly.jimi.engine.approval.Approval;
import io.leavesfly.jimi.engine.runtime.BuiltinSystemPromptArgs;
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
        @JsonPropertyDescription("需要被替换的原始字符串，必须精确匹配文件中的内容（包括空格、换行等）")
        private String old;
        
        /**
         * 替换后的新字符串（可以是多行）
         */
        @JsonPropertyDescription("用于替换的新字符串。默认为空字符串（即删除 old 内容）")
        @Builder.Default
        private String newText = "";
        
        /**
         * 是否替换所有出现的位置
         */
        @JsonPropertyDescription("是否替换文件中所有匹配的位置。true 表示全部替换，false 表示只替换第一次出现。默认为 false")
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
        @JsonPropertyDescription("要编辑的文件绝对路径，必须是完整路径（例如：/home/user/file.txt）")
        private String path;
        
        /**
         * 编辑操作（单个或列表）
         */
        @JsonPropertyDescription("要执行的编辑操作列表，每个操作包含 old、newText 和 replaceAll 字段")
        private List<Edit> edits;
    }
    
    public StrReplaceFile() {
        super(
            "StrReplaceFile",
            "对文件应用字符串替换。支持单个或多个编辑操作。",
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
                // 验证参数
                if (params.path == null || params.path.trim().isEmpty()) {
                    return Mono.just(ToolResult.error(
                        "File path is required. Please provide a valid file path.",
                        "Missing path"
                    ));
                }
                
                if (params.edits == null || params.edits.isEmpty()) {
                    return Mono.just(ToolResult.error(
                        "Edits are required. Please provide at least one edit operation.",
                        "Missing edits"
                    ));
                }
                
                // 验证每个编辑操作
                for (int i = 0; i < params.edits.size(); i++) {
                    Edit edit = params.edits.get(i);
                    if (edit.old == null || edit.old.isEmpty()) {
                        return Mono.just(ToolResult.error(
                            String.format("Edit #%d: 'old' string is required and cannot be empty.", i + 1),
                            "Invalid edit"
                        ));
                    }
                    // 验证 newText 不为 null
                    if (edit.newText == null) {
                        edit.newText = "";  // 如果为 null，设置为空字符串
                    }
                    // 检查是否为无意义替换
                    if (edit.old.equals(edit.newText)) {
                        log.warn("Edit #{}: old and newText are identical, this is a no-op replacement", i + 1);
                    }
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
                
                // 请求审批
                return approval.requestApproval("replace-file", EDIT_ACTION, String.format("Edit file `%s`", params.path))
                    .flatMap(response -> {
                        if (response == ApprovalResponse.REJECT) {
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
}
