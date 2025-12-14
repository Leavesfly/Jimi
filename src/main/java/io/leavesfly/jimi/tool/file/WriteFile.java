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
        @JsonPropertyDescription("文件的绝对路径，必须是完整路径（例如：/home/user/file.txt）")
        private String path;
        
        /**
         * 要写入的内容
         */
        @JsonPropertyDescription("需要写入文件的文本内容")
        private String content;
        
        /**
         * 写入模式：overwrite（覆盖）或 append（追加）
         */
        @JsonPropertyDescription("写入模式：overwrite（覆盖原有内容）或 append（追加到文件末尾）。默认为 overwrite")
        @Builder.Default
        private String mode = "overwrite";
    }
    
    /**
     * 默认构造函数（Spring 调用）
     */
    public WriteFile() {
        super(
            "WriteFile",
            "将内容写入文件。支持覆盖（overwrite）和追加（append）模式。",
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
                // 验证参数
                if (params.path == null || params.path.trim().isEmpty()) {
                    return Mono.just(ToolResult.error(
                        "File path is required. Please provide a valid file path.",
                        "Missing path"
                    ));
                }
                
                if (params.content == null) {
                    return Mono.just(ToolResult.error(
                        "Content is required. Please provide content to write.",
                        "Missing content"
                    ));
                }
                
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
                        if (response == ApprovalResponse.REJECT) {
                            return Mono.just(ToolResult.rejected());
                        }
                        
                        try {
                            // 处理转义序列（将字面量 \n、\t、\r 转换为真正的控制字符）
                            // String processedContent = unescapeContent(params.content);

                            // 转义的逻辑感觉不对，特别是在代码场景下不需要转义，否则会造成代码有问题
                            String processedContent = params.content;

                            // 写入文件
                            if ("overwrite".equals(params.mode)) {
                                Files.writeString(targetPath, processedContent);
                            } else {
                                Files.writeString(targetPath, processedContent, 
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
    
    /**
     * 处理字面量转义序列，将它们转换为真正的控制字符
     * <p>
     * 场景：当 LLM 生成的 JSON 中包含双重转义（\\n）时，Jackson 会将其解析为字面量 "\n"
     * 而不是真正的换行符。此方法将这些字面量转换为实际的控制字符。
     * 
     * @param content 原始内容
     * @return 处理后的内容
     */
    private String unescapeContent(String content) {
        if (content == null) {
            return null;
        }
        
        // 检测是否包含字面量转义序列
        if (!content.contains("\\n") && !content.contains("\\t") && 
            !content.contains("\\r") && !content.contains("\\\\")) {
            return content;
        }
        
        log.debug("Detected literal escape sequences in content, unescaping...");
        
        // 将字面量转义序列转换为真正的控制字符
        return content
            .replace("\\n", "\n")    // 换行
            .replace("\\t", "\t")    // 制表符
            .replace("\\r", "\r")    // 回车
            .replace("\\\\", "\\");   // 反斜杠
    }
}
