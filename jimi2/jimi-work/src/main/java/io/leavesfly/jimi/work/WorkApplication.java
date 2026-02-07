package io.leavesfly.jimi.work;

import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import io.leavesfly.jimi.work.config.WorkConfig;
import io.leavesfly.jimi.work.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Jimi Work 桌面应用主入口
 * <p>
 * 基于 JavaFX 的桌面 AI 助手应用
 * </p>
 *
 * @author Jimi2 Team
 */
public class WorkApplication extends Application {
    
    private static final Logger log = LoggerFactory.getLogger(WorkApplication.class);
    
    /** 工作目录 */
    private static Path workDir;
    
    @Override
    public void start(Stage primaryStage) {
        log.info("启动 Jimi Work 桌面应用");
        
        try {
            Path dir = getWorkDir();
            
            // 1. 加载配置
            WorkConfig config = WorkConfig.load(dir);
            
            // 2. 创建 LLM
            LLMConfig llmConfig = config.toLLMConfig();
            if (llmConfig.getApiKey() == null || llmConfig.getApiKey().isEmpty()) {
                showError("未配置 API Key",
                        "请通过以下方式之一配置：\n"
                        + "1. 设置环境变量: export OPENAI_API_KEY=sk-xxx\n"
                        + "2. 编辑配置文件: ~/.jimi/config.yaml");
                return;
            }
            
            LLMFactory llmFactory = new LLMFactory();
            LLM llm = llmFactory.create(llmConfig);
            log.info("LLM 已初始化: provider={}, model={}", llmConfig.getProvider(), llmConfig.getModel());
            
            // 3. 创建主窗口
            MainWindow mainWindow = new MainWindow(primaryStage, dir, llm, config);
            mainWindow.show();
            
        } catch (Exception e) {
            log.error("启动失败", e);
            showError("启动失败", e.getMessage());
        }
    }
    
    @Override
    public void stop() {
        log.info("关闭 Jimi Work 桌面应用");
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
