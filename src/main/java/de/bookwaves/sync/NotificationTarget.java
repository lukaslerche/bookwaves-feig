package de.bookwaves.sync;

import java.time.Duration;
import java.util.Optional;

/**
 * The names of the parameters that tell a reader where to send notifications, and how
 * this generation stores the connection hold time.
 *
 * <p>The generations keep these in unrelated subtrees and spell the address leaf
 * differently, so all three names are carried rather than built from a shared root.
 */
public record NotificationTarget(
    String addressParameter,
    String portNumberParameter,
    String connectionHoldTimeParameter,
    HoldTime holdTime,
    Address address
) {

    public static final NotificationTarget NEW_GEN = new NotificationTarget(
        "HostInterface.LAN.Remote.Channel1.Address",
        "HostInterface.LAN.Remote.Channel1.PortNumber",
        "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime",
        HoldTime.MILLISECONDS,
        Address.TEXT
    );

    public static final NotificationTarget OLD_GEN = new NotificationTarget(
        "OperatingMode.NotificationMode.Transmission.Destination.IPv4.IPAddress",
        "OperatingMode.NotificationMode.Transmission.Destination.PortNumber",
        "OperatingMode.NotificationMode.Transmission.Destination.ConnectionHoldTime",
        HoldTime.SECONDS,
        Address.PACKED_IPV4
    );

    public NotificationTarget {
        if (addressParameter == null || portNumberParameter == null
            || connectionHoldTimeParameter == null || holdTime == null || address == null) {
            throw new IllegalArgumentException("A notification target needs all three names and both encodings");
        }
    }

    /** How a generation stores the address the reader dials back to. */
    public enum Address {
        /** A text field wide enough for a host name. */
        TEXT,
        /** Four bytes of an IPv4 address, most significant first. */
        PACKED_IPV4;

        /**
         * The value this generation stores for {@code hostName}, or empty when it cannot
         * hold that host name — a packed field takes no name needing a DNS lookup. An
         * omitted address is then set on the reader by hand.
         */
        public Optional<ParamValue> of(String hostName) {
            if (hostName == null || hostName.isBlank()) {
                return Optional.empty();
            }
            return switch (this) {
                case TEXT -> Optional.of(ParamValue.text(hostName));
                case PACKED_IPV4 -> packIpv4(hostName).map(ParamValue::ofLong);
            };
        }

        private static Optional<Long> packIpv4(String hostName) {
            String[] octets = hostName.trim().split("\\.");
            if (octets.length != 4) {
                return Optional.empty();
            }
            long packed = 0;
            for (String octet : octets) {
                int value;
                try {
                    value = Integer.parseInt(octet);
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
                if (value < 0 || value > 255) {
                    return Optional.empty();
                }
                packed = (packed << 8) | value;
            }
            return Optional.of(packed);
        }
    }

    /** How a generation stores the connection hold time. */
    public enum HoldTime {
        /** Two bytes counting milliseconds. */
        MILLISECONDS,
        /** One byte counting seconds, so at most 255. */
        SECONDS;

        /** The value this generation stores for a hold time of {@code duration}. */
        public ParamValue of(Duration duration) {
            return switch (this) {
                case MILLISECONDS -> ParamValue.ofLong(duration.toMillis());
                case SECONDS -> ParamValue.ofByte(Math.toIntExact(duration.toSeconds()));
            };
        }
    }
}
