package io.leavesfly.jimi.work.ui.component;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Skills 管理面板
 * 列出、安装和管理 Skills
 */
public class SkillManagerPane extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(SkillManagerPane.class);

    private VBox skillsList;
    private Label countLabel;

    public SkillManagerPane() {
        getStyleClass().add("skill-manager-pane");
        initUI();
        loadSkills();
    }

    private void initUI() {
        // 顶部标题栏
        VBox header = new VBox(8);
        header.setPadding(new Insets(15));

        Label title = new Label("🧩 Skills 管理");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        countLabel = new Label("加载中...");
        countLabel.setStyle("-fx-text-fill: #666;");

        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> loadSkills());

        HBox titleBar = new HBox(10, title, countLabel, new Region(), refreshBtn);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBar.getChildren().get(2), Priority.ALWAYS);

        header.getChildren().add(titleBar);
        setTop(header);

        // Skills 列表
        skillsList = new VBox(6);
        skillsList.setPadding(new Insets(10));

        ScrollPane scrollPane = new ScrollPane(skillsList);
        scrollPane.setFitToWidth(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        setCenter(scrollPane);
    }

    /**
     * 加载 Skills
     */
    private void loadSkills() {
        skillsList.getChildren().clear();

        List<SkillEntry> skills = new ArrayList<>();

        // 扫描全局 Skills
        Path globalDir = Paths.get(System.getProperty("user.home"), ".jimi", "skills");
        scanSkillsDir(globalDir, "全局", skills);

        // 扫描项目级 Skills
        Path projectDir = Paths.get(System.getProperty("user.dir"), ".jimi", "skills");
        if (Files.exists(projectDir)) {
            scanSkillsDir(projectDir, "项目", skills);
        }

        if (skills.isEmpty()) {
            Label emptyLabel = new Label("暂无已安装的 Skills");
            emptyLabel.setStyle("-fx-text-fill: #888; -fx-padding: 20;");
            skillsList.getChildren().add(emptyLabel);

            Label hintLabel = new Label("Skills 目录: " + globalDir);
            hintLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 11px;");
            skillsList.getChildren().add(hintLabel);
        } else {
            for (SkillEntry skill : skills) {
                skillsList.getChildren().add(createSkillCard(skill));
            }
        }

        countLabel.setText("共 " + skills.size() + " 个 Skills");
    }

    /**
     * 扫描 Skills 目录
     */
    private void scanSkillsDir(Path dir, String scope, List<SkillEntry> skills) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    Path skillFile = entry.resolve("SKILL.md");
                    String name = entry.getFileName().toString();
                    String description = "No description";
                    if (Files.exists(skillFile)) {
                        List<String> lines = Files.readAllLines(skillFile);
                        if (!lines.isEmpty()) {
                            description = lines.get(0).replaceAll("^#\\s*", "");
                        }
                    }
                    skills.add(new SkillEntry(name, description, scope, entry));
                }
            }
        } catch (Exception e) {
            log.warn("扫描 Skills 目录失败: {}", dir, e);
        }
    }

    /**
     * 创建 Skill 卡片
     */
    private HBox createSkillCard(SkillEntry skill) {
        HBox card = new HBox(12);
        card.setPadding(new Insets(10, 14, 10, 14));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8;");

        // 图标
        Label icon = new Label("🧩");
        icon.setStyle("-fx-font-size: 20px;");

        // 信息
        VBox info = new VBox(2);
        Label nameLabel = new Label(skill.name);
        nameLabel.setStyle("-fx-font-weight: bold;");
        Label descLabel = new Label(skill.description);
        descLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
        descLabel.setMaxWidth(300);
        descLabel.setWrapText(true);
        info.getChildren().addAll(nameLabel, descLabel);
        HBox.setHgrow(info, Priority.ALWAYS);

        // 范围标签
        Label scopeLabel = new Label(skill.scope);
        scopeLabel.setStyle("-fx-background-color: #e3f2fd; -fx-text-fill: #1565c0; " +
                "-fx-padding: 2 8; -fx-background-radius: 10; -fx-font-size: 11px;");

        card.getChildren().addAll(icon, info, scopeLabel);
        return card;
    }

    /**
     * Skill 条目
     */
    private record SkillEntry(String name, String description, String scope, Path path) {}
}
