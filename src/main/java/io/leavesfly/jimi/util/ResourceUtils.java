package io.leavesfly.jimi.util;

import io.leavesfly.jimi.exception.AgentSpecException;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ResourceUtils {
    public static Path resolveFromClasspath(String resourcePath) {
        URL resource = ResourceUtils.class.getClassLoader().getResource(resourcePath);
        try {
            if (resource != null) {
                return Paths.get(resource.toURI());
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException("Resource resolve failed: " + resourcePath, e);
        }
        throw new RuntimeException("Resource not found: " + resourcePath);
    }
}
