package io.leavesfly.jimi.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Enumeration;

/**
 * Skill加载器
 * 
 * 职责：
 * - 从文件系统扫描和加载Skills
 * - 解析SKILL.md文件（YAML Front Matter + Markdown内容）
 * - 支持从类路径和用户目录加载
 * 
 * 加载策略：
 * - 类路径优先：首先尝试从resources/skills加载内置Skills
 * - 用户目录回退：如果类路径不可用，回退到~/.jimi/skills
 * - 合并加载：两个位置的Skills都会被加载
 */
@Slf4j
@Service
public class SkillLoader {
    
    /**
     * YAML Front Matter的正则表达式
     * 匹配格式：
     * ---
     * key: value
     * ---
     */
    private static final Pattern FRONT_MATTER_PATTERN = 
        Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
    
    @Autowired
    @Qualifier("yamlObjectMapper")
    private ObjectMapper yamlObjectMapper;
    
    /**
     * 判断是否在JAR包内运行
     */
    private static boolean isRunningFromJar() {
        try {
            URL resource = SkillLoader.class.getClassLoader().getResource("skills");
            return resource != null && resource.getProtocol().equals("jar");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 获取全局Skills目录列表
     * 返回所有可能的全局Skills目录（按优先级排序）
     * 
     * @return 全局Skills目录列表
     */
    public List<Path> getGlobalSkillsDirectories() {
        List<Path> directories = new ArrayList<>();
        
        // JAR包模式下不返回类路径目录,而是标记为特殊处理
        if (!isRunningFromJar()) {
            // 1. 尝试从类路径加载（resources/skills）
            try {
                URL resource = SkillLoader.class.getClassLoader().getResource("skills");
                if (resource != null) {
                    Path classPathDir = Paths.get(resource.toURI());
                    if (Files.exists(classPathDir) && Files.isDirectory(classPathDir)) {
                        directories.add(classPathDir);
                        log.debug("Found skills directory in classpath: {}", classPathDir);
                    }
                }
            } catch (Exception e) {
                log.debug("Unable to load skills from classpath", e);
            }
        }
        
        // 2. 用户目录（~/.jimi/skills）
        String userHome = System.getProperty("user.home");
        Path userSkillsDir = Paths.get(userHome, ".jimi", "skills");
        if (Files.exists(userSkillsDir) && Files.isDirectory(userSkillsDir)) {
            directories.add(userSkillsDir);
            log.debug("Found skills directory in user home: {}", userSkillsDir);
        }
        
        return directories;
    }
    
    /**
     * 从JAR包内的类路径加载Skills
     * 
     * @param scope Skill作用域
     * @return 加载的SkillSpec列表
     */
    public List<SkillSpec> loadSkillsFromClasspath(SkillScope scope) {
        List<SkillSpec> skills = new ArrayList<>();
        
        if (!isRunningFromJar()) {
            log.debug("Not running from JAR, skipping classpath loading");
            return skills;
        }
        
        try {
            // 扫描skills目录下的所有子目录
            String[] skillDirs = {"code-review", "unit-testing"}; // 内置的skill目录名
            
            for (String skillDirName : skillDirs) {
                String resourcePath = "skills/" + skillDirName + "/SKILL.md";
                InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
                
                if (inputStream != null) {
                    try {
                        SkillSpec skill = parseSkillFromStream(inputStream, resourcePath);
                        if (skill != null) {
                            skill.setScope(scope);
                            // 使用虚拟路径标识
                            skill.setSkillFilePath(Paths.get("classpath:" + resourcePath));
                            
                            skills.add(skill);
                            log.debug("Loaded skill from classpath: {} ({})", skill.getName(), resourcePath);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse skill from classpath: {}", resourcePath, e);
                    } finally {
                        inputStream.close();
                    }
                }
            }
            
            log.info("Loaded {} skills from classpath", skills.size());
        } catch (Exception e) {
            log.error("Failed to load skills from classpath", e);
        }
        
        return skills;
    }
    
    /**
     * 从指定目录加载所有Skills
     * 
     * @param directory Skills根目录
     * @param scope Skill作用域
     * @return 加载的SkillSpec列表
     */
    public List<SkillSpec> loadSkillsFromDirectory(Path directory, SkillScope scope) {
        List<SkillSpec> skills = new ArrayList<>();
        
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            log.debug("Skills directory not found: {}", directory);
            return skills;
        }
        
        try {
            Files.list(directory)
                .filter(Files::isDirectory)
                .forEach(skillDir -> {
                    Path skillFile = skillDir.resolve("SKILL.md");
                    if (Files.exists(skillFile)) {
                        try {
                            SkillSpec skill = parseSkillFile(skillFile);
                            if (skill != null) {
                                // 设置作用域和资源路径
                                skill.setScope(scope);
                                skill.setSkillFilePath(skillFile);
                                
                                // 检查是否有resources目录
                                Path resourcesDir = skillDir.resolve("resources");
                                if (Files.exists(resourcesDir) && Files.isDirectory(resourcesDir)) {
                                    skill.setResourcesPath(resourcesDir);
                                }
                                
                                skills.add(skill);
                                log.debug("Loaded skill: {} from {}", skill.getName(), skillFile);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse skill file: {}", skillFile, e);
                        }
                    }
                });
        } catch (IOException e) {
            log.error("Failed to list skills directory: {}", directory, e);
        }
        
        return skills;
    }
    
    /**
     * 从InputStream解析SKILL.md内容
     * 用于从JAR包内读取
     * 
     * @param inputStream 输入流
     * @param resourcePath 资源路径(用于日志)
     * @return 解析的SkillSpec对象，解析失败返回null
     */
    private SkillSpec parseSkillFromStream(InputStream inputStream, String resourcePath) {
        try {
            // 读取文件全文
            String fileContent = new String(inputStream.readAllBytes());
            
            return parseSkillContent(fileContent, resourcePath);
            
        } catch (IOException e) {
            log.error("Failed to read skill from stream: {}", resourcePath, e);
            return null;
        }
    }
    
    /**
     * 解析单个SKILL.md文件
     * 
     * 文件格式：
     * ---
     * name: skill-name
     * description: 描述
     * version: 1.0.0
     * category: 分类
     * triggers:
     *   - 关键词1
     *   - 关键词2
     * ---
     * 
     * Markdown内容...
     * 
     * @param skillFile SKILL.md文件路径
     * @return 解析的SkillSpec对象，解析失败返回null
     */
    public SkillSpec parseSkillFile(Path skillFile) {
        try {
            // 检查是否是classpath资源
            String pathStr = skillFile.toString();
            if (pathStr.startsWith("classpath:")) {
                // 从类路径读取
                String resourcePath = pathStr.substring("classpath:".length());
                InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
                if (inputStream != null) {
                    try {
                        return parseSkillFromStream(inputStream, resourcePath);
                    } finally {
                        inputStream.close();
                    }
                } else {
                    log.error("Classpath resource not found: {}", resourcePath);
                    return null;
                }
            }
            
            // 从文件系统读取
            String fileContent = Files.readString(skillFile);
            return parseSkillContent(fileContent, skillFile.toString());
            
        } catch (IOException e) {
            log.error("Failed to read skill file: {}", skillFile, e);
            return null;
        }
    }
    
    /**
     * 解析Skill内容(YAML Front Matter + Markdown)
     * 
     * @param fileContent 文件内容
     * @param filePath 文件路径(用于日志)
     * @return 解析的SkillSpec对象，解析失败返回null
     */
    private SkillSpec parseSkillContent(String fileContent, String filePath) {
        try {
            // 尝试匹配YAML Front Matter
            Matcher matcher = FRONT_MATTER_PATTERN.matcher(fileContent);
            
            String yamlContent;
            String markdownContent;
            
            if (matcher.matches()) {
                // 有Front Matter
                yamlContent = matcher.group(1);
                markdownContent = matcher.group(2).trim();
            } else {
                // 没有Front Matter，使用默认值
                log.warn("SKILL.md file missing YAML Front Matter: {}", filePath);
                yamlContent = null;
                markdownContent = fileContent.trim();
            }
            
            // 解析YAML元数据
            SkillSpec.SkillSpecBuilder builder = SkillSpec.builder();
            
            if (yamlContent != null) {
                try {
                    Map<String, Object> metadata = yamlObjectMapper.readValue(
                        yamlContent, 
                        Map.class
                    );
                    
                    // 提取字段
                    if (metadata.containsKey("name")) {
                        builder.name((String) metadata.get("name"));
                    }
                    if (metadata.containsKey("description")) {
                        builder.description((String) metadata.get("description"));
                    }
                    if (metadata.containsKey("version")) {
                        builder.version((String) metadata.get("version"));
                    }
                    if (metadata.containsKey("category")) {
                        builder.category((String) metadata.get("category"));
                    }
                    if (metadata.containsKey("triggers")) {
                        builder.triggers((List<String>) metadata.get("triggers"));
                    }
                    
                } catch (Exception e) {
                    log.warn("Failed to parse YAML Front Matter in {}, using defaults", filePath, e);
                }
            }
            
            // 设置内容
            builder.content(markdownContent);
            
            SkillSpec skill = builder.build();
            
            // 验证必填字段
            if (skill.getName() == null || skill.getName().isEmpty()) {
                log.error("Skill name is required in: {}", filePath);
                return null;
            }
            if (skill.getDescription() == null || skill.getDescription().isEmpty()) {
                log.warn("Skill description is missing in: {}", filePath);
                skill.setDescription("No description");
            }
            if (skill.getContent() == null || skill.getContent().isEmpty()) {
                log.warn("Skill content is empty in: {}", filePath);
            }
            
            return skill;
            
        } catch (Exception e) {
            log.error("Failed to parse skill content from: {}", filePath, e);
            return null;
        }
    }
}
