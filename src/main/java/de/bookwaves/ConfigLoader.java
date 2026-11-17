package de.bookwaves;

import de.bookwaves.sync.OutputPowerCodec;
import de.bookwaves.sync.ReaderMode;
import de.bookwaves.sync.ReaderProfile;
import de.bookwaves.sync.RssiFilterMode;
import de.bookwaves.sync.TagTiming;
import de.bookwaves.sync.UhfProfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class to load reader configurations from a YAML file.
 */
public class ConfigLoader {
    private static Logger log() {
        return LoggerFactory.getLogger(ConfigLoader.class);
    }

    /** The highest antenna port the UHF readers expose. */
    private static final int MAX_ANTENNA = 4;
    /** The reader stores the RSSI filter in one unsigned byte. */
    private static final int MAX_RSSI_FILTER = 255;

    public static class Configuration {
        private List<ReaderConfig> readers;
        private Map<String, String> tagPasswords;
        private String defaultTagFormat;
        private String logLevel;
        private Map<String, String> loggers;
        private Boolean corsAnyHost;
        private Boolean tagFileLoggingEnabled;
        private String hostName;
        private String tagFileLoggingPath;
        private Boolean readerConfigurationPersistent;

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

        public String getHostName() {
            return (hostName != null && !hostName.isBlank()) ? hostName : null;
        }

        public void setHostName(String hostName) {
            this.hostName = hostName;
        }

        public boolean isReaderConfigurationPersistent() {
            return readerConfigurationPersistent == null || readerConfigurationPersistent;
        }

        public void setReaderConfigurationPersistent(boolean readerConfigurationPersistent) {
            this.readerConfigurationPersistent = readerConfigurationPersistent;
        }
    }

    private static Configuration globalConfig;

