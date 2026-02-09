package io.leavesfly.jimi.work.ui.component;

import io.leavesfly.jimi.work.model.TodoInfo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 时间线面板 - 展示执行计划 Todo 列表
 */
public class TimelinePane extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(TimelinePane.class);

    private VBox timelineContent;
    private Label summaryLabel;
    private ProgressBar progressBar;

    public TimelinePane() {
        getStyleClass().add("timeline-pane");
        initUI();
    }

    private void initUI() {
        // 顶部摘要
        VBox header = new VBox(8);
        header.setPadding(new Insets(15));
        header.getStyleClass().add("timeline-header");

        Label title = new Label("📋 执行计划");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        summaryLabel = new Label("暂无任务");
        summaryLabel.setStyle("-fx-text-fill: #666;");

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        header.getChildren().addAll(title, summaryLabel, progressBar);
        setTop(header);

        // 时间线内容
        timelineContent = new VBox(4);
        timelineContent.setPadding(new Insets(10));

        ScrollPane scrollPane = new ScrollPane(timelineContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        setCenter(scrollPane);
    }

    /**
     * 更新 Todo 列表
     */
    public void updateTodoList(TodoInfo.TodoList todoList) {
        if (todoList == null) return;

        timelineContent.getChildren().clear();

        int total = todoList.getTotalCount();
        int done = todoList.getDoneCount();

        // 更新摘要
        summaryLabel.setText(String.format("共 %d 项  |  ✅ %d  |  🔄 %d  |  ⏳ %d",
                total, done, todoList.getInProgressCount(), todoList.getPendingCount()));

        // 更新进度条
        if (total > 0) {
            progressBar.setVisible(true);
            progressBar.setProgress((double) done / total);
        }

        // 渲染每个 Todo 项
        List<TodoInfo> todos = todoList.getTodos();
        if (todos != null) {
            for (TodoInfo todo : todos) {
                timelineContent.getChildren().add(createTodoItem(todo));
            }
        }
    }

    /**
     * 创建单个 Todo 项
     */
    private HBox createTodoItem(TodoInfo todo) {
        HBox item = new HBox(10);
        item.setPadding(new Insets(8, 12, 8, 12));
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("timeline-item");

        // 状态图标
        String icon = switch (todo.getStatus()) {
            case DONE -> "✅";
            case IN_PROGRESS -> "🔄";
            case CANCELLED -> "❌";
            case ERROR -> "⚠️";
            default -> "⏳";
        };
        Label iconLabel = new Label(icon);
        iconLabel.setMinWidth(24);

        // 内容
        Label contentLabel = new Label(todo.getContent());
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(contentLabel, Priority.ALWAYS);

        // 状态样式
        String statusStyle = switch (todo.getStatus()) {
            case DONE -> "-fx-background-color: #e8f5e9; -fx-background-radius: 6;";
            case IN_PROGRESS -> "-fx-background-color: #e3f2fd; -fx-background-radius: 6;";
            case ERROR -> "-fx-background-color: #fce4ec; -fx-background-radius: 6;";
            case CANCELLED -> "-fx-background-color: #f5f5f5; -fx-background-radius: 6; -fx-opacity: 0.7;";
            default -> "-fx-background-color: #fff8e1; -fx-background-radius: 6;";
        };
        item.setStyle(statusStyle);

        item.getChildren().addAll(iconLabel, contentLabel);
        return item;
    }

    /**
     * 清空时间线
     */
    public void clear() {
        timelineContent.getChildren().clear();
        summaryLabel.setText("暂无任务");
        progressBar.setVisible(false);
        progressBar.setProgress(0);
    }
}
