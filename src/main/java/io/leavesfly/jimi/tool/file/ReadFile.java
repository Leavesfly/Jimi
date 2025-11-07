package io.leavesfly.jimi.tool.file;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * ReadFile工具
 * 用于读取文件内容
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ReadFile extends AbstractTool<ReadFile.Params> {
    
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
    public Mono<ToolResult> execute(Params params) {
        return Mono.fromCallable(() -> {
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
                
                // 读取文件
                List<String> allLines = Files.readAllLines(path);
                int startLine = params.getLineOffset() - 1; // 转为0-based索引
                
                // 检查起始行是否超出文件范围
                if (startLine >= allLines.size()) {
                    return ToolResult.error(
                            String.format("起始行号 %d 超出文件范围（文件共 %d 行）。", 
                                    params.getLineOffset(), allLines.size()),
                            "行号超出范围"
                    );
                }
                
                int endLine = Math.min(startLine + params.getNLines(), allLines.size());
                endLine = Math.min(endLine, startLine + MAX_LINES);
                
                List<String> lines = new ArrayList<>();
                List<Integer> truncatedLineNumbers = new ArrayList<>();
                int nBytes = 0;
                boolean maxLinesReached = false;
                boolean maxBytesReached = false;
                
                for (int i = startLine; i < endLine; i++) {
                    String line = allLines.get(i);
                    String truncated = truncateLine(line, MAX_LINE_LENGTH);
                    
                    if (!truncated.equals(line)) {
                        truncatedLineNumbers.add(i + 1); // 转回1-based
                    }
                    
                    lines.add(truncated);
                    nBytes += truncated.getBytes().length;
                    
                    if (lines.size() >= MAX_LINES) {
                        maxLinesReached = true;
                        break;
                    }
                    
                    if (nBytes >= MAX_BYTES) {
                        maxBytesReached = true;
                        break;
                    }
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
        });
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
        private String path;
        
        /**
         * 起始行号（从1开始）
         */
        @Builder.Default
        private int lineOffset = 1;
        
        /**
         * 读取行数
         */
        @Builder.Default
        private int nLines = MAX_LINES;
    }
}
