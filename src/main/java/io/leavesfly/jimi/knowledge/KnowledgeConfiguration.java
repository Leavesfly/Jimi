package io.leavesfly.jimi.knowledge;

import io.leavesfly.jimi.knowledge.wiki.WikiGenerator;
import io.leavesfly.jimi.knowledge.wiki.ChangeDetector;
import io.leavesfly.jimi.knowledge.wiki.WikiValidator;
import io.leavesfly.jimi.knowledge.wiki.WikiIndexManager;
import io.leavesfly.jimi.knowledge.wiki.WikiServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knowledge 模块 Spring 配置类
 * 
 * <p>负责组装所有知识模块的服务组件，实现依赖注入。
 * 
 * <p>重构后架构：
 * <pre>
 *  Layer 3:    WikiServiceImpl              (应用层)
 *                  ↓
 *  Layer 2:  MemoryManager  HybridSearchManager  (核心层)
 *                  ↓              ↓
 *  Layer 1:  GraphManager  RagManager     (基础层，平行)
 *                  ↓              ↓
 *  Layer 0:  GraphStore    VectorStore          (基础设施层)
 * </pre>
 * 
 * <p>对外提供唯一入口：{@link KnowledgeService}
 * 
 * <p>注意：大部分组件现在通过 @Component 自动注册，这里只保留 WikiServiceImpl 的配置
 */
@Slf4j
@Configuration
public class KnowledgeConfiguration {
    
    // ==================== Layer 3: 应用层服务 ====================
    
    /**
     * Wiki 服务（适配 WikiGenerator）
     * WikiServiceImpl 没有对应的 Manager，需要手动配置
     */
    @Bean
    public WikiServiceImpl wikiService(
            @Autowired WikiGenerator wikiGenerator,
            @Autowired ChangeDetector changeDetector,
            @Autowired WikiValidator wikiValidator,
            @Autowired(required = false) WikiIndexManager wikiIndexManager) {
        log.info("创建 WikiService");
        return new WikiServiceImpl(wikiGenerator, changeDetector, wikiValidator, wikiIndexManager);
    }
}
