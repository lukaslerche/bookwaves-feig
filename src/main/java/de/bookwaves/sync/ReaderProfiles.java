package de.bookwaves.sync;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The reader generations this service can synchronise.
 *
 * <p>A reader whose {@code type} is {@code GENERIC} — the default — has no profile and
 * is left alone: the service talks to it, but does not manage its configuration.
 */
public final class ReaderProfiles {

    /** The default type, meaning "do not manage this reader's configuration". */
    public static final String GENERIC = "GENERIC";

    /** The current UHF generation, for example the MRU400X. */
    public static final ReaderProfile NEW_GEN = new UhfProfile(
        "NewGen",
        "OperatingMode.AutoReadModes",
        List.of("Date", "Antenna", "IDD", "Time"),
        NotificationTarget.NEW_GEN,
        OutputPowerCodec.NEW_GEN,
        true,
        true
    );

    /** The older UHF generation, for example the MRU102, which has no date selector. */
    public static final ReaderProfile OLD_GEN = new UhfProfile(
        "OldGen",
        "OperatingMode.NotificationMode",
        List.of("AntennaNo", "UID", "Time"),
        NotificationTarget.OLD_GEN,
        OutputPowerCodec.OLD_GEN,
        false,
        false
    );

    private static final List<ReaderProfile> ALL = List.of(NEW_GEN, OLD_GEN);

    private ReaderProfiles() {
    }

    /**
     * The profile for a {@code type} value, or empty for {@code GENERIC}.
     *
     * @throws IllegalArgumentException if the type is neither generic nor a known profile
     */
    public static Optional<ReaderProfile> find(String type) {
        if (type == null || type.isBlank() || GENERIC.equalsIgnoreCase(type.trim())) {
            return Optional.empty();
        }
        for (ReaderProfile profile : ALL) {
            if (profile.id().equalsIgnoreCase(type.trim())) {
                return Optional.of(profile);
            }
        }
        throw new IllegalArgumentException(
            "Unknown reader type '" + type.trim() + "'; supported types are " + supportedTypes());
    }

    /** The accepted {@code type} values, for error messages and documentation. */
    public static String supportedTypes() {
        return GENERIC.toLowerCase(Locale.ROOT) + ", "
            + ALL.stream().map(ReaderProfile::id).collect(Collectors.joining(", "));
    }
}
