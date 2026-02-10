package io.leavesfly.jimi.adk.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 加载器
 * 
 * 职责：
 * - 从文件系统扫描和加载 Skills
 * - 解析 SKILL.md 文件（YAML Front Matter + Markdown 内容）
 * - 支持从类路径和用户目录加载
 * 
 * 加载策略：
 * - 类路径优先：首先尝试从 resources/skills 加载内置 Skills
 * - 用户目录回退：如果类路径不可用，回退到 ~/.jimi/skills
 * - 合并加载：两个位置的 Skills 都会被加载
 */
@Slf4j
public class SkillLoader {
    
    /**
     * YAML Front Matter 的正则表达式
     * 匹配格式：
     * ---
     * key: value
     * ---
     */
    private static final Pattern FRONT_MATTER_PATTERN = 
        Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
    
    private final ObjectMapper yamlObjectMapper;
    private final SkillConfig skillConfig;
    
    public SkillLoader(ObjectMapper yamlObjectMapper, SkillConfig skillConfig) {
        this.yamlObjectMapper = yamlObjectMapper;
        this.skillConfig = skillConfig;
    }
    
    public SkillLoader(ObjectMapper yamlObjectMapper) {
        this(yamlObjectMapper, null);
    }
    
    /**
     * 判断是否在 JAR 包内运行
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
     * 获取全局 Skills 目录列表
     */
    public List<Path> getGlobalSkillsDirectories() {
        List<Path> directories = new ArrayList<>();
        
        if (!isRunningFromJar()) {
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
        
        String userHome = System.getProperty("user.home");
        Path userSkillsDir = Paths.get(userHome, ".jimi", "skills");
        if (Files.exists(userSkillsDir) && Files.isDirectory(userSkillsDir)) {
            directories.add(userSkillsDir);
            log.debug("Found skills directory in user home: {}", userSkillsDir);
        }
        
        return directories;
    }
    
    /**
     * 从 JAR 包内的类路径加载 Skills
     */
    public List<SkillSpec> loadSkillsFromClasspath(SkillScope scope) {
        List<SkillSpec> skills = new ArrayList<>();
        
        if (!isRunningFromJar()) {
            log.debug("Not running from JAR, skipping classpath loading");
            return skills;
        }
        
        try {
            String[] skillDirs = {"code-review", "unit-testing"};
            
            for (String skillDirName : skillDirs) {
                String resourcePath = "skills/" + skillDirName + "/SKILL.md";
                InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
                
                if (inputStream != null) {
                    try {
                        SkillSpec skill = parseSkillFromStream(inputStream, resourcePath);
                        if (skill != null) {
                            skill.setScope(scope);
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
     * 从指定目录加载所有 Skills
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
                                skill.setScope(scope);
                                skill.setSkillFilePath(skillFile);
                                
                                Path resourcesDir = skillDir.resolve("resources");
                                if (Files.exists(resourcesDir) && Files.isDirectory(resourcesDir)) {
                                    skill.setResourcesPath(resourcesDir);
                                }
                                
                                Path scriptsDir = skillDir.resolve("scripts");
                                if (Files.exists(scriptsDir) && Files.isDirectory(scriptsDir)) {
                                    skill.setScriptsPath(scriptsDir);
                                    log.debug("Found scripts directory for skill: {}", skill.getName());
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
     * 从 InputStream 解析 SKILL.md 内容
     */
    private SkillSpec parseSkillFromStream(InputStream inputStream, String resourcePath) {
        try {
            String fileContent = new String(inputStream.readAllBytes());
            return parseSkillContent(fileContent, resourcePath);
        } catch (IOException e) {
            log.error("Failed to read skill from stream: {}", resourcePath, e);
            return null;
        }
    }
    
    /**
     * 解析单个 SKILL.md 文件
     */
    public SkillSpec parseSkillFile(Path skillFile) {
        try {
            String pathStr = skillFile.toString();
            if (pathStr.startsWith("classpath:")) {
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
            
            String fileContent = Files.readString(skillFile);
            return parseSkillContent(fileContent, skillFile.toString());
            
        } catch (IOException e) {
            log.error("Failed to read skill file: {}", skillFile, e);
            return null;
        }
    }
    
    /**
     * 解析 Skill 内容 (YAML Front Matter + Markdown)
     */
    @SuppressWarnings("unchecked")
    private SkillSpec parseSkillContent(String fileContent, String filePath) {
        try {
            Matcher matcher = FRONT_MATTER_PATTERN.matcher(fileContent);
            
            String yamlContent;
            String markdownContent;
            
            if (matcher.matches()) {
                yamlContent = matcher.group(1);
                markdownContent = matcher.group(2).trim();
            } else {
                log.warn("SKILL.md file missing YAML Front Matter: {}", filePath);
                yamlContent = null;
                markdownContent = fileContent.trim();
            }
            
            SkillSpec.SkillSpecBuilder builder = SkillSpec.builder();
            
            if (yamlContent != null) {
                try {
                    Map<String, Object> metadata = yamlObjectMapper.readValue(yamlContent, Map.class);
                    
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
                    if (metadata.containsKey("license")) {
                        builder.license((String) metadata.get("license"));
                    }
                    if (metadata.containsKey("triggers")) {
                        builder.triggers((List<String>) metadata.get("triggers"));
                    }
                    if (metadata.containsKey("dependencies")) {
                        builder.dependencies((List<String>) metadata.get("dependencies"));
                    }
                    if (metadata.containsKey("requires")) {
                        SkillSpec temp = builder.build();
                        if (temp.getDependencies() == null || temp.getDependencies().isEmpty()) {
                            builder.dependencies((List<String>) metadata.get("requires"));
                        }
                    }
                    
                    // 忽略 Claude Code 特有字段
                    List<String> ignoredFields = List.of(
                        "hooks", "allowed-tools", "model", "context", "agent", "user-invocable"
                    );
                    for (String key : metadata.keySet()) {
                        if (ignoredFields.contains(key)) {
                            log.debug("Ignoring Claude Code specific field '{}' in skill '{}'", 
                                key, metadata.get("name"));
                        }
                    }
                    
                    if (metadata.containsKey("scriptPath")) {
                        builder.scriptPath((String) metadata.get("scriptPath"));
                    }
                    if (metadata.containsKey("scriptType")) {
                        builder.scriptType((String) metadata.get("scriptType"));
                    }
                    if (metadata.containsKey("autoExecute")) {
                        builder.autoExecute((Boolean) metadata.get("autoExecute"));
                    }
                    if (metadata.containsKey("scriptEnv")) {
                        builder.scriptEnv((Map<String, String>) metadata.get("scriptEnv"));
                    }
                    if (metadata.containsKey("scriptTimeout")) {
                        Object timeout = metadata.get("scriptTimeout");
                        if (timeout instanceof Integer) {
                            builder.scriptTimeout((Integer) timeout);
                        }
                    }
                    
                } catch (Exception e) {
                    log.warn("Failed to parse YAML Front Matter in {}, using defaults", filePath, e);
                }
            }
            
            builder.content(markdownContent);
            
            SkillSpec skill = builder.build();
            
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
            
            // 兼容 Claude Code Skill: 如果没有 triggers，自动生成
            if ((skill.getTriggers() == null || skill.getTriggers().isEmpty()) 
                    && isClaudeCodeCompatibilityEnabled()) {
                List<String> autoTriggers = generateAutoTriggers(skill);
                skill.setTriggers(autoTriggers);
                log.info("Auto-generated triggers for Claude Code compatible skill '{}': {}", 
                    skill.getName(), autoTriggers);
            }
            
            return skill;
            
        } catch (Exception e) {
            log.error("Failed to parse skill content from: {}", filePath, e);
            return null;
        }
    }
    
    /**
     * 自动生成 Triggers (兼容 Claude Code Skill)
     */
    private List<String> generateAutoTriggers(SkillSpec skill) {
        Set<String> triggers = new LinkedHashSet<>();
        
        String name = skill.getName();
        String description = skill.getDescription();
        
        if (name != null && !name.isEmpty()) {
            String fullName = name.replaceAll("[-_]", " ").trim();
            if (!fullName.isEmpty()) {
                triggers.add(fullName);
            }
            
            String[] nameParts = name.split("[-_\\s]+");
            for (String part : nameParts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && trimmed.length() > 2) {
                    triggers.add(trimmed);
                }
            }
        }
        
        if (description != null && !description.isEmpty()) {
            String[] words = description.toLowerCase()
                    .replaceAll("[^a-z\\s\\u4e00-\\u9fa5]", " ")
                    .split("\\s+");
            
            Set<String> stopWords = Set.of(
                "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
                "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
                "this", "that", "these", "those", "can", "will", "your", "when",
                "这", "那", "的", "了", "是", "在", "有", "和", "或", "但", "如果"
            );
            
            for (String word : words) {
                String trimmed = word.trim();
                if (trimmed.length() >= 4 && !stopWords.contains(trimmed) && trimmed.matches("[a-z]+")) {
                    triggers.add(trimmed);
                    if (triggers.size() >= 10) break;
                }
                if (trimmed.length() >= 2 && trimmed.matches("[\\u4e00-\\u9fa5]+")) {
                    triggers.add(trimmed);
                    if (triggers.size() >= 10) break;
                }
            }
        }
        
        if (triggers.isEmpty() && name != null && !name.isEmpty()) {
            triggers.add(name);
        }
        
        return new ArrayList<>(triggers);
    }
    
    /**
     * 判断是否启用 Claude Code 兼容模式
     */
    private boolean isClaudeCodeCompatibilityEnabled() {
        if (skillConfig != null) {
            return skillConfig.isEnableClaudeCodeCompatibility();
        }
        return true;
    }
    
    /**
     * 从单个 Skill 目录加载 Skill
     */
    public SkillSpec loadSkillFromPath(Path skillPath) {
        if (skillPath == null || !Files.exists(skillPath)) {
            log.warn("Skill path does not exist: {}", skillPath);
            return null;
        }
        
        Path skillFile;
        if (Files.isDirectory(skillPath)) {
            skillFile = skillPath.resolve("SKILL.md");
        } else {
            skillFile = skillPath;
        }
        
        if (!Files.exists(skillFile)) {
            log.warn("SKILL.md not found in: {}", skillPath);
            return null;
        }
        
        SkillSpec skill = parseSkillFile(skillFile);
        if (skill != null) {
            skill.setSkillFilePath(skillFile);
            
            Path skillDir = Files.isDirectory(skillPath) ? skillPath : skillPath.getParent();
            Path resourcesDir = skillDir.resolve("resources");
            if (Files.exists(resourcesDir) && Files.isDirectory(resourcesDir)) {
                skill.setResourcesPath(resourcesDir);
            }
            
            Path scriptsDir = skillDir.resolve("scripts");
            if (Files.exists(scriptsDir) && Files.isDirectory(scriptsDir)) {
                skill.setScriptsPath(scriptsDir);
            }
        }
        
        return skill;
    }
    
    /**
     * 获取用户 Skills 目录
     */
    public Path getUserSkillsDirectory() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".jimi", "skills");
    }
}
