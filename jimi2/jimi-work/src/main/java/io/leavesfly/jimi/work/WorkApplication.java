package io.leavesfly.jimi.work;

import io.leavesfly.jimi.work.ui.MainWindow;
import javafx.application.Application;
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
            // 创建主窗口
            MainWindow mainWindow = new MainWindow(primaryStage, getWorkDir());
            mainWindow.show();
            
        } catch (Exception e) {
            log.error("启动失败", e);
            throw new RuntimeException("启动失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void stop() {
        log.info("关闭 Jimi Work 桌面应用");
    }
    
    /**
     * 获取工作目录
     *
     * @return 工作目录路径
     */
    private Path getWorkDir() {
        if (workDir != null) {
            return workDir;
        }
        return Paths.get(System.getProperty("user.dir"));
    }
    
    /**
     * 主入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            if (("-d".equals(args[i]) || "--dir".equals(args[i])) && i + 1 < args.length) {
                workDir = Paths.get(args[++i]);
            }
        }
        
        // 启动 JavaFX 应用
        launch(args);
    }
}
