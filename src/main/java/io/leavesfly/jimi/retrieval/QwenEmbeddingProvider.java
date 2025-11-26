package io.leavesfly.jimi.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jimi.config.LLMProviderConfig;
import io.netty.resolver.DefaultAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 通义千问 Embedding Provider
 * 调用DashScope Text Embedding API
 */
@Slf4j
public class QwenEmbeddingProvider implements EmbeddingProvider {

    private final String embeddingModel;
    private final int dimension;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public QwenEmbeddingProvider(
            String embeddingModel,
            int dimension,
            LLMProviderConfig providerConfig,
            ObjectMapper objectMapper
    ) {
        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE);

        WebClient.Builder builder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(providerConfig.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + providerConfig.getApiKey());

        if (providerConfig.getCustomHeaders() != null) {
            providerConfig.getCustomHeaders().forEach(builder::defaultHeader);
        }

        this.webClient = builder.build();
        
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
        return Mono.defer(() -> {
            try {
                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", embeddingModel);
                
                ObjectNode input = objectMapper.createObjectNode();
                ArrayNode textsArray = objectMapper.createArrayNode();
                texts.forEach(textsArray::add);
                input.set("texts", textsArray);
                
                requestBody.set("input", input);
                
                requestBody.put("encoding_format", "float");

                log.debug("Calling Qwen embedding API for {} texts", texts.size());

                return webClient.post()
                        .uri("/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .map(this::parseEmbeddingResponse)
                        .doOnSuccess(vectors -> 
                            log.debug("Successfully embedded {} texts, dimension={}", 
                                    vectors.size(), vectors.isEmpty() ? 0 : vectors.get(0).length))
                        .onErrorResume(e -> {
                            log.error("Failed to call Qwen embedding API", e);
                            return Mono.error(new RuntimeException("Failed to call embedding API", e));
                        });

            } catch (Exception e) {
                log.error("Failed to build embedding request", e);
                return Mono.error(new RuntimeException("Failed to build embedding request", e));
            }
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
