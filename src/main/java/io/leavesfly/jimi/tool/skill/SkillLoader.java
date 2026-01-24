package io.leavesfly.jimi.tool.skill;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    
    @Autowired(required = false)
    private SkillConfig skillConfig;
    
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
                                
                                // 检查是否有scripts目录（兼容Claude Code Skills）
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
                    if (metadata.containsKey("license")) {
                        builder.license((String) metadata.get("license"));
                    }
                    if (metadata.containsKey("triggers")) {
                        builder.triggers((List<String>) metadata.get("triggers"));
                    }
                    if (metadata.containsKey("dependencies")) {
                        builder.dependencies((List<String>) metadata.get("dependencies"));
                    }
                    if (metadata.containsKey("requires") && (builder.build().getDependencies() == null
                            || builder.build().getDependencies().isEmpty())) {
                        builder.dependencies((List<String>) metadata.get("requires"));
                    }
                    
                    // 忽略 Claude Code 特有字段（记录日志但不报错）
                    List<String> ignoredFields = List.of("hooks", "allowed-tools", "model", "context", "agent", "user-invocable");
                    for (String key : metadata.keySet()) {
                        if (ignoredFields.contains(key)) {
                            log.debug("Ignoring Claude Code specific field '{}' in skill '{}'", key, metadata.get("name"));
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
            
            // 兼容 Claude Code Skill: 如果没有 triggers,自动生成
            if ((skill.getTriggers() == null || skill.getTriggers().isEmpty()) && isClaudeCodeCompatibilityEnabled()) {
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
     * 从 Skill 的 name 和 description 中提取关键词作为 triggers
     * 
     * 生成策略:
     * 1. 将 name 中的连字符替换为空格,作为一个 trigger
     * 2. 将 name 按连字符拆分,每个单词作为独立 trigger
     * 3. 从 description 中提取有意义的名词和动词
     * 
     * @param skill Skill 对象
     * @return 自动生成的 triggers 列表
     */
    private List<String> generateAutoTriggers(SkillSpec skill) {
        Set<String> triggers = new java.util.LinkedHashSet<>();
        
        String name = skill.getName();
        String description = skill.getDescription();
        
        // 1. 处理 name: 将连字符替换为空格
        if (name != null && !name.isEmpty()) {
            // 添加完整名称 (将 - 和 _ 替换为空格)
            String fullName = name.replaceAll("[-_]", " ").trim();
            if (!fullName.isEmpty()) {
                triggers.add(fullName);
            }
            
            // 添加单个单词
            String[] nameParts = name.split("[-_\\s]+");
            for (String part : nameParts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && trimmed.length() > 2) {  // 过滤过短的词
                    triggers.add(trimmed);
                }
            }
        }
        
        // 2. 处理 description: 提取关键词
        if (description != null && !description.isEmpty()) {
            // 提取英文单词 (长度 >= 4,过滤停用词)
            String[] words = description.toLowerCase()
                    .replaceAll("[^a-z\\s\\u4e00-\\u9fa5]", " ")  // 保留字母和中文
                    .split("\\s+");
            
            Set<String> stopWords = Set.of(
                    "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
                    "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
                    "this", "that", "these", "those", "can", "will", "your", "when",
                    "这", "那", "的", "了", "是", "在", "有", "和", "或", "但", "如果"
            );
            
            for (String word : words) {
                String trimmed = word.trim();
                // 英文单词: 长度 >= 4, 非停用词
                if (trimmed.length() >= 4 && !stopWords.contains(trimmed) && 
                        trimmed.matches("[a-z]+")) {
                    triggers.add(trimmed);
                    if (triggers.size() >= 10) break;  // 限制数量
                }
                // 中文词: 长度 >= 2
                if (trimmed.length() >= 2 && trimmed.matches("[\\u4e00-\\u9fa5]+")) {
                    triggers.add(trimmed);
                    if (triggers.size() >= 10) break;
                }
            }
        }
        
        // 3. 如果没有提取到任何 trigger,至少使用 name
        if (triggers.isEmpty() && name != null && !name.isEmpty()) {
            triggers.add(name);
        }
        
        List<String> result = new ArrayList<>(triggers);
        log.debug("Generated {} auto-triggers from name='{}' and description='{}'", 
                result.size(), name, 
                description != null && description.length() > 50 
                    ? description.substring(0, 50) + "..." 
                    : description);
        
        return result;
    }
    
    /**
     * 判断是否启用 Claude Code 兼容模式
     */
    private boolean isClaudeCodeCompatibilityEnabled() {
        if (skillConfig != null) {
            return skillConfig.isEnableClaudeCodeCompatibility();
        }
        return true;  // 默认启用
    }
    
    // ==================== JWork 扩展方法 ====================
    
    /**
     * 从单个 Skill 目录加载 Skill
     * 
     * @param skillPath Skill 目录路径（包含 SKILL.md）
     * @return 加载的 SkillSpec，加载失败返回 null
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
            
            // 检查 resources 目录
            Path skillDir = Files.isDirectory(skillPath) ? skillPath : skillPath.getParent();
            Path resourcesDir = skillDir.resolve("resources");
            if (Files.exists(resourcesDir) && Files.isDirectory(resourcesDir)) {
                skill.setResourcesPath(resourcesDir);
            }
            
            // 检查 scripts 目录
            Path scriptsDir = skillDir.resolve("scripts");
            if (Files.exists(scriptsDir) && Files.isDirectory(scriptsDir)) {
                skill.setScriptsPath(scriptsDir);
            }
        }
        
        return skill;
    }
    
    /**
     * 获取用户 Skills 目录
     * 
     * @return 用户 Skills 目录路径 (~/.jimi/skills)
     */
    public Path getUserSkillsDirectory() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".jimi", "skills");
    }
}
