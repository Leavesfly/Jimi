package io.leavesfly.jimi.ui.shell.output;

import io.leavesfly.jimi.config.info.ShellUIConfig;
import lombok.extern.slf4j.Slf4j;
import org.jline.terminal.Terminal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 旋转动画管理器
 * 负责终端加载动画的启动、运行和停止
 */
@Slf4j
public class SpinnerManager {

    private final Terminal terminal;
    private final ShellUIConfig uiConfig;

    private Thread spinnerThread;
    private final AtomicBoolean showSpinner = new AtomicBoolean(false);
    private final AtomicReference<String> spinnerMessage = new AtomicReference<>("");

    public SpinnerManager(Terminal terminal, ShellUIConfig uiConfig) {
        this.terminal = terminal;
        this.uiConfig = uiConfig;
    }

    /**
     * 启动旋转动画
     */
    public void start(String message) {
        if (spinnerThread != null && spinnerThread.isAlive()) {
            return;
        }

        showSpinner.set(true);
        spinnerMessage.set(message);

        spinnerThread = new Thread(() -> {
            String[] frames = getFrames();
            int i = 0;
            try {
                terminal.writer().println();
                while (showSpinner.get()) {
                    terminal.writer().print("\r" + frames[i % frames.length] + " " + spinnerMessage.get() + "   ");
                    terminal.flush();
                    i++;
                    Thread.sleep(uiConfig.getSpinnerIntervalMs());
                }
                terminal.writer().print("\r" + " ".repeat(50) + "\r");
                terminal.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        spinnerThread.setDaemon(true);
        spinnerThread.start();
    }

    /**
     * 停止旋转动画
     */
    public void stop() {
        showSpinner.set(false);
        if (spinnerThread != null) {
            try {
                spinnerThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            spinnerThread = null;
        }
    }

    private String[] getFrames() {
        return switch (uiConfig.getSpinnerType()) {
            case "arrows" -> new String[]{"←", "↖", "↑", "↗", "→", "↘", "↓", "↙"};
            case "circles" -> new String[]{"◐", "◓", "◑", "◒"};
            default -> new String[]{"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        };
    }
}
