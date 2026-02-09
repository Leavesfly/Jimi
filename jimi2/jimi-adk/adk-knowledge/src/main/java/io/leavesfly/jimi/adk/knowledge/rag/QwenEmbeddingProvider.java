package io.leavesfly.jimi.adk.knowledge.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通义千问 Embedding Provider
 * 调用DashScope Text Embedding API
 * <p>
 * 使用 java.net.http.HttpClient 替代 Spring WebClient（去 Spring 化）
 */
@Slf4j
public class QwenEmbeddingProvider implements EmbeddingProvider {

    private final String embeddingModel;
    private final int dimension;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;

    /**
     * 构造函数
     *
     * @param embeddingModel 嵌入模型名称
     * @param dimension      向量维度
     * @param baseUrl        API 基础 URL
     * @param apiKey         API 密钥
     * @param customHeaders  自定义请求头（可选）
     * @param objectMapper   JSON 序列化器
     */
    public QwenEmbeddingProvider(
            String embeddingModel,
            int dimension,
            String baseUrl,
            String apiKey,
            Map<String, String> customHeaders,
            ObjectMapper objectMapper) {

        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        log.info("Initialized QwenEmbeddingProvider: model={}, dimension={}", embeddingModel, dimension);
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public Mono<float[]> embed(String text) {
        return callEmbeddingAPI(List.of(text))
                .map(vectors -> vectors.get(0));
    }

    @Override
    public Mono<List<float[]>> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return Mono.just(new ArrayList<>());
        }
        
        // 通义千问批量API限制25条，分批处理
        int batchSize = 25;
        if (texts.size() <= batchSize) {
            return callEmbeddingAPI(texts);
        }
        
        // 分批调用
        List<Mono<List<float[]>>> batches = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            batches.add(callEmbeddingAPI(batch));
        }
        
        return Mono.zip(batches, results -> {
            List<float[]> allVectors = new ArrayList<>();
            for (Object result : results) {
                @SuppressWarnings("unchecked")
                List<float[]> batchVectors = (List<float[]>) result;
                allVectors.addAll(batchVectors);
            }
            return allVectors;
        });
    }

    @Override
    public String getProviderName() {
        return "qwen-" + embeddingModel;
    }

    /**
     * 调用通义千问 Embedding API
     */
    private Mono<List<float[]>> callEmbeddingAPI(List<String> texts) {
        return Mono.fromCallable(() -> {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", embeddingModel);
            
            ObjectNode input = objectMapper.createObjectNode();
            ArrayNode textsArray = objectMapper.createArrayNode();
            texts.forEach(textsArray::add);
            input.set("texts", textsArray);
            
            requestBody.set("input", input);
            requestBody.put("encoding_format", "float");

            log.debug("Calling Qwen embedding API for {} texts", texts.size());

            String requestJson = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Qwen embedding API returned status " + 
                        response.statusCode() + ": " + response.body());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            List<float[]> vectors = parseEmbeddingResponse(responseJson);

            log.debug("Successfully embedded {} texts, dimension={}", 
                    vectors.size(), vectors.isEmpty() ? 0 : vectors.get(0).length);

            return vectors;
        }).onErrorResume(e -> {
            log.error("Failed to call Qwen embedding API", e);
            return Mono.error(new RuntimeException("Failed to call embedding API", e));
        });
    }

    /**
     * 解析嵌入响应
     */
    private List<float[]> parseEmbeddingResponse(JsonNode response) {
        List<float[]> vectors = new ArrayList<>();
        
        if (!response.has("output") || !response.get("output").has("embeddings")) {
            throw new RuntimeException("Invalid embedding response format");
        }
        
        JsonNode embeddings = response.get("output").get("embeddings");
        
        for (JsonNode embeddingNode : embeddings) {
            if (!embeddingNode.has("embedding")) {
                continue;
            }
            
            JsonNode embeddingArray = embeddingNode.get("embedding");
            float[] vector = new float[embeddingArray.size()];
            
            for (int i = 0; i < embeddingArray.size(); i++) {
                vector[i] = (float) embeddingArray.get(i).asDouble();
            }
            
            vectors.add(vector);
        }
        
        return vectors;
    }
}
