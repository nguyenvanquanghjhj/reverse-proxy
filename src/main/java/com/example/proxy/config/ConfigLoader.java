package com.example.proxy.config;

import com.example.proxy.backend.BackendServer;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;

public final class ConfigLoader {
    private ConfigLoader() { }
    public static ProxyConfig load(String filePath) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(filePath), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) { throw new IllegalArgumentException("Cannot read configuration: " + filePath, e); }
        return load(properties);
    }
    public static ProxyConfig load(Properties properties) {
        var backends = new ArrayList<BackendServer>();
        for (String entry : properties.getProperty("backend.servers", "").split(",", -1)) {
            String[] parts = entry.trim().split(":", -1);
            if (parts.length < 2 || parts.length > 3)
                throw new IllegalArgumentException("Expected backend host:port[:capacity]: " + entry);
            try {
                backends.add(new BackendServer(parts[0].trim(), Integer.parseInt(parts[1].trim()),
                        parts.length == 3 ? Integer.parseInt(parts[2].trim()) : 8));
            } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid backend: " + entry, e); }
        }
        return new ProxyConfig(properties, backends);
    }
}
