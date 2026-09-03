package de.bookwaves.sync;

import java.util.List;
import java.util.Locale;

/**
 * Configuration parameters this service must never write.
 *
 * <p>Changing the reader's own network identity can put it beyond reach of the host
 * that would have to change it back, and changing access protection or credentials can
 * lock the reader in a way only the manufacturer can undo. Set these through ISOStart
 * or the reader's web interface.
 *
 * <p>{@code HostInterface.LAN.Remote.Channel1.Address} is not protected: it holds the
 * host's address, not the reader's own.
 */
public final class ProtectedParameters {

    private static final List<String> PROTECTED_PREFIXES = List.of(
        "HostInterface.LAN.IPv4.",
        "HostInterface.LAN.IPv6.",
        "HostInterface.LAN.Hostname.",
        "AccessProtection."
    );

    private static final List<String> PROTECTED_FRAGMENTS = List.of(
        "password",
        "authent",
        "crypto",
        "usermanagement"
    );

    private ProtectedParameters() {
    }

    /** Whether writing this parameter is forbidden. */
    public static boolean isProtected(String parameter) {
        if (parameter == null) {
            return true;
        }
        for (String prefix : PROTECTED_PREFIXES) {
            if (parameter.startsWith(prefix)) {
                return true;
            }
        }
        String lower = parameter.toLowerCase(Locale.ROOT);
        for (String fragment : PROTECTED_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** The message explaining why a write was refused. */
    public static String refusalMessage(String parameter) {
        return "Refusing to write protected parameter " + parameter
            + ": reader network identity and access credentials are not managed by this service";
    }
}
