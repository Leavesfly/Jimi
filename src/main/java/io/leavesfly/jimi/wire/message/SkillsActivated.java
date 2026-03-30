package io.leavesfly.jimi.wire.message;

import io.leavesfly.jimi.skill.SkillSpec;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Skills 激活消息
 * 
 * 当一个或多个 Skills 被激活并注入到上下文时，发送此消息通知 UI 层
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillsActivated implements WireMessage {
    
    /**
     * 激活的 Skill 名称列表
     */
    private List<String> skillNames;
    
    /**
     * 激活的 Skill 总数
     */
    private int count;
    
    /**
     * 从 SkillSpec 列表创建消息
     */
    public static SkillsActivated from(List<SkillSpec> skills) {
        List<String> names = skills.stream()
                .map(SkillSpec::getName)
                .collect(Collectors.toList());
        return new SkillsActivated(names, names.size());
    }
    
    @Override
    public String getMessageType() {
        return "SkillsActivated";
    }
}
