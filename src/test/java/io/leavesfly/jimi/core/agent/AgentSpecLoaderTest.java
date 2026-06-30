package io.leavesfly.jimi.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentSpecLoader} 的 Claude Code .md 格式解析测试。
 *
 * <p>验证 AgentSpecLoader 能正确解析 YAML frontmatter + Markdown body 格式的 agent 定义，
 * 并兼容现有 agent.yaml 格式。
 */
class AgentSpecLoaderTest {

    private AgentSpecLoader loader;
    private Path helloWorldAgentsDir;

    @BeforeEach
    void setUp() throws Exception {
        loader = new AgentSpecLoader();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ReflectionTestUtils.setField(loader, "yamlObjectMapper", yamlMapper);

        URL url = Objects.requireNonNull(
                getClass().getClassLoader().getResource("plugins/hello-world/agents/md-agent/agent.md"),
                "md-agent fixture missing");
        helloWorldAgentsDir = Paths.get(url.toURI()).getParent().getParent();
    }

    @Test
    @DisplayName(".md 格式: frontmatter 正确解析为 AgentSpec 字段")
    void markdownFrontmatterParsed() throws Exception {
        URL url = Objects.requireNonNull(
                getClass().getClassLoader().getResource("plugins/hello-world/agents/md-agent/agent.md"));
        Path mdFile = Paths.get(url.toURI());

        AgentSpec spec = loader.loadAgentSpec(mdFile).block();

        assertNotNull(spec);
        assertEquals("md-test-agent", spec.getName());
        assertEquals("A test agent in Claude Code markdown format", spec.getDescription());
        assertEquals("test-model", spec.getModel());
        assertEquals(10, spec.getMaxTurns());
    }

    @Test
    @DisplayName(".md 格式: body 内容成为 inlineSystemPrompt")
    void markdownBodyBecomesInlinePrompt() throws Exception {
        URL url = Objects.requireNonNull(
                getClass().getClassLoader().getResource("plugins/hello-world/agents/md-agent/agent.md"));
        Path mdFile = Paths.get(url.toURI());

        AgentSpec spec = loader.loadAgentSpec(mdFile).block();

        assertNotNull(spec);
        assertNotNull(spec.getInlineSystemPrompt());
        assertTrue(spec.getInlineSystemPrompt().contains("You are a test agent defined in Claude Code markdown format"));
        assertTrue(spec.getInlineSystemPrompt().contains("## Test Context"));
    }

    @Test
    @DisplayName(".md 格式: 逗号分隔的 tools 字符串转换为列表")
    void markdownCommaSeparatedToolsParsed() throws Exception {
        URL url = Objects.requireNonNull(
                getClass().getClassLoader().getResource("plugins/hello-world/agents/md-agent/agent.md"));
        Path mdFile = Paths.get(url.toURI());

        AgentSpec spec = loader.loadAgentSpec(mdFile).block();

        assertNotNull(spec);
        List<String> tools = spec.getTools();
        assertNotNull(tools);
        assertEquals(2, tools.size());
        assertTrue(tools.contains("ReadFile"));
        assertTrue(tools.contains("WriteFile"));
    }

    @Test
    @DisplayName(".md 格式: disallowedTools 字段正确解析")
    void markdownDisallowedToolsParsed() throws Exception {
        URL url = Objects.requireNonNull(
                getClass().getClassLoader().getResource("plugins/hello-world/agents/md-agent/agent.md"));
        Path mdFile = Paths.get(url.toURI());

        AgentSpec spec = loader.loadAgentSpec(mdFile).block();

        assertNotNull(spec);
        List<String> disallowed = spec.getDisallowedTools();
        assertNotNull(disallowed);
        assertEquals(1, disallowed.size());
        assertTrue(disallowed.contains("BashTool"));
    }

    @Test
    @DisplayName(".md 格式: 无 frontmatter 的 .md 文件被跳过")
    void markdownWithoutFrontmatterSkipped() throws Exception {
        // 创建一个无 frontmatter 的临时 .md 文件
        Path tempDir = Files.createTempDirectory("jimi-test");
        Path tempMd = tempDir.resolve("not-an-agent.md");
        Files.writeString(tempMd, "This is just a regular markdown file with no frontmatter.\n");

        assertThrows(Exception.class, () -> {
            loader.loadAgentSpec(tempMd).block();
        });

        // 清理
        Files.deleteIfExists(tempMd);
        Files.deleteIfExists(tempDir);
    }

    @Test
    @DisplayName(".yaml 格式: 现有 agent.yaml 仍能正常解析（向后兼容）")
    void yamlFormatStillWorks() throws Exception {
        URL url = Objects.requireNonNull(
                getClass().getClassLoader().getResource("plugins/hello-world/agents/hello-agent/agent.yaml"));
        Path yamlFile = Paths.get(url.toURI());

        AgentSpec spec = loader.loadAgentSpec(yamlFile).block();

        assertNotNull(spec);
        assertEquals("hello-agent", spec.getName());
        assertNotNull(spec.getTools());
        assertTrue(spec.getTools().contains("WriteFile"));
        assertTrue(spec.getTools().contains("ReadFile"));
        // agent.yaml 格式的 inlineSystemPrompt 应为 null
        assertNull(spec.getInlineSystemPrompt());
    }
}
