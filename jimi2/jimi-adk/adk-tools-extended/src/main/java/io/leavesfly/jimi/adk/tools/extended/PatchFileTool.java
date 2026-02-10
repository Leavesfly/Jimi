package io.leavesfly.jimi.adk.tools.extended;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
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
import java.util.Arrays;
import java.util.List;

/**
 * PatchFile 工具 - 应用 unified diff patch
 * <p>
 * 使用 unified diff 格式（类似 git diff）精确修改文件内容。
 * 比 WriteFile 更安全，比 StrReplaceFile 更灵活。
 * </p>
 *
 * @author Jimi2 Team
 */
@Slf4j
public class PatchFileTool extends AbstractTool<PatchFileTool.Params> {

    private final Runtime runtime;

    public PatchFileTool(Runtime runtime) {
        super("patch_file",
                "Apply a unified diff patch to a file. Use unified diff format (like 'git diff').",
                Params.class);
        this.runtime = runtime;
    }

    @Override
    public boolean requiresApproval() {
        return true;
    }

    @Override
    public String getApprovalDescription(Params params) {
        return "Edit file (patch): " + params.path;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("File path (absolute or relative to workDir)")
        private String path;

        @JsonPropertyDescription("Unified diff content (same format as 'diff -u' or 'git diff')")
        private String diff;
    }

    @Override
    public Mono<ToolResult> doExecute(Params params) {
        return Mono.defer(() -> {
            try {
                if (params.path == null || params.path.trim().isEmpty()) {
                    return Mono.just(ToolResult.error("File path is required"));
                }

                if (params.diff == null || params.diff.trim().isEmpty()) {
                    return Mono.just(ToolResult.error("Diff content is required"));
                }

                Path targetPath = Path.of(params.path);
                if (!targetPath.isAbsolute()) {
                    targetPath = runtime.getConfig().getWorkDir().resolve(targetPath);
                }

                if (!Files.exists(targetPath)) {
                    return Mono.just(ToolResult.error(String.format("`%s` does not exist", params.path)));
                }

                if (!Files.isRegularFile(targetPath)) {
                    return Mono.just(ToolResult.error(String.format("`%s` is not a file", params.path)));
                }

                // 验证路径在 workDir 内
                Path resolvedPath = targetPath.toRealPath();
                Path resolvedWorkDir = runtime.getConfig().getWorkDir().toRealPath();
                if (!resolvedPath.startsWith(resolvedWorkDir)) {
                    return Mono.just(ToolResult.error(
                            String.format("`%s` is outside working directory", params.path)));
                }

                // 读取原始文件
                List<String> originalLines = Files.readAllLines(targetPath);

                // 解析并应用 patch
                List<String> diffLines = Arrays.asList(params.diff.split("\n"));
                Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(diffLines);

                if (patch.getDeltas().isEmpty()) {
                    return Mono.just(ToolResult.error("No valid hunks found in diff content"));
                }

                List<String> patchedLines;
                try {
                    patchedLines = patch.applyTo(originalLines);
                } catch (PatchFailedException e) {
                    return Mono.just(ToolResult.error(
                            "Failed to apply patch - may not be compatible with file content: " + e.getMessage()));
                }

                if (patchedLines.equals(originalLines)) {
                    return Mono.just(ToolResult.error("No changes made. Patch does not apply to file."));
                }

                // 写回文件
                Files.write(targetPath, patchedLines);

                int totalHunks = patch.getDeltas().size();
                log.info("Successfully patched file: {} ({} hunks)", targetPath, totalHunks);

                return Mono.just(ToolResult.success(
                        String.format("File successfully patched. Applied %d hunk(s).", totalHunks)));

            } catch (Exception e) {
                log.error("Failed to patch file: {}", params.path, e);
                return Mono.just(ToolResult.error("Failed to patch file: " + e.getMessage()));
            }
        });
    }
}
