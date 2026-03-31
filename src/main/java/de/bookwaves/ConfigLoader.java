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
    private static Logger log() {
        return LoggerFactory.getLogger(ConfigLoader.class);
    }
    
    public static class Configuration {
        private List<ReaderConfig> readers;
        private Map<String, String> tagPasswords;
        private String defaultTagFormat;
        private String logLevel;
        private Map<String, String> loggers;
        private Boolean corsAnyHost;
        private Boolean tagFileLoggingEnabled;
        private String tagFileLoggingPath;

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

        public Map<String, String> getLoggers() {
            return loggers != null ? loggers : new HashMap<>();
        }

        public void setLoggers(Map<String, String> loggers) {
            this.loggers = loggers;
        }

        public boolean isCorsAnyHost() {
            return corsAnyHost != null ? corsAnyHost : false;
        }

        public void setCorsAnyHost(boolean corsAnyHost) {
            this.corsAnyHost = corsAnyHost;
        }

        public boolean isTagFileLoggingEnabled() {
            return tagFileLoggingEnabled != null ? tagFileLoggingEnabled : true;
        }

        public void setTagFileLoggingEnabled(boolean tagFileLoggingEnabled) {
            this.tagFileLoggingEnabled = tagFileLoggingEnabled;
        }

        public String getTagFileLoggingPath() {
            return (tagFileLoggingPath != null && !tagFileLoggingPath.isBlank())
                ? tagFileLoggingPath
                : "/logs/taggingLog.csv";
        }

        public void setTagFileLoggingPath(String tagFileLoggingPath) {
            this.tagFileLoggingPath = tagFileLoggingPath;
        }
    }

    private static Configuration globalConfig;

    public static Configuration loadConfiguration() throws Exception {
        if (globalConfig != null) {
            return globalConfig;
        }

        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(Configuration.class, loaderOptions);
        Yaml yaml = new Yaml(constructor);
        
        // Require external file path from environment variable
        String externalConfigPath = System.getenv("CONFIG_FILE_PATH");
        if (externalConfigPath == null || externalConfigPath.isEmpty()) {
            throw new Exception("CONFIG_FILE_PATH environment variable is not set. " +
                "Please provide configuration file path via -e CONFIG_FILE_PATH=<path> or volume mount.");
        }
        
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(externalConfigPath);
        } catch (Exception e) {
            throw new Exception("Failed to load configuration file from " + externalConfigPath + ": " + e.getMessage());
        }
        
        try (InputStream stream = inputStream) {
            globalConfig = yaml.load(stream);

            if (globalConfig == null) {
                throw new Exception("Configuration file is empty or invalid YAML");
            }

            return globalConfig;
        }
    }

    public static List<ReaderConfig> loadReaders() throws Exception {
        Configuration configuration = loadConfiguration();

        if (configuration.getReaders() == null || configuration.getReaders().isEmpty()) {
            log().error("No readers defined in configuration");
            throw new Exception("No readers found in configuration file");
        }

        log().info("Loaded configuration with {} readers and {} tag password entries",
            configuration.getReaders().size(), configuration.getTagPasswords().size());
        return configuration.getReaders();
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
     * Get per-logger level overrides (logger name -> level).
     * Example key: "de.bookwaves" or "de.bookwaves.ReaderManager".
     */
    public static Map<String, String> getLoggerLevels() {
        if (globalConfig == null) {
            return new HashMap<>();
        }
        return globalConfig.getLoggers();
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

    /**
     * Whether successful tag initializations should be written to a CSV file.
     * Defaults to true when not specified.
     */
    public static boolean isTagFileLoggingEnabled() {
        if (globalConfig == null) {
            return true;
        }
        return globalConfig.isTagFileLoggingEnabled();
    }

    /**
     * Path to CSV file used for tag initialization logging.
     * Defaults to /logs/taggingLog.csv when not specified.
     */
    public static String getTagFileLoggingPath() {
        if (globalConfig == null) {
            return "/logs/taggingLog.csv";
        }
        return globalConfig.getTagFileLoggingPath();
    }
}
