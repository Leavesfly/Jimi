package io.leavesfly.jimi.adk.api.session;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 会话管理器接口
 * 管理会话的创建、加载和持久化
 */
public interface SessionManager {
    
    /**
     * 创建新会话
     *
     * @param workDir 工作目录
     * @return 新会话
     */
    Session createSession(Path workDir);
    
    /**
     * 加载会话
     *
     * @param sessionId 会话 ID
     * @return 会话（如果存在）
     */
    Optional<Session> loadSession(String sessionId);
    
    /**
     * 获取或创建工作目录的会话
     *
     * @param workDir 工作目录
     * @return 会话
     */
    Session getOrCreateSession(Path workDir);
    
    /**
     * 保存会话
     *
     * @param session 会话
     */
    void saveSession(Session session);
    
    /**
     * 删除会话
     *
     * @param sessionId 会话 ID
     */
    void deleteSession(String sessionId);
    
    /**
     * 列出所有会话
     *
     * @return 会话列表
     */
    List<Session> listSessions();
    
    /**
     * 获取会话存储目录
     *
     * @return 存储目录路径
     */
    Path getSessionsDirectory();
}
