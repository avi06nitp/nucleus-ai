package com.visa.nucleus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.File;
import java.io.IOException;

/**
 * Loads {@code agent-orchestrator.yaml} and exposes it as a {@link NucleusProperties} bean.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>File path in the {@code NUCLEUS_CONFIG} environment variable.</li>
 *   <li>{@code ./agent-orchestrator.yaml} in the current working directory.</li>
 *   <li>Default {@link NucleusProperties} values if neither file is present.</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(NucleusProperties.class)
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String DEFAULT_CONFIG_FILENAME = "agent-orchestrator.yaml";

    /**
     * Returns a {@link NucleusProperties} bean populated from the external YAML config file.
     * Falls back to default values when the file is absent.
     */
    @Bean
    @Primary
    public NucleusProperties nucleusProperties() {
        File configFile = resolveConfigFile();

        if (configFile != null && configFile.exists()) {
            try {
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                mapper.findAndRegisterModules();
                NucleusProperties props = mapper.readValue(configFile, NucleusProperties.class);
                log.info("Loaded Nucleus config from: {}", configFile.getAbsolutePath());
                return props;
            } catch (IOException e) {
                log.warn("Failed to parse '{}', falling back to defaults: {}", configFile.getAbsolutePath(), e.getMessage());
            }
        } else {
            log.info("No agent-orchestrator.yaml found; using built-in defaults.");
        }

        return new NucleusProperties();
    }

    private File resolveConfigFile() {
        String envPath = System.getenv("NUCLEUS_CONFIG");
        if (envPath != null && !envPath.isBlank()) {
            return new File(envPath);
        }
        return new File(DEFAULT_CONFIG_FILENAME);
    }
}
