package io.leavesfly.jimi.adk.tools.extended;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.tools.base.AbstractTool;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * StrReplaceFile 工具 - 字符串替换编辑文件
 * <p>
 * 简化版实现，去掉审批和沙箱机制，直接基于 ADK Runtime。
 * </p>
 *
 * @author Jimi2 Team
 */
@Slf4j
public class StrReplaceFileTool extends AbstractTool<StrReplaceFileTool.Params> {

    private final Runtime runtime;

    public StrReplaceFileTool(Runtime runtime) {
        super("str_replace_file",
                "Replace a string in a file. old_str must exactly match the file content.",
                Params.class);
        this.runtime = runtime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("File path (absolute or relative to workDir)")
        private String path;

        @JsonPropertyDescription("The exact string to be replaced (must match exactly including whitespace)")
        private String old_str;

        @JsonPropertyDescription("The new string to replace with. Use empty string to delete old_str.")
        private String new_str;
    }

    @Override
    public Mono<ToolResult> doExecute(Params params) {
        return Mono.defer(() -> {
            try {
                if (params.path == null || params.path.trim().isEmpty()) {
                    return Mono.just(ToolResult.error("File path is required"));
                }

                if (params.old_str == null || params.old_str.isEmpty()) {
                    return Mono.just(ToolResult.error("old_str is required and cannot be empty"));
                }

                String newStr = params.new_str != null ? params.new_str : "";

                if (params.old_str.equals(newStr)) {
                    log.warn("old_str and new_str are identical, no-op replacement");
                }

                Path targetPath = Path.of(params.path);
                if (!targetPath.isAbsolute()) {
                    targetPath = runtime.getWorkDir().resolve(targetPath);
                }

                if (!Files.exists(targetPath)) {
                    return Mono.just(ToolResult.error(String.format("`%s` does not exist", params.path)));
                }

                if (!Files.isRegularFile(targetPath)) {
                    return Mono.just(ToolResult.error(String.format("`%s` is not a file", params.path)));
                }

                // 验证路径在 workDir 内
                Path resolvedPath = targetPath.toRealPath();
                Path resolvedWorkDir = runtime.getWorkDir().toRealPath();
                if (!resolvedPath.startsWith(resolvedWorkDir)) {
                    return Mono.just(ToolResult.error(
                            String.format("`%s` is outside working directory", params.path)));
                }

                // 读取文件
                String content = Files.readString(targetPath);
                String originalContent = content;

                // 只替换第一次出现
                int index = content.indexOf(params.old_str);
                if (index != -1) {
                    content = content.substring(0, index) +
                            newStr +
                            content.substring(index + params.old_str.length());
                }

                if (content.equals(originalContent)) {
                    return Mono.just(ToolResult.error("No replacements made. old_str not found in file."));
                }

                // 写回
                Files.writeString(targetPath, content);

                return Mono.just(ToolResult.success("File edited successfully"));

            } catch (Exception e) {
                log.error("Failed to replace string in file: {}", params.path, e);
                return Mono.just(ToolResult.error("Failed to edit file: " + e.getMessage()));
            }
        });
    }
}
