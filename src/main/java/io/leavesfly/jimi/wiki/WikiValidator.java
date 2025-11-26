package io.leavesfly.jimi.wiki;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Wiki 文档验证器
 * <p>
 * 职责：
 * - 验证文档链接有效性
 * - 验证代码引用准确性
 * - 验证 Mermaid 图表语法
 * - 检查文档完整性
 */
@Slf4j
@Component
public class WikiValidator {
    
    /**
     * 验证 Wiki 文档
     *
     * @param wikiPath Wiki 目录路径
     * @return 验证报告
     */
    public ValidationReport validate(Path wikiPath) {
        ValidationReport report = new ValidationReport();
        
        if (!Files.exists(wikiPath) || !Files.isDirectory(wikiPath)) {
            report.addError("Wiki 目录不存在: " + wikiPath);
            return report;
        }
        
        try {
            // 1. 检查必需文件
            validateRequiredFiles(wikiPath, report);
            
            // 2. 验证所有 Markdown 文件
            try (Stream<Path> paths = Files.walk(wikiPath)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().endsWith(".md"))
                     .forEach(docPath -> validateDocument(wikiPath, docPath, report));
            }
            
            // 3. 生成摘要
            report.generateSummary();
            
        } catch (IOException e) {
            log.error("Failed to validate wiki", e);
            report.addError("验证过程出错: " + e.getMessage());
        }
        
