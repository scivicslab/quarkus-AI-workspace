package com.scivicslab.serviceportal.config;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * Reads {@code service.portal.port-range} (e.g. "28000-28019") and exposes
 * {@code quarkus.http.port} as the range start so that a single JVM property
 * is sufficient to assign both the dashboard port and the tool port pool.
 *
 * Ordinal 310: overrides application.properties (250) and env vars (300),
 * but yields to explicit -Dquarkus.http.port system properties (400).
 */
public class PortRangeConfigSource implements ConfigSource {

    private final Map<String, String> properties;

    public PortRangeConfigSource() {
        String range = System.getProperty("service.portal.port-range", "").trim();
        Map<String, String> props = Map.of();
        if (!range.isBlank()) {
            try {
                int start = Integer.parseInt(range.split("-")[0].trim());
                props = Map.of("quarkus.http.port", String.valueOf(start));
            } catch (NumberFormatException ignored) {}
        }
        this.properties = props;
    }

    @Override public Map<String, String> getProperties() { return properties; }
    @Override public Set<String> getPropertyNames()      { return properties.keySet(); }
    @Override public String getValue(String key)          { return properties.get(key); }
    @Override public String getName()                     { return "PortRangeConfigSource"; }
    @Override public int    getOrdinal()                  { return 310; }
}
