package de.bookwaves.sync;

import de.bookwaves.ReaderConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Profile for the UHF reader generations.
 *
 * <p>The generations describe the same concepts under different parameter names and
 * differ in two capabilities, so they are one class configured twice. See
 * {@link ReaderProfiles} for the instances.
 *
 * @param id                        the {@code type} value in {@code config.yaml}
 * @param autoReadRoot              the operating-mode subtree this generation uses
 * @param dataSelectors             the fields transmitted with each notification
 * @param supportsAuthentication    whether the generation has user login
 * @param supportsPersistenceReset  whether the generation exposes persistence reset
 */
public record UhfProfile(
    String id,
    String autoReadRoot,
    List<String> dataSelectors,
    boolean supportsAuthentication,
    boolean supportsPersistenceReset
) implements ReaderProfile {

    /** {@code OperatingMode.Mode} value selecting host mode. */
    public static final int HOST_MODE = 0x00;
    /** {@code OperatingMode.Mode} value selecting notification mode. */
    public static final int NOTIFICATION_MODE = 0xC0;

    /** Seconds a transponder stays valid before it is reported again. */
    private static final long TRANSPONDER_VALID_TIME = 1;
    /** Seconds the reader holds the notification channel open. */
    private static final long CONNECTION_HOLD_TIME = 10000;
    /** Seconds before tag persistence is reset. */
    private static final long PERSISTENCE_RESET_TIME = 1;
    /** Persistence reset mode where all antenna ports act as one. */
    private static final int PERSISTENCE_RESET_ALL_PORTS_AS_ONE = 0b0;

    private static final String MODE = "OperatingMode.Mode";
    private static final String MULTIPLEXER_ENABLE = "AirInterface.Multiplexer.Enable";
    private static final String SELECTED_ANTENNAS = "AirInterface.Multiplexer.UHF.Internal.SelectedAntennas";
    private static final String RSSI_FILTER = "AirInterface.Antenna.UHF.No%d.RSSIFilter";
    private static final String OUTPUT_POWER = "AirInterface.Antenna.UHF.No%d.OutputPower";
    private static final String CHANNEL_ADDRESS = "HostInterface.LAN.Remote.Channel1.Address";
    private static final String CHANNEL_PORT = "HostInterface.LAN.Remote.Channel1.PortNumber";
    private static final String CONNECTION_HOLD = "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime";
    private static final String PERSISTENCE_RESET_MODE = "Transponder.PersistenceReset.Mode";
    private static final String PERSISTENCE_RESET_TIME_PARAM =
        "Transponder.PersistenceReset.Antenna.No%d.PersistenceResetTime";

    @Override
    public List<ParamSpec> parametersFor(ReaderConfig config, String hostName) {
        List<ParamSpec> specs = new ArrayList<>();
        boolean notification = ReaderMode.NOTIFICATION.matches(config.getMode());

        specs.add(new ParamSpec(
            MODE,
            ParamValue.ofByte(notification ? NOTIFICATION_MODE : HOST_MODE),
            "operating mode"));

        addAntennaParameters(specs, config);

        if (supportsPersistenceReset) {
            addPersistenceReset(specs, config);
        }

        if (notification) {
            addNotificationParameters(specs, config, hostName);
        }

        return List.copyOf(specs);
    }

    /** Air interface settings, which apply in both operating modes. */
    private void addAntennaParameters(List<ParamSpec> specs, ReaderConfig config) {
        List<Integer> antennas = config.getAntennas();
        List<Integer> rssiFilters = config.getRssiFilters();
        List<Double> outputPowers = config.getOutputPowers();

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            specs.add(new ParamSpec(
                String.format(RSSI_FILTER, antenna),
                ParamValue.ofLong(rssiFilters.get(i)),
                "RSSI filter for antenna " + antenna));
            specs.add(new ParamSpec(
                String.format(OUTPUT_POWER, antenna),
                ParamValue.ofByte(OutputPowerCodec.code(outputPowers.get(i))),
                "output power for antenna " + antenna));
        }
    }

    private void addPersistenceReset(List<ParamSpec> specs, ReaderConfig config) {
        specs.add(new ParamSpec(
            PERSISTENCE_RESET_MODE,
            ParamValue.ofByte(PERSISTENCE_RESET_ALL_PORTS_AS_ONE),
            "persistence reset mode"));
        for (int antenna : config.getAntennas()) {
            specs.add(new ParamSpec(
                String.format(PERSISTENCE_RESET_TIME_PARAM, antenna),
                ParamValue.ofLong(PERSISTENCE_RESET_TIME),
                "persistence reset time for antenna " + antenna));
        }
    }

    private void addNotificationParameters(List<ParamSpec> specs, ReaderConfig config, String hostName) {
        specs.add(new ParamSpec(MULTIPLEXER_ENABLE, ParamValue.bool(true), "antenna multiplexer"));
        specs.add(new ParamSpec(
            SELECTED_ANTENNAS,
            ParamValue.ofByte(Byte.toUnsignedInt(config.getAntennaMask())),
            "selected antennas"));

        for (String selector : dataSelectors) {
            specs.add(new ParamSpec(
                autoReadRoot + ".DataSelector." + selector,
                ParamValue.bool(true),
                "transmit " + selector + " with each notification"));
        }

        specs.add(new ParamSpec(
            autoReadRoot + ".Filter.TransponderValidTime",
            ParamValue.ofLong(TRANSPONDER_VALID_TIME),
            "transponder valid time"));
        specs.add(new ParamSpec(
            CONNECTION_HOLD,
            ParamValue.ofLong(CONNECTION_HOLD_TIME),
            "notification connection hold time"));
        specs.add(new ParamSpec(
            CHANNEL_PORT,
            ParamValue.ofLong(config.getListenerPort()),
            "port the reader sends notifications to"));

        // Without a host name the address has to be set on the reader by hand. Leaving
        // the parameter out keeps it from being reported as drift.
        if (hostName != null && !hostName.isBlank()) {
            specs.add(new ParamSpec(
                CHANNEL_ADDRESS,
                ParamValue.text(hostName),
                "address the reader sends notifications to"));
        }
    }
}
