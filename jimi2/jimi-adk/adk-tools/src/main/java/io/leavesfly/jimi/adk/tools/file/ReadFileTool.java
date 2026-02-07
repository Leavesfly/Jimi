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

/**
 * 读取文件工具
 */
@Slf4j
public class ReadFileTool extends AbstractTool<ReadFileTool.Params> {
    
    /**
     * 工作目录
     */
    private final Path workDir;
    
    public ReadFileTool(Path workDir) {
        super("read_file", 
              "读取指定文件的内容。支持读取整个文件或指定行范围。", 
              Params.class);
        this.workDir = workDir;
    }
    
    @Override
    protected Mono<ToolResult> doExecute(Params params) {
        return Mono.fromCallable(() -> {
            Path filePath = resolveFilePath(params.filePath);
            
            if (!Files.exists(filePath)) {
                return ToolResult.error("文件不存在: " + params.filePath);
            }
            
            if (Files.isDirectory(filePath)) {
                return ToolResult.error("指定路径是目录而非文件: " + params.filePath);
            }
            
            String content;
            if (params.startLine != null && params.endLine != null) {
                // 读取指定行范围
                content = readLines(filePath, params.startLine, params.endLine);
            } else {
                // 读取整个文件
                content = Files.readString(filePath);
            }
            
            return ToolResult.success(content);
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
     * 读取指定行范围
     */
    private String readLines(Path filePath, int startLine, int endLine) throws Exception {
        var lines = Files.readAllLines(filePath);
        int total = lines.size();
        
        // 调整行号（1-based 转 0-based）
        int start = Math.max(0, startLine - 1);
        int end = Math.min(total, endLine);
        
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(String.format("%6d→%s%n", i + 1, lines.get(i)));
        }
        
        return sb.toString();
    }
    
    /**
     * 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("要读取的文件路径（相对于工作目录或绝对路径）")
        private String filePath;
        
        @JsonPropertyDescription("起始行号（1-based，可选）")
        private Integer startLine;
        
        @JsonPropertyDescription("结束行号（1-based，可选）")
        private Integer endLine;
    }
}
