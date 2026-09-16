package io.leavesfly.jimi.core.hook;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HolGuardPluginHookTest {

    @TempDir
    Path tempDir;

    @Test
    void onlyExplicitBenignAllowPasses() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/bash")));
        String script = loadHookScript();
        Path binDir = createGuardFixture();

        assertEquals(0, runHook(script, binDir, "explicit-allow",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "implicit-allow",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "review",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"rm -rf ./build\"}}"));
        assertEquals(2, runHook(script, binDir, "malformed",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "error",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\",\"tool_input\":{\"command\":\"git status\"}}"));
        assertEquals(2, runHook(script, binDir, "explicit-allow",
                "{\"hook_event_name\":\"PRE_TOOL_USE\",\"tool_name\":\"BashTool\"}"));
    }

    @SuppressWarnings("unchecked")
    private String loadHookScript() throws IOException {
        Path hookPath = Path.of("integrations", "hol-guard", "hooks", "hol-guard-pre-tool.yaml");
        assertTrue(Files.exists(hookPath));
        Map<String, Object> root = new Yaml().load(Files.readString(hookPath, StandardCharsets.UTF_8));
        Map<String, Object> execution = (Map<String, Object>) root.get("execution");
        return (String) execution.get("script");
    }

    private Path createGuardFixture() throws IOException {
        Path binDir = Files.createDirectory(tempDir.resolve("bin"));
        Path guard = binDir.resolve("hol-guard");
        Files.writeString(guard, """
                #!/bin/sh
                case \"$HOL_GUARD_FIXTURE\" in
                  explicit-allow)
                    printf '%s\\n' '{\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":true}}'
                    exit 0
                    ;;
                  implicit-allow)
                    printf '%s\\n' '{\"minimum_action\":\"allow\",\"classification\":{\"explicitly_benign\":false}}'
                    exit 0
                    ;;
                  review)
                    printf '%s\\n' '{\"minimum_action\":\"review\",\"classification\":{\"reason\":\"review\"}}'
                    exit 0
                    ;;
                  malformed)
                    printf '%s\\n' 'not-json'
                    exit 0
                    ;;
                  error)
                    printf '%s\\n' 'guard failed' >&2
                    exit 1
                    ;;
                  *)
                    exit 1
                    ;;
                esac
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(guard, PosixFilePermissions.fromString("rwx------"));
        return binDir;
    }

    private int runHook(String script, Path binDir, String fixture, String stdin) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", script);
        processBuilder.directory(tempDir.toFile());
        String existingPath = processBuilder.environment().getOrDefault("PATH", "");
        processBuilder.environment().put("PATH", binDir + ":" + existingPath);
        processBuilder.environment().put("HOL_GUARD_FIXTURE", fixture);

        Process process = processBuilder.start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        boolean finished = process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(finished, "hook fixture timed out");
        process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        return process.exitValue();
    }
}
