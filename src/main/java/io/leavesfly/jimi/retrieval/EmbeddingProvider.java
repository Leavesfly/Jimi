package io.leavesfly.jimi.retrieval;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 嵌入向量提供者接口
 * <p>
 * 职责：
 * - 将文本转换为向量表示
 * - 支持批量嵌入以提高性能
 * - 提供向量维度信息
 * <p>
 * 实现可以是：
 * - 本地模型（ONNX、JNI等）
 * - 远程API（OpenAI、通义千问等）
 * - LLM提供商的embedding接口
 */
public interface EmbeddingProvider {

    /**
     * 获取嵌入向量的维度
     *
     * @return 向量维度
     */
    int getDimension();

    /**
     * 将单个文本转换为嵌入向量
     *
     * @param text 输入文本
     * @return 嵌入向量（异步）
     */
    Mono<float[]> embed(String text);

    /**
     * 批量将文本转换为嵌入向量
     * 批量处理可以提高性能，减少API调用次数
     *
     * @param texts 输入文本列表
     * @return 嵌入向量列表（异步）
     */
    Mono<List<float[]>> embedBatch(List<String> texts);

    /**
     * 获取提供者名称（用于日志和调试）
     *
     * @return 提供者名称
     */
    String getProviderName();
}
