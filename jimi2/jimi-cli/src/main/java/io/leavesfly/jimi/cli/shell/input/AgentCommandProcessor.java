package io.leavesfly.jimi.cli.shell.input;

import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.cli.shell.ShellContext;
import io.leavesfly.jimi.cli.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 命令输入处理器
 * 处理普通的 Agent 命令（不以 / 或 ! 开头）
 */
@Slf4j
public class AgentCommandProcessor implements InputProcessor {
    
    @Override
    public boolean canProcess(String input) {
        // 默认处理器，处理所有其他输入
        return true;
    }
    
    @Override
    public int getPriority() {
        return 100; // 最低优先级
    }
    
    @Override
    public boolean process(String input, ShellContext context) throws Exception {
        OutputFormatter out = context.getOutputFormatter();
        
        out.printInfo("执行: " + input);
        
        try {
            // 通过 Engine 运行，阻塞等待完成
            ExecutionResult result = context.getEngine().run(input).block();
            
            if (result != null && !result.isSuccess()) {
                out.printError("执行失败: " + result.getError());
            } else {
                out.printSuccess("✓ 完成");
            }
            
        } catch (Exception e) {
            // 处理各种异常
            handleExecutionError(e, out);
        }
        
        return true;
    }
    
    /**
     * 处理执行错误
     */
    private void handleExecutionError(Exception e, OutputFormatter out) {
        log.error("Error executing agent command", e);
        
        String errorMsg = e.getMessage();
        if (errorMsg == null) {
            errorMsg = e.getClass().getSimpleName();
        }
        
        // 根据异常类型给出友好提示
        if (errorMsg.contains("LLMNotSet")) {
            out.printError("LLM 未配置。请设置 KIMI_API_KEY 环境变量。");
            out.printInfo("或在配置文件中配置模型。");
        } else if (errorMsg.contains("MaxStepsReached")) {
            out.printError("达到最大步骤数。任务可能过于复杂。");
            out.printInfo("尝试将其分解为更小的任务。");
        } else if (errorMsg.contains("401")) {
            out.printError("认证失败。请检查您的 API 密钥。");
        } else if (errorMsg.contains("403")) {
            out.printError("配额已用尽。请升级您的套餐或稍后重试。");
        } else {
            out.printError("错误: " + errorMsg);
        }
    }
}
