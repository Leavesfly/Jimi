package io.leavesfly.jimi.adk.api.exception;

/**
 * 配置异常
 * 当配置文件无效或配置加载失败时抛出
 */
public class ConfigException extends AdkException {
    
    public ConfigException(String message) {
        super(message);
    }
    
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
