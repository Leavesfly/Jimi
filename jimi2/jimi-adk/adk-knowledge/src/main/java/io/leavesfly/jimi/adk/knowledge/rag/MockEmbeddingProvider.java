package io.leavesfly.jimi.adk.knowledge.rag;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟嵌入提供者
 * <p>
 * 仅用于开发和测试，不提供真实的语义向量
 * 使用简单的哈希+随机种子生成固定维度向量
 * <p>
 * 生产环境应替换为：
 * - OpenAI Embedding API
 * - 通义千问 Embedding
 * - 本地ONNX模型
 * - Sentence Transformers
 */
@Slf4j
public class MockEmbeddingProvider implements EmbeddingProvider {

    private final int dimension;
    private final String providerName;

    public MockEmbeddingProvider() {
        this(384, "mock");
    }

    public MockEmbeddingProvider(int dimension, String providerName) {
        this.dimension = dimension;
        this.providerName = providerName;
        log.warn("Using MockEmbeddingProvider - NOT suitable for production. " +
                "Replace with real embedding service for semantic search.");
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public Mono<float[]> embed(String text) {
        return Mono.fromCallable(() -> generateMockVector(text));
    }

    @Override
    public Mono<List<float[]>> embedBatch(List<String> texts) {
        return Mono.fromCallable(() -> {
            List<float[]> embeddings = new ArrayList<>();
            for (String text : texts) {
                embeddings.add(generateMockVector(text));
            }
            return embeddings;
        });
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    /**
     * 生成模拟向量
     * 使用文本哈希作为随机种子，保证相同文本产生相同向量
     */
    private float[] generateMockVector(String text) {
        float[] vector = new float[dimension];
        
        // 使用文本哈希作为随机种子
        long seed = text.hashCode();
        Random random = new Random(seed);
        
        // 生成随机向量
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) (random.nextGaussian() * 0.1);
        }
        
        // 归一化（L2范数）
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        }
        
        return vector;
    }
}
