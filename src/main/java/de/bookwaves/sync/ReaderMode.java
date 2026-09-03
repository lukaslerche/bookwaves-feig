package de.bookwaves.sync;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How a reader delivers tag reads: the service polls it, or it pushes to the service.
 */
public enum ReaderMode {
    /** The service polls the reader for tags. */
    HOST("host"),
    /** The reader pushes tag reads to the service. */
    NOTIFICATION("notification");

    private final String configValue;

    ReaderMode(String configValue) {
        this.configValue = configValue;
    }

    /** The value written in {@code config.yaml}. */
    public String configValue() {
        return configValue;
    }

    /** Whether {@code mode} names this mode, ignoring case and surrounding space. */
    public boolean matches(String mode) {
        return mode != null && configValue.equalsIgnoreCase(mode.trim());
    }

    /**
     * Parses a {@code mode} value from configuration.
     *
     * @throws IllegalArgumentException naming the supported modes, if unrecognised
     */
    public static ReaderMode parse(String mode) {
        for (ReaderMode candidate : values()) {
            if (candidate.matches(mode)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
            "Unsupported mode " + (mode == null ? "<unset>" : "'" + mode.trim() + "'")
                + "; supported modes are " + supportedValues());
    }

    /** The supported mode values, for error messages. */
    public static String supportedValues() {
        return Arrays.stream(values())
            .map(mode -> mode.configValue.toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(", "));
    }
}
