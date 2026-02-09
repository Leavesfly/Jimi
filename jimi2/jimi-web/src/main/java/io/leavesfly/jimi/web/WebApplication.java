package io.leavesfly.jimi.web;

import io.leavesfly.jimi.web.service.WebWorkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Jimi Web 应用主入口
 * <p>
 * 提供 Manus 风格的 Web 交互界面，支持：
 * - 多会话管理
 * - 流式 AI 对话（SSE）
 * - 工具调用可视化
 * - 执行过程实时展示
 * </p>
 */
@SpringBootApplication
public class WebApplication {

    private static final Logger log = LoggerFactory.getLogger(WebApplication.class);

    public static void main(String[] args) {
        log.info("启动 Jimi Web...");

        ConfigurableApplicationContext context = SpringApplication.run(WebApplication.class, args);

        int port = context.getEnvironment().getProperty("server.port", Integer.class, 8080);
        log.info("Jimi Web 已启动: http://localhost:{}", port);
        System.out.println("\n  Jimi Web 已启动！");
        System.out.println("  访问地址: http://localhost:" + port);
        System.out.println();
    }
}
