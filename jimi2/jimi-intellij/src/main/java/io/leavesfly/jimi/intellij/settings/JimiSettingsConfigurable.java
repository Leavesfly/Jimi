package io.leavesfly.jimi.intellij.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Jimi 设置页面
 * <p>
 * 在 Settings → Tools → Jimi AI Assistant 下提供配置 UI。
 * </p>
 *
 * @author Jimi2 Team
 */
public class JimiSettingsConfigurable implements Configurable {

    private JPanel mainPanel;
    private JComboBox<String> providerCombo;
    private JTextField modelField;
    private JPasswordField apiKeyField;
    private JTextField baseUrlField;
    private JCheckBox yoloModeCheck;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Jimi AI Assistant";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // Provider
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        mainPanel.add(new JLabel("LLM Provider:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        providerCombo = new JComboBox<>(new String[]{
                "openai", "qwen", "deepseek", "kimi", "ollama", "claude", "glm"
        });
        mainPanel.add(providerCombo, gbc);

        // Model
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        mainPanel.add(new JLabel("Model:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        modelField = new JTextField(30);
        mainPanel.add(modelField, gbc);

        // API Key
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        mainPanel.add(new JLabel("API Key:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        apiKeyField = new JPasswordField(30);
        mainPanel.add(apiKeyField, gbc);

        // hint
        row++;
        gbc.gridx = 1; gbc.gridy = row;
        JLabel hint = new JLabel("也可通过环境变量 OPENAI_API_KEY / {PROVIDER}_API_KEY 设置");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        mainPanel.add(hint, gbc);

        // Base URL
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        mainPanel.add(new JLabel("Base URL (可选):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        baseUrlField = new JTextField(30);
        mainPanel.add(baseUrlField, gbc);

        // YOLO mode
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        yoloModeCheck = new JCheckBox("YOLO 模式 (跳过工具调用确认)");
        mainPanel.add(yoloModeCheck, gbc);

        // filler
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weighty = 1.0;
        mainPanel.add(new JPanel(), gbc);

        // 加载当前值
        reset();

        return mainPanel;
    }

    @Override
    public boolean isModified() {
        JimiSettings settings = JimiSettings.getInstance();
        JimiSettings.State state = settings.getState();
        if (state == null) return false;

        return !state.provider.equals(providerCombo.getSelectedItem())
                || !state.model.equals(modelField.getText().trim())
                || !state.apiKey.equals(new String(apiKeyField.getPassword()))
                || !state.baseUrl.equals(baseUrlField.getText().trim())
                || state.yoloMode != yoloModeCheck.isSelected();
    }

    @Override
    public void apply() throws ConfigurationException {
        JimiSettings settings = JimiSettings.getInstance();
        settings.setProvider((String) providerCombo.getSelectedItem());
        settings.setModel(modelField.getText().trim());
        settings.setApiKey(new String(apiKeyField.getPassword()));
        settings.setBaseUrl(baseUrlField.getText().trim());
        settings.setYoloMode(yoloModeCheck.isSelected());
    }

    @Override
    public void reset() {
        JimiSettings settings = JimiSettings.getInstance();
        JimiSettings.State state = settings.getState();
        if (state == null) return;

        providerCombo.setSelectedItem(state.provider);
        modelField.setText(state.model);
        apiKeyField.setText(state.apiKey);
        baseUrlField.setText(state.baseUrl);
        yoloModeCheck.setSelected(state.yoloMode);
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
    }
}
