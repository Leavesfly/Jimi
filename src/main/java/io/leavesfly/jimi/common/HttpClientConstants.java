package io.leavesfly.jimi.common;

/**
 * HTTP 客户端配置常量
 * 统一管理 HTTP 相关的缓冲区大小、超时时间等配置
 */
public final class HttpClientConstants {
    
    /**
     * 缓冲区大小（字节）
     * 用于流式读写操作
     */
    public static final int BUFFER_SIZE = 8192;
    
    /**
     * 连接超时时间（毫秒）
     * 建立 TCP 连接的最大等待时间
     */
    public static final int CONNECT_TIMEOUT = 10000;
    
    /**
     * 读取超时时间（毫秒）
     * 等待服务器响应的最大时间
     */
    public static final int READ_TIMEOUT = 30000;
    
    /**
     * 堆栈字符串最大长度限制
     * 防止异常堆栈信息过大导致内存问题
     */
    public static final int MAX_STACK_TRACE_LENGTH = 10000;
    
    private HttpClientConstants() {
        // 防止实例化
    }
}
