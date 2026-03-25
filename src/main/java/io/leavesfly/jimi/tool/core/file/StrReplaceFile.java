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
import java.util.ArrayList;
import java.util.List;

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
        @JsonPropertyDescription("要编辑的文件绝对路径。支持绝对路径或相对于工作目录的相对路径")
        private String path;
        
        /**
         * 要替换的旧字符串（精确匹配）
         */
        @JsonPropertyDescription("要替换的原始字符串，必须与文件中的内容完全一致（包括缩进、空格和换行）。建议先用 ReadFile 确认文件内容后再填写")
        private String old_str;
        
        /**
         * 替换后的新字符串
         */
        @JsonPropertyDescription("替换后的新字符串。如果要删除 old_str，传空字符串 \"\" 即可。不传此参数等同于空字符串")
        @Builder.Default
        private String new_str = "";
    }
    
    public StrReplaceFile() {
        super(
            "StrReplaceFile",
            "替换文件中的字符串。old_str 必须与文件内容完全一致（包括缩进和换行）。" +
            "如果替换失败，工具会返回最相似的片段帮助你修正。" +
            "建议先用 ReadFile 查看文件内容，再调用本工具。",
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
                        "old_str is required and cannot be empty. " +
                        "If you want to insert content, use WriteFile with append mode instead.",
                        "Missing old_str"
                    ));
                }
                
                // new_str 为 null 时视为空字符串（删除操作）
                if (params.new_str == null) {
                    params.new_str = "";
                }
                
                // 检查是否为无意义替换
                if (params.old_str.equals(params.new_str)) {
                    return Mono.just(ToolResult.error(
                        "old_str and new_str are identical. No changes needed.",
                        "No-op replacement"
                    ));
                }
                
                Path rawPath = Path.of(params.path);
                
                // 支持相对路径：自动解析为绝对路径
                final Path targetPath;
                if (!rawPath.isAbsolute()) {
                    targetPath = workDir.resolve(rawPath).normalize();
                } else {
                    targetPath = rawPath;
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
            String content = Files.readString(targetPath);
            
            // 精确匹配
            int index = content.indexOf(params.old_str);
            
            if (index == -1) {
                // 匹配失败，尝试提供诊断信息帮助大模型自我修正
                return Mono.just(buildNotFoundError(content, params.old_str));
            }
            
            // 检查是否有多处匹配
            int secondIndex = content.indexOf(params.old_str, index + 1);
            if (secondIndex != -1) {
                int occurrences = countOccurrences(content, params.old_str);
                log.info("old_str found {} times in file, replacing the first occurrence", occurrences);
            }
            
            // 执行替换（只替换第一次出现）
            String newContent = content.substring(0, index) +
                    params.new_str +
                    content.substring(index + params.old_str.length());
            
            Files.writeString(targetPath, newContent);
            
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
    
    /**
     * 构建匹配失败的诊断错误信息
     * 帮助大模型理解为什么匹配失败，并给出修正建议
     */
    private ToolResult buildNotFoundError(String fileContent, String oldStr) {
        StringBuilder errorMsg = new StringBuilder();
        errorMsg.append("The old_str was not found in the file. ");
        
        // 尝试忽略前后空白的匹配
        String trimmedOldStr = oldStr.trim();
        if (!trimmedOldStr.isEmpty()) {
            // 按行搜索，找最相似的片段
            String bestMatch = findMostSimilarFragment(fileContent, oldStr);
            if (bestMatch != null) {
                errorMsg.append("Did you mean this similar content?\n\n");
                errorMsg.append("```\n").append(bestMatch).append("\n```\n\n");
                errorMsg.append("Make sure old_str matches the file content exactly, ");
                errorMsg.append("including whitespace and indentation.");
            } else {
                // 尝试找到第一行是否存在
                String firstLine = oldStr.split("\n", 2)[0].trim();
                if (!firstLine.isEmpty() && fileContent.contains(firstLine)) {
                    int lineIndex = findLineNumber(fileContent, firstLine);
                    errorMsg.append(String.format(
                        "The first line \"%s\" was found at line %d, " +
                        "but the full old_str does not match. " +
                        "Please use ReadFile to check the exact content around line %d, " +
                        "then retry with the correct old_str.",
                        truncateForDisplay(firstLine, 60), lineIndex, lineIndex
                    ));
                } else {
                    errorMsg.append("The content does not exist in this file. " +
                        "Please use ReadFile to verify the file content first.");
                }
            }
        }
        
        return ToolResult.error(errorMsg.toString(), "String not found");
    }
    
    /**
     * 在文件内容中查找与 oldStr 最相似的片段
     * 使用滑动窗口 + 行级别匹配
     */
    private String findMostSimilarFragment(String fileContent, String oldStr) {
        String[] oldLines = oldStr.split("\n");
        String[] fileLines = fileContent.split("\n");
        
        if (oldLines.length == 0 || fileLines.length == 0) {
            return null;
        }
        
        // 找到第一行的最佳匹配位置
        String firstOldLine = oldLines[0].trim();
        if (firstOldLine.isEmpty()) {
            return null;
        }
        
        int bestStartLine = -1;
        double bestSimilarity = 0;
        
        for (int i = 0; i <= fileLines.length - oldLines.length; i++) {
            if (fileLines[i].trim().contains(firstOldLine) || firstOldLine.contains(fileLines[i].trim())) {
                // 计算从这个位置开始的相似度
                double similarity = calculateBlockSimilarity(fileLines, i, oldLines);
                if (similarity > bestSimilarity && similarity >= 0.5) {
                    bestSimilarity = similarity;
                    bestStartLine = i;
                }
            }
        }
        
        if (bestStartLine == -1) {
            return null;
        }
        
        // 提取匹配的片段
        int endLine = Math.min(bestStartLine + oldLines.length, fileLines.length);
        List<String> matchedLines = new ArrayList<>();
        for (int i = bestStartLine; i < endLine; i++) {
            matchedLines.add(fileLines[i]);
        }
        return String.join("\n", matchedLines);
    }
    
    /**
     * 计算两个代码块的相似度（0.0 ~ 1.0）
     */
    private double calculateBlockSimilarity(String[] fileLines, int startIndex, String[] oldLines) {
        int matchCount = 0;
        int compareLength = Math.min(oldLines.length, fileLines.length - startIndex);
        
        for (int i = 0; i < compareLength; i++) {
            String fileLine = fileLines[startIndex + i].trim();
            String oldLine = oldLines[i].trim();
            if (fileLine.equals(oldLine)) {
                matchCount++;
            }
        }
        
        return (double) matchCount / oldLines.length;
    }
    
    /**
     * 统计字符串出现次数
     */
    private int countOccurrences(String content, String target) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
    }
    
    /**
     * 查找内容所在行号（1-based）
     */
    private int findLineNumber(String content, String target) {
        int index = content.indexOf(target);
        if (index == -1) {
            return -1;
        }
        int lineNumber = 1;
        for (int i = 0; i < index; i++) {
            if (content.charAt(i) == '\n') {
                lineNumber++;
            }
        }
        return lineNumber;
    }
    
    /**
     * 截断字符串用于显示
     */
    private String truncateForDisplay(String str, int maxLength) {
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }
}
