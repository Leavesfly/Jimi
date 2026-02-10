package io.leavesfly.jimi.adk.api.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Skill 服务接口
 * <p>
 * 定义 Skill 系统的核心能力契约，供 adk-core 和上层应用通过接口依赖 Skill 能力，
 * 而无需直接依赖 adk-skill 实现模块。
 * </p>
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>根据用户输入匹配相关 Skills</li>
 *   <li>将匹配的 Skills 格式化为可注入上下文的内容</li>
 *   <li>管理 Skill 的生命周期（加载、注册、查询）</li>
 * </ul>
 */
public interface SkillService {

    /**
     * 根据用户输入匹配相关的 Skills
     *
     * @param inputText 用户输入文本
     * @return 匹配的 Skill 描述列表（按得分降序排序）
     */
    List<SkillDescriptor> matchSkills(String inputText);

    /**
     * 将匹配的 Skills 格式化为可注入上下文的系统消息内容
     *
     * @param inputText 用户输入文本
     * @param workDir   工作目录（用于脚本执行）
     * @return 格式化的系统消息文本，如果没有新 Skill 则返回 null
     */
    String matchAndFormat(String inputText, Path workDir);

    /**
     * 加载项目级 Skills
     *
     * @param projectSkillsDir 项目 Skills 目录
     */
    void loadProjectSkills(Path projectSkillsDir);

    /**
     * 获取当前已激活的 Skill 名称列表
     *
     * @return 已激活的 Skill 名称列表
     */
    List<String> getActiveSkillNames();

    /**
     * 清理所有激活状态
     */
    void clearActiveSkills();

    /**
     * 获取所有已注册的 Skill 描述列表
     *
     * @return Skill 描述列表
     */
    List<SkillDescriptor> getAllSkills();

    /**
     * 获取 Skill 系统的统计信息
     *
     * @return 统计信息 Map
     */
    Map<String, Object> getStatistics();

    /**
     * 检查 Skill 系统是否已初始化
     *
     * @return 是否已初始化
     */
    boolean isInitialized();
}
