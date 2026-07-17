package io.leavesfly.jimi.loop;

import io.leavesfly.jimi.config.info.LoopEngineeringConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loop 状态文件管理器
 * <p>
 * 管理 .jimi/progress.md 状态文件的读写。
 * 状态文件是 Loop Engineering 的"脊柱"——Agent 忘了，但文件不会忘。
 */
@Slf4j
@Service
public class LoopStateManager {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern TASK_PATTERN = Pattern.compile("^- \\[([ xX])\\] #(\\d+): (.+)$");
    private static final Pattern GOAL_PATTERN = Pattern.compile("^- \\*\\*目标\\*\\*: (.+)$", Pattern.MULTILINE);
    private static final Pattern ITERATION_PATTERN = Pattern.compile("^- 总迭代: (\\d+)$", Pattern.MULTILINE);
    private static final Pattern VERIFY_CMD_PATTERN = Pattern.compile("^- \\*\\*验证命令\\*\\*: (.+)$", Pattern.MULTILINE);
    private static final String NO_VERIFY_COMMAND = "无";

    @Autowired
    private LoopEngineeringConfig config;

    /**
     * 读取状态文件内容
     *
     * @param workDir   工作目录
     * @param stateFile 状态文件路径（相对于 workDir）
     * @return 状态文件内容，不存在时返回空字符串
     */
    public String readState(Path workDir, String stateFile) {
        Path statePath = resolveStatePath(workDir, stateFile);
        if (!Files.exists(statePath)) {
            return "";
        }
        try {
            return Files.readString(statePath);
        } catch (IOException e) {
            log.error("Failed to read state file: {}", statePath, e);
            return "";
        }
    }

    /**
     * 写入状态文件
     *
     * @param workDir   工作目录
     * @param stateFile 状态文件路径（相对于 workDir）
     * @param content   文件内容
     */
    public void writeState(Path workDir, String stateFile, String content) {
        Path statePath = resolveStatePath(workDir, stateFile);
        try {
            Files.createDirectories(statePath.getParent());
            Files.writeString(statePath, content);
            log.debug("State file updated: {}", statePath);
        } catch (IOException e) {
            log.error("Failed to write state file: {}", statePath, e);
        }
    }

    /**
     * 追加任务到状态文件
     *
     * @param workDir     工作目录
     * @param stateFile   状态文件路径
     * @param description 任务描述
     * @param completed   是否已完成
     */
    public void appendTask(Path workDir, String stateFile, String description, boolean completed) {
        String content = readState(workDir, stateFile);
        int nextId = countTasks(content) + 1;
        String checkbox = completed ? "x" : " ";
        String taskLine = String.format("- [%s] #%d: %s", checkbox, nextId, description);

        // 找到合适的位置插入
        String section = completed ? "## 已完成" : "## 进行中";
        if (content.contains(section)) {
            int sectionEnd = findSectionEnd(content, section);
            content = content.substring(0, sectionEnd) + taskLine + "\n" + content.substring(sectionEnd);
        } else {
            content = content + "\n" + taskLine + "\n";
        }

        writeState(workDir, stateFile, content);
    }

    /**
     * 更新任务状态
     *
     * @param workDir   工作目录
     * @param stateFile 状态文件路径
     * @param taskId    任务 ID
     * @param completed 是否已完成
     */
    public void updateTask(Path workDir, String stateFile, int taskId, boolean completed) {
        String content = readState(workDir, stateFile);
        String newCheckbox = completed ? "x" : " ";

        String[] lines = content.split("\n");
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            Matcher matcher = TASK_PATTERN.matcher(line);
            if (matcher.matches() && Integer.parseInt(matcher.group(2)) == taskId) {
                // 仅重写行首复选框，避免误改任务描述中的 [ ]/[x]
                line = String.format("- [%s] #%s: %s", newCheckbox, matcher.group(2), matcher.group(3));
            }
            result.append(line).append("\n");
        }

