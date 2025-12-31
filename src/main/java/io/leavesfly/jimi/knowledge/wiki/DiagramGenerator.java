package io.leavesfly.jimi.knowledge.wiki;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mermaid 图表生成器
 * <p>
 * 职责：
 * - 自动生成架构图、类图、序列图
 * - 基于代码依赖关系生成图表
 * - 支持模块依赖分析
 */
@Slf4j
@Component
public class DiagramGenerator {
    
    /**
     * 生成模块依赖图
     *
     * @param srcPath 源代码目录
     * @return Mermaid 图表代码
     */
    public String generateModuleDiagram(Path srcPath) {
        try {
            Map<String, Set<String>> packageDeps = analyzePackageDependencies(srcPath);
            
            if (packageDeps.isEmpty()) {
                return "";
            }
            
            StringBuilder diagram = new StringBuilder();
            diagram.append("```mermaid\n");
            diagram.append("graph TB\n");
            
            // 生成节点和边
            for (Map.Entry<String, Set<String>> entry : packageDeps.entrySet()) {
                String fromPkg = simplifyPackageName(entry.getKey());
                for (String toPkg : entry.getValue()) {
                    String toSimple = simplifyPackageName(toPkg);
                    if (!fromPkg.equals(toSimple)) {
                        diagram.append(String.format("    %s --> %s\n", 
                            sanitizeNodeName(fromPkg), sanitizeNodeName(toSimple)));
                    }
                }
            }
            
            diagram.append("```\n");
            return diagram.toString();
            
        } catch (Exception e) {
            log.error("Failed to generate module diagram", e);
            return "";
        }
    }
    
    /**
     * 生成包的类图
     *
     * @param packagePath 包路径
     * @param maxClasses  最大类数量
     * @return Mermaid 类图代码
     */
    public String generateClassDiagram(Path packagePath, int maxClasses) {
        try {
            List<ClassInfo> classes = analyzeClasses(packagePath, maxClasses);
            
            if (classes.isEmpty()) {
                return "";
            }
            
            StringBuilder diagram = new StringBuilder();
            diagram.append("```mermaid\n");
            diagram.append("classDiagram\n");
            
            // 生成类定义
            for (ClassInfo classInfo : classes) {
                diagram.append(String.format("    class %s {\n", classInfo.name));
                
                // 添加方法（简化显示）
                for (String method : classInfo.methods.stream().limit(5).collect(Collectors.toList())) {
                    diagram.append(String.format("        +%s\n", method));
                }
                
                diagram.append("    }\n");
                
                // 添加继承关系
                if (classInfo.superClass != null && !classInfo.superClass.equals("Object")) {
                    diagram.append(String.format("    %s <|-- %s\n", 
                        classInfo.superClass, classInfo.name));
                }
                
                // 添加接口实现
                for (String iface : classInfo.interfaces) {
                    diagram.append(String.format("    %s <|.. %s\n", iface, classInfo.name));
                }
            }
            
            diagram.append("```\n");
            return diagram.toString();
            
        } catch (Exception e) {
            log.error("Failed to generate class diagram", e);
            return "";
        }
    }
    
    /**
     * 生成简单的架构概览图
     *
     * @param srcPath 源代码目录
     * @return Mermaid 图表代码
     */
    public String generateArchitectureOverview(Path srcPath) {
        try {
            Set<String> topLevelPackages = getTopLevelPackages(srcPath);
            
            if (topLevelPackages.isEmpty()) {
                return "";
            }
            
            StringBuilder diagram = new StringBuilder();
            diagram.append("```mermaid\n");
            diagram.append("graph TB\n");
            diagram.append("    subgraph 应用层\n");
            
            for (String pkg : topLevelPackages) {
                if (pkg.contains("cli") || pkg.contains("command")) {
                    diagram.append(String.format("        %s[%s]\n", 
                        sanitizeNodeName(pkg), pkg));
                }
            }
            
            diagram.append("    end\n");
            diagram.append("    subgraph 业务层\n");
            
            for (String pkg : topLevelPackages) {
                if (pkg.contains("engine") || pkg.contains("agent")) {
                    diagram.append(String.format("        %s[%s]\n", 
                        sanitizeNodeName(pkg), pkg));
                }
            }
            
            diagram.append("    end\n");
            diagram.append("    subgraph 基础设施层\n");
            
            for (String pkg : topLevelPackages) {
                if (pkg.contains("llm") || pkg.contains("tool") || 
                    pkg.contains("rag") || pkg.contains("mcp")) {
                    diagram.append(String.format("        %s[%s]\n", 
                        sanitizeNodeName(pkg), pkg));
                }
            }
            
            diagram.append("    end\n");
            diagram.append("```\n");
            
            return diagram.toString();
            
        } catch (Exception e) {
            log.error("Failed to generate architecture overview", e);
            return "";
        }
    }
    
