package io.leavesfly.jimi.tool.core.ask;

import io.leavesfly.jimi.wire.Wire;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * 人工交互服务
 * 管理Agent与用户之间的交互请求/响应生命周期
 * <p>
 * 职责：
 * - 创建和发送人工交互请求
 * - 等待用户响应
 * - 支持多种交互类型（确认、自由输入、选择）
 */
@Slf4j
@Component
public class HumanInteraction {

    @Autowired
    private final Wire wire;

    public HumanInteraction(Wire wire) {
        this.wire = wire;
    }

    /**
     * 请求确认（满意/需要修改/拒绝）
     *
     * @param question 确认问题
     * @return 用户响应的 Mono
     */
    public Mono<HumanInputResponse> requestConfirmation(String question) {
        return request(question, HumanInputRequest.InputType.CONFIRM, null, null);
    }

    /**
     * 请求自由输入
     *
     * @param question     提问内容
     * @param defaultValue 默认值（可选）
     * @return 用户响应的 Mono
     */
    public Mono<HumanInputResponse> requestInput(String question, String defaultValue) {
        return request(question, HumanInputRequest.InputType.FREE_INPUT, null, defaultValue);
    }

    /**
     * 请求选择
     *
     * @param question 提问内容
     * @param choices  选项列表
     * @return 用户响应的 Mono
     */
    public Mono<HumanInputResponse> requestChoice(String question, List<String> choices) {
        return request(question, HumanInputRequest.InputType.CHOICE, choices, null);
    }

    /**
     * 发送交互请求并等待响应
     */
    private Mono<HumanInputResponse> request(String question,
                                             HumanInputRequest.InputType type,
                                             List<String> choices,
                                             String defaultValue) {
        return Mono.create(sink -> {
            String requestId = UUID.randomUUID().toString();

            HumanInputRequest request = HumanInputRequest.builder()
                    .requestId(requestId)
                    .question(question)
                    .inputType(type)
                    .choices(choices)
                    .defaultValue(defaultValue)
                    .responseSink(sink)
                    .build();

            // 通过Wire发送请求
            wire.send(request);

            log.info("Human input request sent: requestId={}, type={}", requestId, type);
        });
    }
}