        writeState(workDir, stateFile, result.toString());
    }

    /**
     * 生成新的状态报告
     *
     * @param workDir       工作目录
     * @param stateFile     状态文件路径
     * @param goalCondition 目标条件描述
     * @param verifyCommand 确定性验证命令（可为 null，表示使用 LLM 验证）
     * @return 初始化后的状态文件内容
     */
    public String initializeState(Path workDir, String stateFile, String goalCondition, String verifyCommand) {
        String now = LocalDateTime.now().format(DATE_FORMAT);
        String template = String.format("""
                # Loop Progress
                
                ## 当前目标
                - **开始时间**: %s
                - **目标**: %s
                - **验证命令**: %s
                
                ## 已完成
                
                ## 进行中
                
                ## 待处理
                
                ## 统计
                - 总迭代: 0
                - Token 消耗: 0
                """, now, goalCondition,
                (verifyCommand == null || verifyCommand.isBlank()) ? NO_VERIFY_COMMAND : verifyCommand);

        writeState(workDir, stateFile, template);
        return template;
    }

    /**
     * 更新统计信息
     *
     * @param workDir        工作目录
     * @param stateFile      状态文件路径
     * @param iterationCount 迭代次数
     */
    public void updateStats(Path workDir, String stateFile, int iterationCount) {
        String content = readState(workDir, stateFile);
        // 替换迭代次数
        content = content.replaceAll("- 总迭代: \\d+", "- 总迭代: " + iterationCount);
        writeState(workDir, stateFile, content);
    }

    /**
     * 更新 Token 消耗统计
     *
     * @param workDir   工作目录
     * @param stateFile 状态文件路径
     * @param tokens    本次 goal 消耗的 token 数
     */
    public void updateTokenStats(Path workDir, String stateFile, long tokens) {
        String content = readState(workDir, stateFile);
        content = content.replaceAll("- Token 消耗: \\d+", "- Token 消耗: " + tokens);
        writeState(workDir, stateFile, content);
    }

    /**
     * 从状态文件中解析目标条件（用于跨会话续跑）
     *
     * @param workDir   工作目录
     * @param stateFile 状态文件路径
     * @return 目标条件，不存在时返回 null
     */
    public String parseGoalCondition(Path workDir, String stateFile) {
        String content = readState(workDir, stateFile);
        if (content.isEmpty()) {
            return null;
        }
        Matcher matcher = GOAL_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * 从状态文件中解析已执行的迭代次数（用于跨会话续跑）
     *
     * @param workDir   工作目录
     * @param stateFile 状态文件路径
     * @return 已执行迭代次数，不存在时返回 0
     */
    public int parseIterationCount(Path workDir, String stateFile) {
        String content = readState(workDir, stateFile);
        if (content.isEmpty()) {
            return 0;
        }
        Matcher matcher = ITERATION_PATTERN.matcher(content);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /**
     * 从状态文件中解析确定性验证命令（用于跨会话续跑）
     *
     * @param workDir   工作目录
     * @param stateFile 状态文件路径
     * @return 验证命令，未配置或不存在时返回 null
     */
    public String parseVerifyCommand(Path workDir, String stateFile) {
        String content = readState(workDir, stateFile);
        if (content.isEmpty()) {
            return null;
        }
        Matcher matcher = VERIFY_CMD_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        String cmd = matcher.group(1).trim();
        return NO_VERIFY_COMMAND.equals(cmd) ? null : cmd;
    }

    /**
     * 获取默认状态文件路径
     */
    public String getDefaultStateFile() {
        return config.getDefaultStateFile();
    }

    // ==================== 内部方法 ====================

    private Path resolveStatePath(Path workDir, String stateFile) {
        if (stateFile == null || stateFile.isEmpty()) {
            stateFile = config.getDefaultStateFile();
        }
        Path base = workDir.normalize();
        Path resolved = base.resolve(stateFile).normalize();
        // 防止状态文件路径逃逸工作目录
        if (!resolved.startsWith(base)) {
            log.warn("State file path escapes work dir, falling back to default: {}", stateFile);
            resolved = base.resolve(config.getDefaultStateFile()).normalize();
        }
        return resolved;
    }

    private int countTasks(String content) {
        int count = 0;
        Matcher matcher = TASK_PATTERN.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int findSectionEnd(String content, String sectionHeader) {
        int sectionStart = content.indexOf(sectionHeader);
        if (sectionStart == -1) {
            return content.length();
        }
        // 找到 section 内容结束位置（下一个 ## 或文件末尾）
        int afterHeader = content.indexOf("\n", sectionStart) + 1;
        int nextSection = content.indexOf("\n## ", afterHeader);
        return nextSection == -1 ? content.length() : nextSection;
    }
}
