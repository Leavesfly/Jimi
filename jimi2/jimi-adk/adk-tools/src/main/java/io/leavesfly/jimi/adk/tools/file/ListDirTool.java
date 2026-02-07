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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 列出目录内容工具
 */
@Slf4j
public class ListDirTool extends AbstractTool<ListDirTool.Params> {
    
    /**
     * 工作目录
     */
    private final Path workDir;
    
    public ListDirTool(Path workDir) {
        super("list_dir", 
              "列出指定目录的内容，包括文件和子目录。", 
              Params.class);
        this.workDir = workDir;
    }
    
    @Override
    protected Mono<ToolResult> doExecute(Params params) {
        return Mono.fromCallable(() -> {
            Path dirPath = resolveFilePath(params.path);
            
            if (!Files.exists(dirPath)) {
                return ToolResult.error("目录不存在: " + params.path);
            }
            
            if (!Files.isDirectory(dirPath)) {
                return ToolResult.error("指定路径不是目录: " + params.path);
            }
            
            String content = listDirectory(dirPath, params.recursive, 0);
            return ToolResult.success(content);
        });
    }
    
    /**
     * 解析文件路径
     */
    private Path resolveFilePath(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) {
            return workDir;
        }
        Path p = Path.of(path);
        if (p.isAbsolute()) {
            return p;
        }
        return workDir.resolve(p).normalize();
    }
    
    /**
     * 列出目录内容
     */
    private String listDirectory(Path dir, boolean recursive, int depth) throws IOException {
        StringBuilder sb = new StringBuilder();
        String indent = "  ".repeat(depth);
        
        try (Stream<Path> stream = Files.list(dir)) {
            var entries = stream.sorted().collect(Collectors.toList());
            
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean isDir = Files.isDirectory(entry);
                
                if (isDir) {
                    sb.append(indent).append("[dir] ").append(name).append("/\n");
                    if (recursive && depth < 3) {  // 限制递归深度
                        sb.append(listDirectory(entry, true, depth + 1));
                    }
                } else {
                    long size = Files.size(entry);
                    sb.append(indent).append("[file] ").append(name)
                      .append(" (").append(formatSize(size)).append(")\n");
                }
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 格式化文件大小
     */
    private String formatSize(long size) {
        if (size < 1024) {
            return size + "B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1fKB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1fMB", size / (1024.0 * 1024));
        } else {
            return String.format("%.1fGB", size / (1024.0 * 1024 * 1024));
        }
    }
    
    /**
     * 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("要列出的目录路径（默认为当前工作目录）")
        private String path;
        
        @JsonPropertyDescription("是否递归列出子目录（默认 false）")
        @Builder.Default
        private boolean recursive = false;
    }
}
