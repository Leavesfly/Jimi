package io.leavesfly.jimi.work;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.work.config.WorkConfig;
import io.leavesfly.jimi.work.service.WorkService;
import io.leavesfly.jimi.work.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Jimi Work 桌面应用主入口
 * <p>
 * OpenWork 风格的 AI Agent 桌面协作台
 * 支持多会话管理、执行计划时间线、权限审批、Skills 管理
 * </p>
 */
public class WorkApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(WorkApplication.class);

    /** 工作目录（命令行指定） */
    private static Path workDir;

    /** 核心服务 */
    private WorkService workService;

    @Override
    public void init() {
        log.info("初始化 JWork...");
        applySystemTheme();
    }

    @Override
    public void start(Stage primaryStage) {
        log.info("启动 Jimi Work 桌面应用");

        try {
            Path dir = getWorkDir();

            // 1. 加载配置
            WorkConfig config = WorkConfig.load(dir);

            // 2. 检查 API Key
            LLMConfig llmConfig = config.toLLMConfig();
            if (llmConfig.getApiKey() == null || llmConfig.getApiKey().isEmpty()) {
                showError("未配置 API Key",
                        "请通过以下方式之一配置：\n"
                                + "1. 设置环境变量: export OPENAI_API_KEY=sk-xxx\n"
                                + "2. 编辑配置文件: ~/.jimi/config.yaml");
                return;
            }

            // 3. 创建服务
            workService = new WorkService(config);

            // 4. 创建主窗口
            MainWindow mainWindow = new MainWindow(primaryStage, workService);
            mainWindow.show();

            log.info("JWork 启动成功");

        } catch (Exception e) {
            log.error("启动失败", e);
            showError("启动失败", e.getMessage());
        }
    }

    @Override
    public void stop() {
        log.info("关闭 Jimi Work 桌面应用");
        if (workService != null) {
            workService.shutdown();
        }
        // 强制退出，防止后台线程阻塞
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                Runtime.getRuntime().halt(0);
            } catch (InterruptedException ignored) {}
        }).start();
    }

    /**
     * 应用系统主题
     */
    private void applySystemTheme() {
        try {
            if (isMacDarkTheme()) {
                Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
            } else {
                Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
            }
        } catch (Exception e) {
            log.warn("主题检测失败，使用 PrimerDark", e);
            Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        }
    }

    /**
     * 检测 macOS 暗色主题
     */
    private boolean isMacDarkTheme() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac")) {
            try {
                Process process = java.lang.Runtime.getRuntime().exec("defaults read -g AppleInterfaceStyle");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    return "Dark".equalsIgnoreCase(line);
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true; // 其他平台默认 Dark
    }

    /**
     * 显示错误对话框
     */
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Jimi Work");
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
        Platform.exit();
    }

    /**
     * 获取工作目录
     */
    private Path getWorkDir() {
        if (workDir != null) {
            return workDir;
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    /**
     * 主入口
     */
    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (("-d".equals(args[i]) || "--dir".equals(args[i])) && i + 1 < args.length) {
                workDir = Paths.get(args[++i]);
            }
        }
        launch(args);
    }
}
