package io.leavesfly.jimi.work.ui.component;

import io.leavesfly.jimi.work.model.ApprovalInfo;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * 审批对话框 - 权限审批弹窗
 * 支持三种响应: 允许一次 / 本次会话允许 / 拒绝
 */
public class ApprovalDialog {

    /**
     * 显示审批对话框
     *
     * @param approvalInfo 审批信息
     * @return 用户响应
     */
    public static Optional<ApprovalInfo.Response> show(ApprovalInfo approvalInfo) {
        Dialog<ApprovalInfo.Response> dialog = new Dialog<>();
        dialog.setTitle("权限审批");
        dialog.setHeaderText("操作需要您的确认");

        // 内容
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));

        Label actionLabel = new Label("操作: " + approvalInfo.getAction());
        actionLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label descLabel = new Label(approvalInfo.getDescription());
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(400);
        descLabel.setStyle("-fx-text-fill: #555;");

        Label warningLabel = new Label("⚠️ 此操作可能修改您的文件或系统状态");
        warningLabel.setStyle("-fx-text-fill: #e65100; -fx-font-size: 12px;");

        content.getChildren().addAll(actionLabel, descLabel, new Separator(), warningLabel);
        dialog.getDialogPane().setContent(content);

        // 按钮
        ButtonType approveBtn = new ButtonType("允许一次", ButtonBar.ButtonData.OK_DONE);
        ButtonType approveSessionBtn = new ButtonType("本次会话允许", ButtonBar.ButtonData.APPLY);
        ButtonType rejectBtn = new ButtonType("拒绝", ButtonBar.ButtonData.CANCEL_CLOSE);

        dialog.getDialogPane().getButtonTypes().addAll(approveBtn, approveSessionBtn, rejectBtn);

        // 按钮样式
        Button approveButton = (Button) dialog.getDialogPane().lookupButton(approveBtn);
        approveButton.setStyle("-fx-background-color: #4caf50; -fx-text-fill: white;");

        Button rejectButton = (Button) dialog.getDialogPane().lookupButton(rejectBtn);
        rejectButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white;");

        dialog.setResultConverter(buttonType -> {
            if (buttonType == approveBtn) return ApprovalInfo.Response.APPROVE;
            if (buttonType == approveSessionBtn) return ApprovalInfo.Response.APPROVE_SESSION;
            return ApprovalInfo.Response.REJECT;
        });

        return dialog.showAndWait();
    }
}
