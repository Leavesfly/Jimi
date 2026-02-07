package io.leavesfly.jimi.adk.tools.file;

import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.tools.base.AbstractTool;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 写入文件工具
 */
@Slf4j
public class WriteFileTool extends AbstractTool<WriteFileTool.Params> {
    
    /**
     * 工作目录
     */
    private final Path workDir;
    
    public WriteFileTool(Path workDir) {
        super("write_file", 
              "创建或覆盖文件内容。会自动创建父目录。", 
              Params.class);
        this.workDir = workDir;
    }
    
    @Override
    public boolean requiresApproval() {
        return true;  // 写文件操作需要审批
    }
    
    @Override
    public String getApprovalDescription(Params params) {
        return "写入文件: " + params.filePath;
    }
    
    @Override
    protected Mono<ToolResult> doExecute(Params params) {
        return Mono.fromCallable(() -> {
            Path filePath = resolveFilePath(params.filePath);
            
            // 创建父目录
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            
            // 写入文件
            Files.writeString(filePath, params.content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            
            log.info("文件已写入: {}", filePath);
            return ToolResult.success("文件已成功写入: " + params.filePath);
        });
    }
    
    /**
     * 解析文件路径
     */
    private Path resolveFilePath(String filePath) {
        Path path = Path.of(filePath);
        if (path.isAbsolute()) {
            return path;
        }
        return workDir.resolve(path).normalize();
    }
    
    /**
     * 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("要写入的文件路径")
        private String filePath;
        
        @JsonPropertyDescription("要写入的文件内容")
        private String content;
    }
}
