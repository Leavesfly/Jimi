package io.leavesfly.jimi.wire.message.request;

import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.wire.message.WireRequest;
import lombok.Getter;

import java.util.List;

/**
 * 运行命令请求
 * <p>
 * Client 通过此请求让 Engine 执行用户输入。
 */
@Getter
public class RunCommandRequest extends WireRequest<Void> {

    private final List<ContentPart> input;

    public RunCommandRequest(List<ContentPart> input) {
        this.input = input;
    }

    @Override
    public String getMessageType() {
        return "request.run_command";
    }
}
