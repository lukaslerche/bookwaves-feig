package de.bookwaves;

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
    
    public static class Configuration {
        private List<ReaderConfig> readers;
        private Map<String, String> tagPasswords;
        private String defaultTagFormat;

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
    }

    private static Configuration globalConfig;

    public static List<ReaderConfig> loadReaders(String resourcePath) throws Exception {
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
            System.out.println("Loading configuration from: " + externalConfigPath);
        } catch (Exception e) {
            throw new Exception("Failed to load configuration file from " + externalConfigPath + ": " + e.getMessage());
        }
        
        try (InputStream stream = inputStream) {
            globalConfig = yaml.load(stream);
            
            if (globalConfig == null || globalConfig.getReaders() == null || globalConfig.getReaders().isEmpty()) {
                throw new Exception("No readers found in configuration file");
            }
            
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
}