    public static Configuration loadConfiguration() throws Exception {
        if (globalConfig != null) {
            return globalConfig;
        }

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
            globalConfig = parse(stream);
            return globalConfig;
        }
    }

    /** Maps YAML onto {@link Configuration}. Package-private so the mapping can be tested. */
    static Configuration parse(InputStream stream) throws Exception {
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(Configuration.class, loaderOptions);
        Configuration parsed = new Yaml(constructor).load(stream);

        if (parsed == null) {
            throw new Exception("Configuration file is empty or invalid YAML");
        }
        return parsed;
    }

    public static List<ReaderConfig> loadReaders() throws Exception {
        Configuration configuration = loadConfiguration();

        if (configuration.getReaders() == null || configuration.getReaders().isEmpty()) {
            log().error("No readers defined in configuration");
            throw new Exception("No readers found in configuration file");
        }

        List<ReaderConfig> readers = configuration.getReaders();
        validateReaderConfigurations(readers);
        warnOnUnwritableHostName(readers, getHostName());

        log().info("Loaded configuration with {} readers and {} tag password entries",
            readers.size(), configuration.getTagPasswords().size());
        return readers;
    }

    /**
     * Names every managed notification reader that will not get {@code hostName} as its
     * notification target. An older generation stores the address as four packed bytes and
     * cannot hold a host name, so the address is left out of its sync rather than written
     * wrongly — which is right, but silent unless reported here.
     */
    static void warnOnUnwritableHostName(List<ReaderConfig> readers, String hostName) {
        if (hostName == null) {
            return;
        }
        for (ReaderConfig reader : readers) {
            if (!ReaderMode.NOTIFICATION.matches(reader.getMode())) {
                continue;
            }
            reader.getProfile()
                .filter(UhfProfile.class::isInstance)
                .map(UhfProfile.class::cast)
                .filter(profile -> profile.notificationTarget().address().of(hostName).isEmpty())
                .ifPresent(profile -> log().warn(
                    "Reader {} is type {}, which stores its notification target as an IPv4 address; "
                        + "hostName '{}' is not one, so the target address must be set on the reader by hand",
                    reader.getName(), profile.id(), hostName));
        }
    }

    /**
     * Rejects configurations that cannot be synchronised, before any reader is contacted,
     * so a bad file fails at load rather than partway through writing to a reader.
     */
    static void validateReaderConfigurations(List<ReaderConfig> readers) throws Exception {
        Set<String> seenNames = new HashSet<>();

        for (ReaderConfig reader : readers) {
            if (reader == null) {
                throw new Exception("Reader configuration entry must not be null");
            }

            String name = (reader.getName() == null || reader.getName().isBlank())
                ? "<unnamed>"
                : reader.getName();

            if ("<unnamed>".equals(name)) {
                throw new Exception("Invalid reader configuration: every reader must have a name");
            }
            if (!seenNames.add(name)) {
                // Readers are held in a map keyed by name; a duplicate would replace the
                // earlier reader rather than being reported.
                throw new Exception("Invalid reader configuration: duplicate reader name '" + name + "'");
            }

            validateReader(reader, name);
        }
    }

    private static void validateReader(ReaderConfig reader, String name) throws Exception {
        ReaderMode mode;
        try {
            mode = ReaderMode.parse(reader.getMode());
        } catch (IllegalArgumentException e) {
            throw new Exception("Invalid reader configuration for '" + name + "': " + e.getMessage());
        }

        try {
            reader.getEffectiveRssiFilterMode();
        } catch (IllegalArgumentException e) {
            throw new Exception("Invalid reader configuration for '" + name + "': " + e.getMessage());
        }

        Optional<ReaderProfile> profile;
        try {
            profile = reader.getProfile();
        } catch (IllegalArgumentException e) {
            throw new Exception("Invalid reader configuration for '" + name + "': " + e.getMessage());
        }

        if (mode == ReaderMode.NOTIFICATION && reader.getListenerPort() == null) {
            throw new Exception("Invalid reader configuration for '" + name
                + "': listenerPort is required in notification mode");
        }

        Integer listenerPort = reader.getListenerPort();
        if (listenerPort != null && (listenerPort < 1 || listenerPort > 65535)) {
            throw new Exception("Invalid reader configuration for '" + name
                + "': listenerPort " + listenerPort + " is out of range 1..65535");
        }

        if (isPlaceholderCredential(reader.getUsername()) || isPlaceholderCredential(reader.getPassword())) {
            log().warn("Reader {} still has the example's placeholder username or password; "
                + "logging in to the reader will fail until the real credentials are set", name);
        }

        if (reader.isHfProtocol() && !reader.getAntennas().isEmpty()) {
            throw new Exception("Invalid reader configuration for '" + name
                + "': antennas must not be configured when protocol is hf");
        }

        if (reader.isHfProtocol() && !reader.getRssiFilters().isEmpty()) {
            throw new Exception("Invalid reader configuration for '" + name
                + "': rssiFilters must not be configured when protocol is hf");
        }

        if (profile.isEmpty()) {
            if (rssiFiltersInUse(reader)) {
                validateAntennas(reader.getAntennas(), name, 8);
                validateRssiFilters(reader, name);
            }
            return;
        }

        if (reader.isHfProtocol()) {
            throw new Exception("Invalid reader configuration for '" + name
                + "': configuration sync is only available for uhf readers");
        }

        validateManagedReader(reader, name, profile.get());
    }

    /** Checks the settings that only matter when this service synchronises the reader. */
    private static void validateManagedReader(ReaderConfig reader, String name, ReaderProfile profile)
            throws Exception {
        List<Integer> antennas = reader.getAntennas();
        List<Double> outputPowers = reader.getOutputPowers();

        if (antennas.isEmpty()) {
            throw new Exception("Invalid reader configuration for '" + name
                + "': at least one antenna must be configured for type " + profile.id());
        }

        validateAntennas(antennas, name, MAX_ANTENNA);

        if (rssiFiltersInUse(reader)) {
            validateRssiFilters(reader, name);
        }
        requireMatchingLength(name, "outputPowers", outputPowers.size(), antennas.size());

        // Maximum power differs per generation.
        OutputPowerCodec codec = profile.outputPowerCodec();
        for (Double power : outputPowers) {
            if (power == null || !codec.isSupported(power)) {
                throw new Exception("Invalid reader configuration for '" + name
                    + "': output power " + power + " is not supported by type " + profile.id()
                    + "; supported values are " + codec.supportedValues());
            }
        }

        requireStorable(name, TagTiming.TRANSPONDER_VALID_TIME, reader.getTransponderValidTime());
        requireStorable(name, TagTiming.PERSISTENCE_RESET_TIME, reader.getPersistenceResetTime());

        if (!profile.supportsPersistenceReset() && !ReaderMode.HOST.matches(reader.getMode())
                && reader.getPersistenceResetTime() != null) {
            log().warn("Reader {}: persistenceResetTime is ignored for type {} outside Host Mode", name, profile.id());
        }

        if (profile.supportsAuthentication() && !reader.hasCredentials()) {
            log().warn("Reader {} is type {} but has no username and password; "
                + "configuration sync will fail if the reader is password protected",
                name, profile.id());
        }
        if (!profile.supportsAuthentication() && reader.hasCredentials()) {
            log().warn("Reader {} is type {}, which has no user login; "
                + "the configured username and password are ignored", name, profile.id());
        }
    }

    /** Whether a reader credential is still the placeholder the example config ships. */
    static boolean isPlaceholderCredential(String value) {
        return "SET_IN_SERVER".equals(value);
    }

    private static void requireStorable(String name, TagTiming timing, String value) throws Exception {
        if (!timing.isSupported(value)) {
            throw new Exception("Invalid reader configuration for '" + name + "': "
                + timing.key() + " " + value + " is not supported; accepted are "
                + timing.supportedValues());
        }
    }

    /**
     * Whether anything acts on this reader's {@code rssiFilters}: this service when it
     * filters in software and some are set, synchronisation when the reader filters and
     * is managed. Unused filters are not checked.
     */
    private static boolean rssiFiltersInUse(ReaderConfig reader) {
        if (reader.getEffectiveRssiFilterMode() == RssiFilterMode.SOFTWARE) {
            return !reader.getRssiFilters().isEmpty();
        }
        return reader.isManaged();
    }

    private static void validateAntennas(List<Integer> antennas, String name, int maximum) throws Exception {
        for (Integer antenna : antennas) {
            if (antenna == null || antenna < 1 || antenna > maximum) {
                throw new Exception("Invalid reader configuration for '" + name
                    + "': antenna " + antenna + " is out of range 1.." + maximum);
            }
        }
    }

    /** Checks that every antenna has one RSSI filter the reader could store. */
    private static void validateRssiFilters(ReaderConfig reader, String name) throws Exception {
        List<Integer> rssiFilters = reader.getRssiFilters();
        requireMatchingLength(name, "rssiFilters", rssiFilters.size(), reader.getAntennas().size());

        for (Integer filter : rssiFilters) {
            if (filter == null || filter < 0 || filter > MAX_RSSI_FILTER) {
                throw new Exception("Invalid reader configuration for '" + name
                    + "': rssiFilter " + filter + " is out of range 0.." + MAX_RSSI_FILTER);
            }
        }
    }

    private static void requireMatchingLength(String name, String key, int actual, int expected)
            throws Exception {
        if (actual != expected) {
            throw new Exception("Invalid reader configuration for '" + name + "': " + key
                + " length (" + actual + ") must match antennas length (" + expected + ")");
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
     * Whether configuration written to a reader is stored in its EEPROM, and so
     * survives a power cycle.
     * Defaults to true when not specified.
     */
    public static boolean isReaderConfigurationPersistent() {
        if (globalConfig == null) {
            return true;
        }
        return globalConfig.isReaderConfigurationPersistent();
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

    /**
     * Address readers dial back to in notification mode.
     * Returns null when not configured, in which case the reader's notification
     * target address has to be set on the reader by hand.
     */
    public static String getHostName() {
        if (globalConfig == null) {
            return null;
        }
        return globalConfig.getHostName();
    }
}
