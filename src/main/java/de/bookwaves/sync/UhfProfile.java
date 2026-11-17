package de.bookwaves.sync;

import de.bookwaves.ReaderConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Profile for the UHF reader generations.
 *
 * <p>The generations describe the same concepts under different parameter names, encode
 * output power differently and differ in two capabilities, so they are one class
 * configured twice. See {@link ReaderProfiles} for the instances.
 *
 * @param id                        the {@code type} value in {@code config.yaml}
 * @param autoReadRoot              the operating-mode subtree this generation uses
 * @param dataSelectors             the fields transmitted with each notification
 * @param excludedDataSelectors     fields that must stay off for the transmitted ones to work
 * @param notificationTarget        where this generation keeps its notification target
 * @param outputPowerCodec          how this generation encodes antenna output power
 * @param supportsAuthentication    whether the generation has user login
 * @param supportsPersistenceReset  whether the generation exposes persistence reset
 */
public record UhfProfile(
    String id,
    String autoReadRoot,
    List<String> dataSelectors,
    List<String> excludedDataSelectors,
    NotificationTarget notificationTarget,
    OutputPowerCodec outputPowerCodec,
    boolean supportsAuthentication,
    boolean supportsPersistenceReset
) implements ReaderProfile {

    /** {@code OperatingMode.Mode} value selecting host mode. */
    public static final int HOST_MODE = 0x00;
    /** {@code OperatingMode.Mode} value selecting notification mode. */
    public static final int NOTIFICATION_MODE = 0xC0;

    /** How long the reader holds the notification connection open. */
    private static final Duration CONNECTION_HOLD_TIME = Duration.ofSeconds(10);

    /** {@code RSSIFilter} value with which the reader reports every tag it hears. */
    private static final int READER_RSSI_FILTER_OFF = 0;

    /** Clearing this bit makes all antenna ports one reading point. */
    private static final boolean EACH_ANTENNA_PORT_IS_ITS_OWN_READING_POINT = false;

    private static final String MODE = "OperatingMode.Mode";
    private static final String MULTIPLEXER_ENABLE = "AirInterface.Multiplexer.Enable";
    private static final String SELECTED_ANTENNAS = "AirInterface.Multiplexer.UHF.Internal.SelectedAntennas";
    private static final String RSSI_FILTER = "AirInterface.Antenna.UHF.No%d.RSSIFilter";
    private static final String OUTPUT_POWER = "AirInterface.Antenna.UHF.No%d.OutputPower";
    static final String MRU102_PERSISTENCE_RESET_TIME =
        "Transponder.PersistenceReset.Antenna.No1.PersistenceResetTime";
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
        } else if (ReaderMode.HOST.matches(config.getMode())
                && config.getPersistenceResetTime() != null && !config.getPersistenceResetTime().isBlank()) {
            specs.add(new ParamSpec(MRU102_PERSISTENCE_RESET_TIME,
                ParamValue.ofLong(TagTiming.PERSISTENCE_RESET_TIME.steps(config.getPersistenceResetTime())),
                "OldGen persistence reset time (all antennas)"));
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
        // When this service filters, the reader's own filter is switched off; see ADR 0002.
        boolean readerFilters = config.getEffectiveRssiFilterMode() == RssiFilterMode.READER;

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            specs.add(new ParamSpec(
                String.format(RSSI_FILTER, antenna),
                ParamValue.ofByte(readerFilters ? rssiFilters.get(i) : READER_RSSI_FILTER_OFF),
                "RSSI filter for antenna " + antenna));
            specs.add(new ParamSpec(
                String.format(OUTPUT_POWER, antenna),
                ParamValue.ofByte(outputPowerCodec.code(outputPowers.get(i))),
                "output power for antenna " + antenna));
        }
    }

    private void addPersistenceReset(List<ParamSpec> specs, ReaderConfig config) {
        specs.add(new ParamSpec(
            PERSISTENCE_RESET_MODE,
            ParamValue.bool(EACH_ANTENNA_PORT_IS_ITS_OWN_READING_POINT),
            "persistence reset mode"));
        for (int antenna : config.getAntennas()) {
            specs.add(new ParamSpec(
                String.format(PERSISTENCE_RESET_TIME_PARAM, antenna),
                ParamValue.ofLong(TagTiming.PERSISTENCE_RESET_TIME.steps(config.getPersistenceResetTime())),
                "persistence reset time for antenna " + antenna));
        }
    }

    private void addNotificationParameters(List<ParamSpec> specs, ReaderConfig config, String hostName) {
        // The multiplexer is an air interface setting, but a host mode read names the
        // antennas it wants in the inventory command, so only notifications need it.
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

        for (String selector : excludedDataSelectors) {
            specs.add(new ParamSpec(
                autoReadRoot + ".DataSelector." + selector,
                ParamValue.bool(false),
                "do not transmit " + selector + " with each notification"));
        }

        specs.add(new ParamSpec(
            autoReadRoot + ".Filter.TransponderValidTime",
            ParamValue.ofLong(TagTiming.TRANSPONDER_VALID_TIME.steps(config.getTransponderValidTime())),
            "transponder valid time"));
        specs.add(new ParamSpec(
            notificationTarget.connectionHoldTimeParameter(),
            notificationTarget.holdTime().of(CONNECTION_HOLD_TIME),
            "notification connection hold time"));
        specs.add(new ParamSpec(
            notificationTarget.portNumberParameter(),
            ParamValue.ofLong(config.getListenerPort()),
            "port the reader sends notifications to"));

        // Without a usable host name the address has to be set on the reader by hand.
        // Leaving the parameter out keeps it from being reported as drift.
        notificationTarget.address().of(hostName).ifPresent(value -> specs.add(new ParamSpec(
            notificationTarget.addressParameter(),
            value,
            "address the reader sends notifications to")));
    }
}
