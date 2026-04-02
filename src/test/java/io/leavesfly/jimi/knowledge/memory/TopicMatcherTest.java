package io.leavesfly.jimi.knowledge.memory;

import io.leavesfly.jimi.memory.MemoryStore;
import io.leavesfly.jimi.memory.TopicMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopicMatcherTest {

    @TempDir
    Path tempDir;

    private MemoryStore memoryStore;
    private TopicMatcher topicMatcher;

    @BeforeEach
    void setUp() throws Exception {
        // Create topics directory
        Path topicsDir = tempDir.resolve("topics");
        Files.createDirectories(topicsDir);

        // Create test topic files
        Files.writeString(topicsDir.resolve("project-structure.md"), "Project structure content");
        Files.writeString(topicsDir.resolve("build-system.md"), "Build system details");
        Files.writeString(topicsDir.resolve("api-design.md"), "API design patterns");
        Files.writeString(topicsDir.resolve("database-schema.md"), "Database schema info");

        // Initialize MemoryStore and TopicMatcher
        // MemoryStore expects (workDirPath, storagePath), use tempDir as both
        memoryStore = new MemoryStore(tempDir.toString(), tempDir.toString());
        topicMatcher = new TopicMatcher(memoryStore);
    }

    @Test
    void match_shouldMatchTopicByKeyword() {
        String userInput = "Tell me about the project structure";
        List<TopicMatcher.MatchedTopic> result = topicMatcher.match(userInput, 10);

        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(t -> t.topicName().equals("project-structure")));
    }

    @Test
    void match_shouldSortTopicsByMatchScore() {
        String userInput = "project and structure and build";
        List<TopicMatcher.MatchedTopic> result = topicMatcher.match(userInput, 10);

        assertFalse(result.isEmpty());
        
        // project-structure should have highest score (matches both "project" and "structure")
        assertEquals("project-structure", result.get(0).topicName());
        assertTrue(result.get(0).score() > result.get(1).score());
    }

    @Test
    void match_shouldRespectMaxTopicsLimit() {
        String userInput = "project structure build api database";
        List<TopicMatcher.MatchedTopic> result = topicMatcher.match(userInput, 2);

        assertTrue(result.size() <= 2);
    }

    @Test
    void match_shouldReturnEmptyListWhenNoMatch() {
        String userInput = "something completely unrelated";
        List<TopicMatcher.MatchedTopic> result = topicMatcher.match(userInput, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void match_shouldReturnEmptyListForEmptyInput() {
        List<TopicMatcher.MatchedTopic> result = topicMatcher.match("", 10);
        assertTrue(result.isEmpty());

        result = topicMatcher.match(null, 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadMatchedTopics_shouldLoadMatchedTopicContent() {
        List<TopicMatcher.MatchedTopic> matched = List.of(
                new TopicMatcher.MatchedTopic("project-structure", 10)
        );

        String result = topicMatcher.loadMatchedTopics(matched);

        assertTrue(result.contains("## Relevant Memory Topics"));
        assertTrue(result.contains("### Project Structure"));
        assertTrue(result.contains("Project structure content"));
    }

    @Test
    void loadMatchedTopics_shouldReturnEmptyStringForEmptyList() {
        String result = topicMatcher.loadMatchedTopics(List.of());
        assertEquals("", result);
    }

    @Test
    void loadMatchedTopics_shouldFormatMultipleTopics() {
        List<TopicMatcher.MatchedTopic> matched = List.of(
                new TopicMatcher.MatchedTopic("project-structure", 10),
                new TopicMatcher.MatchedTopic("build-system", 5)
        );

        String result = topicMatcher.loadMatchedTopics(matched);

        assertTrue(result.contains("### Project Structure"));
        assertTrue(result.contains("### Build System"));
        assertTrue(result.contains("Project structure content"));
        assertTrue(result.contains("Build system details"));
    }
}
