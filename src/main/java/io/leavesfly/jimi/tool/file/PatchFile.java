package io.leavesfly.jimi.tool.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import io.leavesfly.jimi.soul.approval.Approval;
import io.leavesfly.jimi.soul.approval.ApprovalResponse;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * PatchFile 工具 - 应用统一差异补丁
 * 
 * 使用 unified diff 格式（类似 git diff）修改文件内容。
 * 这是比 WriteFile 更精确的文件编辑方式。
 * 
 * 功能特性：
 * 1. 支持 unified diff 格式（diff -u, git diff）
 * 2. 只修改需要变更的部分
 * 3. 自动验证补丁是否适用
 * 4. 需要审批确认
 * 5. 安全检查（路径验证）
 * 
 * 使用场景：
 * - 修改现有文件的部分内容
 * - 精确的代码重构
 * - 批量修改多个位置
 * 
 * 优势：
 * - 比 WriteFile 更安全（不会覆盖整个文件）
 * - 比 StrReplaceFile 更灵活（支持上下文匹配）
 * - 自动处理行号偏移
 * 
 * @author 山泽
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class PatchFile extends AbstractTool<PatchFile.Params> {
    
    private static final String NAME = "PatchFile";
    private static final String DESCRIPTION = """
            Apply a unified diff patch to a file.
            
            **Tips:**
            - The patch must be in unified diff format, the format used by `diff -u` and `git diff`.
            - Only use this tool on text files.
            - The tool will fail with error returned if the patch doesn't apply cleanly.
            - The file must exist before applying the patch.
            - You should prefer this tool over WriteFile tool and Bash `sed` command when editing an existing file.
            
            **Unified Diff Format Example:**
            ```diff
            --- a/file.txt
            +++ b/file.txt
            @@ -1,3 +1,3 @@
             line1
            -old line
            +new line
             line3
            ```
            """;
    
    private Path workDir;
    private Approval approval;
    
    /**
     * PatchFile 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        
        /**
         * 文件的绝对路径
         */
        @JsonProperty("path")
        private String path;
        
        /**
         * unified diff 格式的补丁内容
         */
        @JsonProperty("diff")
        private String diff;
    }
    
    public PatchFile() {
        super(NAME, DESCRIPTION, Params.class);
    }
    
    public void setBuiltinArgs(BuiltinSystemPromptArgs builtinArgs) {
        this.workDir = builtinArgs.getJimiWorkDir();
    }
    
    public void setApproval(Approval approval) {
        this.approval = approval;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.fromCallable(() -> {
            log.debug("Patching file: {}", params.getPath());
            
            try {
                // 1. 验证路径
                Path filePath = Paths.get(params.getPath());
                
                if (!filePath.isAbsolute()) {
                    return ToolResult.error(
                            "`" + params.getPath() + "` is not an absolute path. " +
                            "You must provide an absolute path to patch a file.",
                            "Invalid path"
                    );
                }
                
                // 安全检查：确保路径在工作目录内
                ToolResult pathError = validatePath(filePath);
                if (pathError != null) {
                    return pathError;
                }
                
                // 2. 检查文件是否存在
                if (!Files.exists(filePath)) {
                    return ToolResult.error(
                            "`" + params.getPath() + "` does not exist.",
                            "File not found"
                    );
                }
                
                if (!Files.isRegularFile(filePath)) {
                    return ToolResult.error(
                            "`" + params.getPath() + "` is not a file.",
                            "Invalid path"
                    );
                }
                
                // 3. 请求审批
                ApprovalResponse response = approval.requestApproval(
                        "patch-" + System.currentTimeMillis(),
                        "EDIT",
                        "Patch file `" + params.getPath() + "`"
                ).block();
                
                if (response != ApprovalResponse.APPROVE && response != ApprovalResponse.APPROVE_FOR_SESSION) {
                    return ToolResult.rejected();
                }
                
                // 4. 读取原始文件内容
                List<String> originalLines = Files.readAllLines(filePath);
                
                // 5. 解析并应用补丁
                return applyPatch(filePath, originalLines, params.getDiff());
                
            } catch (IOException e) {
                log.error("Failed to patch file", e);
                return ToolResult.error(
                        "Failed to patch file. Error: " + e.getMessage(),
                        "Failed to patch file"
                );
            } catch (Exception e) {
                log.error("Unexpected error while patching file", e);
                return ToolResult.error(
                        "Unexpected error: " + e.getMessage(),
                        "Failed to patch file"
                );
            }
        });
    }
    
    /**
     * 验证路径安全性
     */
    private ToolResult validatePath(Path filePath) {
        try {
            Path resolvedPath = filePath.toRealPath();
            Path resolvedWorkDir = workDir.toRealPath();
            
            if (!resolvedPath.startsWith(resolvedWorkDir)) {
                return ToolResult.error(
                        "`" + filePath + "` is outside the working directory. " +
                        "You can only patch files within the working directory.",
                        "Path outside working directory"
                );
            }
        } catch (IOException e) {
            // 文件不存在时无法 toRealPath，但这会在后续检查中处理
            Path normalizedPath = filePath.normalize();
            Path normalizedWorkDir = workDir.normalize();
            
            if (!normalizedPath.startsWith(normalizedWorkDir)) {
                return ToolResult.error(
                        "`" + filePath + "` is outside the working directory.",
                        "Path outside working directory"
                );
            }
        }
        
        return null;
    }
    
    /**
     * 应用补丁
     */
    private ToolResult applyPatch(Path filePath, List<String> originalLines, String diffContent) {
        try {
            // 将 diff 内容按行分割
            List<String> diffLines = Arrays.asList(diffContent.split("\n"));
            
            // 解析 unified diff
            Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(diffLines);
            
            // 检查补丁是否为空
            if (patch.getDeltas().isEmpty()) {
                return ToolResult.error(
                        "No valid hunks found in the diff content",
                        "No hunks found"
                );
            }
            
            // 应用补丁
            List<String> patchedLines;
            try {
                patchedLines = patch.applyTo(originalLines);
            } catch (PatchFailedException e) {
                return ToolResult.error(
                        "Failed to apply patch - patch may not be compatible with the file content. " +
                        "Error: " + e.getMessage(),
                        "Patch application failed"
                );
            }
            
            // 检查是否有实际变更
            if (patchedLines.equals(originalLines)) {
                return ToolResult.error(
                        "No changes were made. The patch does not apply to the file.",
                        "No changes made"
                );
            }
            
            // 写入修改后的内容
            Files.write(filePath, patchedLines);
            
            int totalHunks = patch.getDeltas().size();
            
            log.info("Successfully patched file: {} ({} hunks)", filePath, totalHunks);
            
            return ToolResult.ok(
                    "",
                    "File successfully patched. Applied " + totalHunks + 
                    " hunk(s) to " + filePath + "."
            );
            
        } catch (Exception e) {
            log.error("Failed to parse or apply diff", e);
            return ToolResult.error(
                    "Failed to parse diff content: " + e.getMessage(),
                    "Invalid diff format"
            );
        }
    }
}