    /**
     * 分析包依赖关系
     */
    private Map<String, Set<String>> analyzePackageDependencies(Path srcPath) throws IOException {
        Map<String, Set<String>> dependencies = new HashMap<>();
        
        try (Stream<Path> paths = Files.walk(srcPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(file -> {
                     try {
                         String content = Files.readString(file);
                         String packageName = extractPackage(content);
                         
                         if (packageName != null) {
                             Set<String> imports = extractImports(content);
                             dependencies.put(packageName, imports);
                         }
                     } catch (IOException e) {
                         log.debug("Failed to read file: {}", file, e);
                     }
                 });
        }
        
        return dependencies;
    }
    
    /**
     * 分析类信息
     */
    private List<ClassInfo> analyzeClasses(Path packagePath, int maxClasses) throws IOException {
        List<ClassInfo> classes = new ArrayList<>();
        
        try (Stream<Path> paths = Files.walk(packagePath)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .limit(maxClasses)
                 .forEach(file -> {
                     try {
                         String content = Files.readString(file);
                         ClassInfo classInfo = parseClassInfo(content);
                         if (classInfo != null) {
                             classes.add(classInfo);
                         }
                     } catch (IOException e) {
                         log.debug("Failed to parse class: {}", file, e);
                     }
                 });
        }
        
        return classes;
    }
    
    /**
     * 获取顶层包名
     */
    private Set<String> getTopLevelPackages(Path srcPath) throws IOException {
        Set<String> packages = new HashSet<>();
        
        try (Stream<Path> paths = Files.walk(srcPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(file -> {
                     try {
                         String content = Files.readString(file);
                         String packageName = extractPackage(content);
                         if (packageName != null) {
                             String topLevel = getTopLevelPackage(packageName);
                             if (topLevel != null) {
                                 packages.add(topLevel);
                             }
                         }
                     } catch (IOException e) {
                         log.debug("Failed to read file: {}", file, e);
                     }
                 });
        }
        
        return packages;
    }
    
    /**
     * 提取包名
     */
    private String extractPackage(String content) {
        Pattern pattern = Pattern.compile("package\\s+([\\w.]+);");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 提取导入的包
     */
    private Set<String> extractImports(String content) {
        Set<String> imports = new HashSet<>();
        Pattern pattern = Pattern.compile("import\\s+([\\w.]+);");
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String importPkg = matcher.group(1);
            // 只保留项目内的导入
            if (importPkg.startsWith("io.leavesfly.jimi")) {
                String pkg = importPkg.substring(0, importPkg.lastIndexOf('.'));
                imports.add(pkg);
            }
        }
        
        return imports;
    }
    
    /**
     * 解析类信息
     */
    private ClassInfo parseClassInfo(String content) {
        // 提取类名
        Pattern classPattern = Pattern.compile("(public\\s+)?(class|interface)\\s+(\\w+)");
        Matcher classMatcher = classPattern.matcher(content);
        
        if (!classMatcher.find()) {
            return null;
        }
        
        ClassInfo classInfo = new ClassInfo();
        classInfo.name = classMatcher.group(3);
        
        // 提取继承
        Pattern extendsPattern = Pattern.compile("extends\\s+(\\w+)");
        Matcher extendsMatcher = extendsPattern.matcher(content);
        if (extendsMatcher.find()) {
            classInfo.superClass = extendsMatcher.group(1);
        }
        
        // 提取接口
        Pattern implPattern = Pattern.compile("implements\\s+([\\w,\\s]+)");
        Matcher implMatcher = implPattern.matcher(content);
        if (implMatcher.find()) {
            String[] interfaces = implMatcher.group(1).split(",");
            for (String iface : interfaces) {
                classInfo.interfaces.add(iface.trim());
            }
        }
        
        // 提取方法
        Pattern methodPattern = Pattern.compile("(public|protected|private)\\s+\\w+\\s+(\\w+)\\s*\\(");
        Matcher methodMatcher = methodPattern.matcher(content);
        while (methodMatcher.find()) {
            classInfo.methods.add(methodMatcher.group(2) + "()");
        }
        
        return classInfo;
    }
    
    /**
     * 简化包名
     */
    private String simplifyPackageName(String packageName) {
        if (packageName == null) {
            return "unknown";
        }
        
        // 移除基础包名前缀
        String simplified = packageName.replace("io.leavesfly.jimi.", "");
        
        // 取最后一段
        int lastDot = simplified.lastIndexOf('.');
        if (lastDot > 0) {
            return simplified.substring(lastDot + 1);
        }
        
        return simplified;
    }
    
    /**
     * 获取顶层包名
     */
    private String getTopLevelPackage(String packageName) {
        if (packageName == null || !packageName.startsWith("io.leavesfly.jimi.")) {
            return null;
        }
        
        String remaining = packageName.substring("io.leavesfly.jimi.".length());
        int firstDot = remaining.indexOf('.');
        
        if (firstDot > 0) {
            return remaining.substring(0, firstDot);
        }
        
        return remaining;
    }
    
    /**
     * 清理节点名称（移除特殊字符）
     */
    private String sanitizeNodeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "_");
    }
    
    /**
     * 类信息数据模型
     */
    private static class ClassInfo {
        String name;
        String superClass;
        List<String> interfaces = new ArrayList<>();
        List<String> methods = new ArrayList<>();
    }
}
