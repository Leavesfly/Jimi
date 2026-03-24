package io.leavesfly.jimi.core;

import io.leavesfly.jimi.llm.message.ContentPart;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Engine 接口
 * 定义 Agent 的核心行为
 */
public interface Engine {
    
    /**
     * 获取 Agent 名称
     */
    String getName();
    
    /**
     * 获取使用的模型名称
     */
    String getModel();
    
    /**
     * 获取当前状态快照
     */
    Map<String, Object> getStatus();
    
    /**
     * 运行 Agent（文本输入）
     * 
     * @param userInput 用户输入文本
     * @return 完成的 Mono
     */
    Mono<Void> run(String userInput);
    
    /**
     * 运行 Agent（多部分内容输入）
     * 
     * @param userInput 用户输入内容部分列表
     * @return 完成的 Mono
     */
    Mono<Void> run(List<ContentPart> userInput);

    /**
     * 运行 Agent（文本输入），可选择跳过知识检索和 Skill 匹配
     * 用于系统内部续问等场景，避免用系统提示词触发无意义的知识搜索
     *
     * @param userInput     用户输入文本
     * @param skipKnowledge 是否跳过知识检索和 Skill 匹配
     * @return 完成的 Mono
     */
    default Mono<Void> run(String userInput, boolean skipKnowledge) {
        return run(userInput);
    }

    /**
     * 运行 Agent（多部分内容输入），可选择跳过知识检索和 Skill 匹配
     *
     * @param userInput     用户输入内容部分列表
     * @param skipKnowledge 是否跳过知识检索和 Skill 匹配
     * @return 完成的 Mono
     */
    default Mono<Void> run(List<ContentPart> userInput, boolean skipKnowledge) {
        return run(userInput);
    }
}
