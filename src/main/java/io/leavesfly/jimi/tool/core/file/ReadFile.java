package io.leavesfly.jimi.tool.core.file;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.core.engine.context.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.tool.SyncTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * ReadFile 工具
 * 用于读取文件内容
 * <p>
 * 继承 SyncTool 基类，只需实现 executeSync() 方法，
 * 无需关心 Reactor 的 Mono 包装。
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ReadFile extends SyncTool<ReadFile.Params> {
    
    private static final String NAME = "ReadFile";
    private static final int MAX_LINES = 1000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int MAX_BYTES = 100 * 1024; // 100KB
    
    private static final String DESCRIPTION = String.format(
            "读取文件内容。最多读取%d行，每行最长%d字符，总计最多%dKB。" +
            "文件路径必须是绝对路径。如果文件太大，可以分多次读取。",
            MAX_LINES, MAX_LINE_LENGTH, MAX_BYTES / 1024
    );
    
    private Path workDir;
    
    /**
     * 默认构造函数（Spring 调用）
     */
    public ReadFile() {
        super(NAME, DESCRIPTION, Params.class);
    }
    
    /**
     * 设置工作目录（运行时注入）
     */
    public void setBuiltinArgs(BuiltinSystemPromptArgs builtinArgs) {
        this.workDir = builtinArgs.getJimiWorkDir();
    }
    
    @Override
    protected ToolResult executeSync(Params params) {
        try {
            // 验证参数
            if (params.getPath() == null || params.getPath().trim().isEmpty()) {
                return ToolResult.error(
                        "File path is required. Please provide a valid file path.",
                        "缺少路径"
                );
            }
            
            Path path = Paths.get(params.getPath());
            
            // 验证路径
            if (!path.isAbsolute()) {
                return ToolResult.error(
                        String.format("`%s` 不是绝对路径。必须提供绝对路径来读取文件。", 
                                params.getPath()),
                        "无效路径"
                );
            }
            
            if (!Files.exists(path)) {
                return ToolResult.error(
                        String.format("`%s` 不存在。", params.getPath()),
                        "文件未找到"
                );
            }
            
            if (!Files.isRegularFile(path)) {
                return ToolResult.error(
                        String.format("`%s` 不是文件。", params.getPath()),
                        "无效路径"
                );
            }
            
            // 验证行号参数
            if (params.getLineOffset() < 1) {
                return ToolResult.error(
                        String.format("起始行号必须大于等于1，当前值为：%d", params.getLineOffset()),
                        "无效的行号"
                );
            }
            
            if (params.getNLines() < 1) {
                return ToolResult.error(
                        String.format("读取行数必须大于等于1，当前值为：%d", params.getNLines()),
                        "无效的行数"
                );
            }
            
            // 文件大小检查，防止超大文件导致问题
            long fileSize = Files.size(path);
            if (fileSize > 50 * 1024 * 1024) { // 50MB
                return ToolResult.error(
                        String.format("文件过大（%d MB），超过 50MB 限制。", fileSize / (1024 * 1024)),
                        "文件过大"
                );
            }
            
            // 使用 BufferedReader 流式读取，避免大文件 OOM
            List<String> lines = new ArrayList<>();
            List<Integer> truncatedLineNumbers = new ArrayList<>();
            int nBytes = 0;
            boolean maxLinesReached = false;
            boolean maxBytesReached = false;
            boolean startLineExceeded = false;
            int totalLineCount = 0;
            
            int startLine = params.getLineOffset(); // 1-based
            int maxLinesToRead = Math.min(params.getNLines(), MAX_LINES);
            
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                int currentLine = 0;
                while ((line = reader.readLine()) != null) {
                    currentLine++;
                    totalLineCount = currentLine;
                    
                    // 跳过起始行之前的行
                    if (currentLine < startLine) {
                        continue;
                    }
                    
                    // 已经读够了就停止
                    if (lines.size() >= maxLinesToRead) {
                        maxLinesReached = true;
                        break;
                    }
                    
                    String truncated = truncateLine(line, MAX_LINE_LENGTH);
                    if (!truncated.equals(line)) {
                        truncatedLineNumbers.add(currentLine);
                    }
                    
                    lines.add(truncated);
                    nBytes += truncated.getBytes().length;
                    
                    if (nBytes >= MAX_BYTES) {
                        maxBytesReached = true;
                        break;
                    }
                }
                
                // 检查起始行是否超出文件范围
                if (lines.isEmpty() && startLine > totalLineCount) {
                    startLineExceeded = true;
                }
            }
            
            if (startLineExceeded) {
                return ToolResult.error(
                        String.format("起始行号 %d 超出文件范围（文件共 %d 行）。",
                                params.getLineOffset(), totalLineCount),
                        "行号超出范围"
                );
            }
            
            // 格式化输出（带行号）
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                int lineNum = params.getLineOffset() + i;
                output.append(String.format("%6d\t%s\n", lineNum, lines.get(i)));
            }
            
            // 构建消息
            String message = lines.size() > 0
                    ? String.format("从第%d行开始读取了%d行。", params.getLineOffset(), lines.size())
                    : "未读取到任何行。";
            
            if (maxLinesReached) {
                message += String.format(" 已达到最大%d行限制。", MAX_LINES);
            } else if (maxBytesReached) {
                message += String.format(" 已达到最大%dKB限制。", MAX_BYTES / 1024);
            } else if (lines.size() < params.getNLines()) {
                message += " 已到达文件末尾。";
            }
            
            if (!truncatedLineNumbers.isEmpty()) {
                message += String.format(" 行 %s 被截断。", truncatedLineNumbers);
            }
            
            return ToolResult.ok(output.toString(), message);
            
        } catch (Exception e) {
            log.error("读取文件失败: {}", params.getPath(), e);
            return ToolResult.error(
                    String.format("读取文件失败：%s。错误：%s", params.getPath(), e.getMessage()),
                    "读取失败"
            );
        }
    }
    
    /**
     * 截断行
     */
    private String truncateLine(String line, int maxLength) {
        if (line.length() <= maxLength) {
            return line;
        }
        String marker = "...";
        return line.substring(0, maxLength - marker.length()) + marker;
    }
    
    /**
     * ReadFile工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        /**
         * 文件的绝对路径
         */
        @JsonPropertyDescription("要读取的文件绝对路径，必须是完整路径（例如：/home/user/file.txt）")
        private String path;
        
        /**
         * 起始行号（从1开始）
         */
        @JsonPropertyDescription("开始读取的行号，从 1 开始计数。默认为 1（文件开头）")
        @Builder.Default
        private int lineOffset = 1;
        
        /**
         * 读取行数
         */
        @JsonPropertyDescription("从起始行开始读取的行数。默认为 " + MAX_LINES + " 行")
        @Builder.Default
        private int nLines = MAX_LINES;
    }
}