        return report;
    }
    
    /**
     * 验证必需文件
     */
    private void validateRequiredFiles(Path wikiPath, ValidationReport report) {
        String[] requiredFiles = {"README.md"};
        
        for (String file : requiredFiles) {
            Path filePath = wikiPath.resolve(file);
            if (!Files.exists(filePath)) {
                report.addError("缺少必需文件: " + file);
            }
        }
    }
    
    /**
     * 验证单个文档
     */
    private void validateDocument(Path wikiPath, Path docPath, ValidationReport report) {
        try {
            String content = Files.readString(docPath);
            String relativePath = wikiPath.relativize(docPath).toString();
            
            // 1. 验证链接
            validateLinks(wikiPath, docPath, content, report);
            
            // 2. 验证代码引用
            validateCodeReferences(docPath, content, report);
            
            // 3. 验证 Mermaid 图表
            validateMermaidDiagrams(docPath, content, report);
            
            // 4. 检查基本结构
            validateStructure(docPath, content, report);
            
            report.incrementValidatedDocs();
            
        } catch (IOException e) {
            log.error("Failed to validate document: {}", docPath, e);
            report.addError("无法读取文档: " + docPath);
        }
    }
    
    /**
     * 验证文档链接
     */
    private void validateLinks(Path wikiPath, Path docPath, String content, ValidationReport report) {
        // Markdown 链接格式: [text](url)
        Pattern linkPattern = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\)]+)\\)");
        Matcher matcher = linkPattern.matcher(content);
        
        while (matcher.find()) {
            String linkText = matcher.group(1);
            String linkUrl = matcher.group(2);
            
            // 跳过外部链接和锚点
            if (linkUrl.startsWith("http://") || linkUrl.startsWith("https://") || 
                linkUrl.startsWith("#")) {
                continue;
            }
            
            // 验证相对路径链接
            Path linkedPath = docPath.getParent().resolve(linkUrl).normalize();
            
            if (!Files.exists(linkedPath)) {
                String docName = wikiPath.relativize(docPath).toString();
                report.addWarning(String.format("文档 %s 中的链接无效: [%s](%s)", 
                    docName, linkText, linkUrl));
            }
        }
    }
    
    /**
     * 验证代码引用
     */
    private void validateCodeReferences(Path docPath, String content, ValidationReport report) {
        // 检查代码块中的文件路径引用
        Pattern codeRefPattern = Pattern.compile("```[\\w]*\\n([^`]+)```", Pattern.DOTALL);
        Matcher matcher = codeRefPattern.matcher(content);
        
        int codeBlockCount = 0;
        while (matcher.find()) {
            codeBlockCount++;
            String codeBlock = matcher.group(1);
            
            // 检查代码块是否为空
            if (codeBlock.trim().isEmpty()) {
                String docName = docPath.getFileName().toString();
                report.addWarning(String.format("文档 %s 包含空代码块", docName));
            }
        }
        
        // 检查是否缺少代码示例（对于技术文档）
        if (codeBlockCount == 0 && content.contains("示例") || content.contains("代码")) {
            String docName = docPath.getFileName().toString();
            report.addInfo(String.format("文档 %s 可能缺少代码示例", docName));
        }
    }
    
    /**
     * 验证 Mermaid 图表
     */
    private void validateMermaidDiagrams(Path docPath, String content, ValidationReport report) {
        // 查找 Mermaid 代码块
        Pattern mermaidPattern = Pattern.compile("```mermaid\\n([^`]+)```", Pattern.DOTALL);
        Matcher matcher = mermaidPattern.matcher(content);
        
        while (matcher.find()) {
            String diagram = matcher.group(1);
            
            // 基本语法检查
            if (!diagram.contains("graph") && !diagram.contains("sequenceDiagram") && 
                !diagram.contains("classDiagram") && !diagram.contains("flowchart")) {
                String docName = docPath.getFileName().toString();
                report.addWarning(String.format("文档 %s 中的 Mermaid 图表可能缺少图表类型声明", 
                    docName));
            }
            
            // 检查基本语法错误
            if (diagram.contains("-->") && diagram.contains("<--")) {
                String docName = docPath.getFileName().toString();
                report.addWarning(String.format("文档 %s 中的 Mermaid 图表可能存在语法错误（混合箭头方向）", 
                    docName));
            }
        }
    }
    
    /**
     * 验证文档结构
     */
    private void validateStructure(Path docPath, String content, ValidationReport report) {
        String docName = docPath.getFileName().toString();
        
        // 检查是否有标题
        if (!content.contains("#")) {
            report.addWarning(String.format("文档 %s 缺少标题", docName));
        }
        
        // 检查文档长度
        int lineCount = (int) content.lines().count();
        if (lineCount < 10) {
            report.addInfo(String.format("文档 %s 内容较少（仅 %d 行）", docName, lineCount));
        }
        
        // 检查是否有更新时间标记
        if (!content.contains("更新时间") && !content.contains("生成时间")) {
            report.addInfo(String.format("文档 %s 缺少时间戳", docName));
        }
    }
    
    /**
     * 验证报告
     */
    @Data
    public static class ValidationReport {
        private List<ValidationIssue> errors = new ArrayList<>();
        private List<ValidationIssue> warnings = new ArrayList<>();
        private List<ValidationIssue> infos = new ArrayList<>();
        
        private int validatedDocs = 0;
        private String summary;
        
        public void addError(String message) {
            errors.add(ValidationIssue.builder()
                .level(IssueLevel.ERROR)
                .message(message)
                .build());
        }
        
        public void addWarning(String message) {
            warnings.add(ValidationIssue.builder()
                .level(IssueLevel.WARNING)
                .message(message)
                .build());
        }
        
        public void addInfo(String message) {
            infos.add(ValidationIssue.builder()
                .level(IssueLevel.INFO)
                .message(message)
                .build());
        }
        
        public void incrementValidatedDocs() {
            validatedDocs++;
        }
        
        public void generateSummary() {
            summary = String.format("验证完成: 文档 %d 个, 错误 %d 个, 警告 %d 个, 提示 %d 个",
                validatedDocs, errors.size(), warnings.size(), infos.size());
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
        
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
        
        public boolean isClean() {
            return errors.isEmpty() && warnings.isEmpty();
        }
    }
    
    /**
     * 验证问题
     */
    @Data
    @Builder
    public static class ValidationIssue {
        private IssueLevel level;
        private String message;
    }
    
    /**
     * 问题级别
     */
    public enum IssueLevel {
        ERROR,      // 错误 - 必须修复
        WARNING,    // 警告 - 建议修复
        INFO        // 提示 - 可选优化
    }
}
