package de.bookwaves;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class to load reader configurations from a YAML file.
 */
public class ConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    
    public static class Configuration {
        private List<ReaderConfig> readers;
        private Map<String, String> tagPasswords;
        private String defaultTagFormat;
        private String logLevel;
        private Boolean corsAnyHost;

        public List<ReaderConfig> getReaders() {
            return readers;
        }

        public void setReaders(List<ReaderConfig> readers) {
            this.readers = readers;
        }

        public Map<String, String> getTagPasswords() {
            return tagPasswords != null ? tagPasswords : new HashMap<>();
        }

        public void setTagPasswords(Map<String, String> tagPasswords) {
            this.tagPasswords = tagPasswords;
        }

        public String getDefaultTagFormat() {
            return defaultTagFormat != null ? defaultTagFormat : "DE290";
        }

        public void setDefaultTagFormat(String defaultTagFormat) {
            this.defaultTagFormat = defaultTagFormat;
        }

        public String getLogLevel() {
            return logLevel != null ? logLevel : "INFO";
        }

        public void setLogLevel(String logLevel) {
            this.logLevel = logLevel;
        }

        public boolean isCorsAnyHost() {
            return corsAnyHost != null ? corsAnyHost : false;
        }

        public void setCorsAnyHost(boolean corsAnyHost) {
            this.corsAnyHost = corsAnyHost;
        }
    }

    private static Configuration globalConfig;

    public static List<ReaderConfig> loadReaders() throws Exception {
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(Configuration.class, loaderOptions);
        Yaml yaml = new Yaml(constructor);
        
        // Require external file path from environment variable
        String externalConfigPath = System.getenv("CONFIG_FILE_PATH");
        if (externalConfigPath == null || externalConfigPath.isEmpty()) {
            log.error("CONFIG_FILE_PATH environment variable is not set");
            throw new Exception("CONFIG_FILE_PATH environment variable is not set. " +
                "Please provide configuration file path via -e CONFIG_FILE_PATH=<path> or volume mount.");
        }
        
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(externalConfigPath);
            log.info("Loading configuration from {}", externalConfigPath);
        } catch (Exception e) {
            log.error("Failed to open configuration file {}: {}", externalConfigPath, e.getMessage());
            throw new Exception("Failed to load configuration file from " + externalConfigPath + ": " + e.getMessage());
        }
        
        try (InputStream stream = inputStream) {
            globalConfig = yaml.load(stream);
            
            if (globalConfig == null || globalConfig.getReaders() == null || globalConfig.getReaders().isEmpty()) {
                log.error("No readers defined in configuration file {}", externalConfigPath);
                throw new Exception("No readers found in configuration file");
            }
            
            log.info("Loaded configuration with {} readers and {} tag password entries", 
                globalConfig.getReaders().size(), globalConfig.getTagPasswords().size());
            return globalConfig.getReaders();
        }
    }

    /**
     * Get the global tag password configuration.
     * Must call loadReaders() first.
     */
    public static Map<String, String> getTagPasswords() {
        if (globalConfig == null) {
            return new HashMap<>();
        }
        return globalConfig.getTagPasswords();
    }

    /**
     * Get the default tag format for initialization.
     * Must call loadReaders() first.
     * @return "DE290" or "CD290" (defaults to "DE290")
     */
    public static String getDefaultTagFormat() {
        if (globalConfig == null) {
            return "DE290";
        }
        return globalConfig.getDefaultTagFormat();
    }

    /**
     * Get the configured log level for the application.
     * Defaults to INFO when not specified.
     */
    public static String getLogLevel() {
        if (globalConfig == null) {
            return "INFO";
        }
        return globalConfig.getLogLevel();
    }

    /**
     * Whether to allow any host for CORS.
     * Defaults to false when not specified.
     */
    public static boolean isCorsAnyHost() {
        if (globalConfig == null) {
            return false;
        }
        return globalConfig.isCorsAnyHost();
    }
}
