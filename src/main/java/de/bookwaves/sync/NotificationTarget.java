package de.bookwaves.sync;

/**
 * The names of the parameters that tell a reader where to send notifications.
 *
 * <p>The generations keep these in unrelated subtrees and spell the address leaf
 * differently, so all three names are carried rather than built from a shared root.
 */
public record NotificationTarget(
    String addressParameter,
    String portNumberParameter,
    String connectionHoldTimeParameter
) {

    public static final NotificationTarget NEW_GEN = new NotificationTarget(
        "HostInterface.LAN.Remote.Channel1.Address",
        "HostInterface.LAN.Remote.Channel1.PortNumber",
        "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime"
    );

    public static final NotificationTarget OLD_GEN = new NotificationTarget(
        "OperatingMode.NotificationMode.Transmission.Destination.IPv4.IPAddress",
        "OperatingMode.NotificationMode.Transmission.Destination.PortNumber",
        "OperatingMode.NotificationMode.Transmission.Destination.ConnectionHoldTime"
    );

    public NotificationTarget {
        if (addressParameter == null || portNumberParameter == null
            || connectionHoldTimeParameter == null) {
            throw new IllegalArgumentException("A notification target needs all three parameter names");
        }
    }
}
