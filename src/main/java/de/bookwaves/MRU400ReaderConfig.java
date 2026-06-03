package de.bookwaves;

import de.bookwaves.ReaderManager.ReaderOperationException;

// import de.feig.fedm.Config;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.Connector;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.ReaderStatus;
import de.feig.fedm.types.BoolRef;
import de.feig.fedm.types.ByteRef;
import de.feig.fedm.types.LongRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;

import java.lang.StringBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for FEIG MRU400/MRU400X/MRMU400 readers.
 * Extends ReaderConfig with MRU400/MRU400X/MRMU400-specific settings synchronisation between yaml configuration and the readers.
 */
public class MRU400ReaderConfig extends ReaderConfig {

    boolean configurationLoaded = false;
    boolean configurationPersistent = false;
    String hostName = "";
    private static final Logger log = LoggerFactory.getLogger(MRU400ReaderConfig.class);

    private static final String MODE_PARAMETER = "OperatingMode.Mode";
    private static final String MULTIPLEXER_ACTIVATION_PARAMETER = "AirInterface.Multiplexer.Enable";
    private static final String PORT_NUMBER_PARAMETER = "HostInterface.LAN.Remote.Channel1.PortNumber";
    private static final String SELECTED_ANTENNAS_PARAMETER = "AirInterface.Multiplexer.UHF.Internal.SelectedAntennas";
    private static final String RSSI_FILTER_ANTENNA_TEMPLATE_PARAMETER = "AirInterface.Antenna.UHF.No%d.RSSIFilter";
    private static final String OUTPUT_POWER_ANTENNA_TEMPLATE_PARAMETER = "AirInterface.Antenna.UHF.No%d.OutputPower";
    private static final String DATA_SELECTOR_TEMPLATE_PARAMETER = "OperatingMode.AutoReadModes.DataSelector.%s";
    private static final String TRANSPONDER_VALID_TIME_PARAMETER = "OperatingMode.AutoReadModes.Filter.TransponderValidTime";
    private static final String CHANNEL_ADDRESS_PARAMETER = "HostInterface.LAN.Remote.Channel1.Address";
    private static final String CONNECTION_HOLD_TIME_PARAMETER = "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime";
    private static final String PERSISTENCE_RESET_MODE_PARAMETER = "Transponder.PersistenceReset.Mode";
    private static final String PERSISTENCE_RESET_TIME_TEMPLATE_PARAMETER = "Transponder.PersistenceReset.Antenna.No%d.PersistenceResetTime";

    private static final int PERSISTENCE_RESET_TIME = 1;
    private static final int TRANSPONDER_VALID_TIME = 1;
    private static final int CONNECTION_HOLD_TIME = 10000;
    private static final boolean ALL_ANTENNA_PORTS_ACT_AS_ONE = true;

    private static final List<String> DATA_SELECTORS = List.of("Date", "Antenna", "IDD", "Time");

    private static final Map<Boolean, Integer> PERSISTENCE_RESET_MODE_TO_BINARY =
        Map.ofEntries(
            entry(true, 0b0),
            entry(false, 0b1)
        );
    private static final Map<Double, Byte> OUTPUT_POWER_TO_HEX =
        Map.ofEntries(
            entry(0.1, (byte) 0x10),
            entry(0.2, (byte) 0x11),
            entry(0.3, (byte) 0x12),
            entry(0.4, (byte) 0x13),
            entry(0.5, (byte) 0x14),
            entry(0.6, (byte) 0x15),
            entry(0.7, (byte) 0x16),
            entry(0.8, (byte) 0x17),
            entry(0.9, (byte) 0x18),
            entry(1.0, (byte) 0x19)
        );
    private static final Map<String, Integer> MODE_TO_HEX =
        Map.ofEntries(
            entry("host", 0x00),
            entry("notification", 0xC0)
        );
    private static final Map<Integer, List<Integer>> SELECTED_ANTENNAS_TO_LIST =
        Map.ofEntries(
            entry(0x01, List.of(1)),
            entry(0x10, List.of(2)),
            entry(0x11, List.of(1, 2))
        );

    public MRU400ReaderConfig() {
        setType(ReaderType.MRU400);
    }

    @Override
    public ReaderType getType() {
        return ReaderType.MRU400;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getHostName() {
        return hostName;
    }

    public void setConfigurationPersistent(boolean configurationPersistent) {
        this.configurationPersistent = configurationPersistent;
    }

    public boolean isConfigurationPersistent() {
        return configurationPersistent;
    }

    public int checkReturnCode(int state, boolean change) throws ReaderOperationException {
        if (change) {
            if (state == 0) {
                log.info("Configuration of reader {} unchanged.", getName());
            } else if (state == 1) {
                return ErrorCode.Ok;
            }
            if (state < ErrorCode.Ok) {
                throw new ReaderOperationException(
                    "Failed to change configuration of reader " + getName() + " with " + 
                    ErrorCode.toString(state) + " (Error code: " + state + ")"
                );
            } else if (state > ErrorCode.Ok) {
                throw new ReaderOperationException(
                    "Failed to change configuration of reader " + getName() + " with " + 
                    ReaderStatus.toString(state)  + " (Error code: + " + state + ")"
                );
            }
        } else {
            if (state < ErrorCode.Ok) {
                throw new ReaderOperationException(
                    "Failed to read configuration of reader " + getName() +
                    "' (error code: " + state + ")"
                );
            } else if (state > ErrorCode.Ok) {
                throw new ReaderOperationException(
                    "Failed to read configuration of reader " + getName() +
                    "' (Reader code: " + state + ")"
                );
            }
        }
        return state;
    }

    public ConfigurationState checkReaderMode(ReaderModule readerModule) throws ReaderOperationException {
        ByteRef currentMode = new ByteRef ((byte) 0x00);
        int state = readerModule.config().getConfigPara(MODE_PARAMETER, currentMode);
        state = checkReturnCode(state, false);
        if (!MODE_TO_HEX.get(getMode()).equals(currentMode.getValue())) return ConfigurationState.MISCONFIGURED;
        return ConfigurationState.CONFIGURED;
    }

    public ConfigurationState checkRSSIFilters(ReaderModule readerModule) throws ReaderOperationException {
        List<Integer> antennas = getAntennas();

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            LongRef configuredRssiFilter = new LongRef(getRssiFilters().get(i));
            LongRef currentRssiFilter = new LongRef();
            String param = String.format(RSSI_FILTER_ANTENNA_TEMPLATE_PARAMETER, antenna);
            log.debug("Reader {}: Checking parameter {}", getName(), param);
            int state = readerModule.config().getConfigPara(param, currentRssiFilter);
            state = checkReturnCode(state, false);
            if (currentRssiFilter.getValue() != configuredRssiFilter.getValue()) return ConfigurationState.MISCONFIGURED;
        }
        
        return ConfigurationState.CONFIGURED;
    }

    public ConfigurationState checkOutputPowers(ReaderModule readerModule) throws ReaderOperationException {
        List<Integer> antennas = getAntennas();

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            ByteRef configuredOutputPower = new ByteRef((byte) OUTPUT_POWER_TO_HEX.get(getOutputPowers().get(i)));
            ByteRef currentOutputPower = new ByteRef(); 
            String param = String.format(OUTPUT_POWER_ANTENNA_TEMPLATE_PARAMETER, antenna);
            int state = readerModule.config().getConfigPara(param, currentOutputPower);
            state = checkReturnCode(state, false);
            if (currentOutputPower.getValue() != configuredOutputPower.getValue()) return ConfigurationState.MISCONFIGURED;
        }
        
        return ConfigurationState.CONFIGURED;
    }

    @Override
    public synchronized ConfigurationState checkConfig(ReaderModule readerModule) throws ReaderOperationException { 
        log.info("Reader {}: Validating the configuration", getName());
        int state = readerModule.config().readCompleteConfiguration();
        state = checkReturnCode(state, false);
        this.configurationLoaded = true;

        ConfigurationState configState = checkReaderMode(readerModule);
        if (configState == ConfigurationState.MISCONFIGURED) return configState;

        log.info("Check RSSI filters");

        configState = checkRSSIFilters(readerModule);
        if (configState == ConfigurationState.MISCONFIGURED) return configState;
        
        configState = checkOutputPowers(readerModule);
        if (configState == ConfigurationState.MISCONFIGURED) return configState;

        return ConfigurationState.CONFIGURED;
    }

    public synchronized int resetCompleteReaderConfiguration(ReaderModule readerModule) throws ReaderOperationException {
        int state = readerModule.config().resetCompleteConfiguration();
        state = checkReturnCode(state, false);
        return state;
    }

    @Override
    public synchronized int applyConfig(ReaderModule readerModule) throws ReaderOperationException {
        log.info("Reader {}: configuring MRU400 reader.", getName());
        int state = ErrorCode.Ok;

        if (!this.configurationLoaded) {
            state = readerModule.config().readCompleteConfiguration();
            state = checkReturnCode(state, false);
        }

        state = setReaderMode(readerModule);
        if (state != ErrorCode.Ok) return state;

        state = setReaderRSSIFilters(readerModule);
        if (state != ErrorCode.Ok) return state;

        state = setReaderOutputPowers(readerModule);
        if (state != ErrorCode.Ok) return state;

        state = readerModule.config().applyConfiguration(isConfigurationPersistent());
        state = checkReturnCode(state, true);
        if (state != ErrorCode.Ok) return state;

        return ErrorCode.Ok;
    }
    
    private int setChannelAddress(ReaderModule readerModule) throws ReaderOperationException {
        if (hostName == null || hostName.isBlank()) {
            log.warn("Host name not set, parameter {} in reader {} must be set manually.", CHANNEL_ADDRESS_PARAMETER, getName());
            return ErrorCode.Ok;
        }
        log.info("Reader {}: setting parameter {} to {}", getName(), CHANNEL_ADDRESS_PARAMETER, hostName);
        int state = readerModule.config().changeConfigPara(CHANNEL_ADDRESS_PARAMETER, hostName);
        state = checkReturnCode(state, true);

        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set channel address (error {})", getName(), state);
            return state;
        }

        return ErrorCode.Ok;
    }
    
    private int setConnectionHoldTime(ReaderModule readerModule) throws ReaderOperationException {
        log.info("Reader {}: setting parameter {} to {}", getName(), CONNECTION_HOLD_TIME_PARAMETER, CONNECTION_HOLD_TIME);
        int state = readerModule.config().changeConfigPara(CONNECTION_HOLD_TIME_PARAMETER, CONNECTION_HOLD_TIME);
        state = checkReturnCode(state, true);

        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set connection hold time (error {})", getName(), state);
            return state;
        }

        return ErrorCode.Ok;
    }

    private int setTransponderValidTime(ReaderModule readerModule) throws ReaderOperationException {
        log.info("Reader {}: setting parameter {} to {}", getName(), TRANSPONDER_VALID_TIME_PARAMETER, TRANSPONDER_VALID_TIME);
        int state = readerModule.config().changeConfigPara(TRANSPONDER_VALID_TIME_PARAMETER, TRANSPONDER_VALID_TIME);
        state = checkReturnCode(state, true);

        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set transponder valid time (error {})", getName(), state);
            return state;
        }

        return ErrorCode.Ok;
    }

    private int activateDataSelector(ReaderModule readerModule, String dataSelector) throws ReaderOperationException {
        String param = String.format(DATA_SELECTOR_TEMPLATE_PARAMETER, dataSelector);
        log.info("Reader {}: activating data selector {} to {}", getName(), param, 0x1);
        int state = readerModule.config().changeConfigPara(param, 0x1);
        state = checkReturnCode(state, true);
        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set transmitted field {} (error {})", getName(), param, state);
            return state;
        }
        return ErrorCode.Ok;
    }

    private int setTransmittedFields(ReaderModule readerModule) throws ReaderOperationException {
        for (String dataSelector: DATA_SELECTORS) activateDataSelector(readerModule, dataSelector);
        return ErrorCode.Ok;
    }

    private int setSelectedAntennas(ReaderModule readerModule) throws ReaderOperationException {
        byte value = getAntennaMask();
        log.info("Reader {}: setting parameter {} to {}", getName(), SELECTED_ANTENNAS_PARAMETER, value);

        int state = readerModule.config().changeConfigPara(SELECTED_ANTENNAS_PARAMETER, value);
        state = checkReturnCode(state, true);
        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set to select antennas (error {})", getName(), state);
            return state;
        }

        return ErrorCode.Ok;
    }

    private int enableMultiplexer(ReaderModule readerModule) throws ReaderOperationException {
        log.info("Reader {}: enabling parameter {} to {}", getName(), MULTIPLEXER_ACTIVATION_PARAMETER, true);
        int state = readerModule.config().changeConfigPara(MULTIPLEXER_ACTIVATION_PARAMETER, true);
        state = checkReturnCode(state, true);
        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to enable multiplexer (error {})", getName(), state);
            return state;
        }
        return ErrorCode.Ok;
    }

    private int setPersistenceResetTime(ReaderModule readerModule) throws ReaderOperationException {
        int state = readerModule.config().changeConfigPara(
            PERSISTENCE_RESET_MODE_PARAMETER,
            PERSISTENCE_RESET_MODE_TO_BINARY.get(ALL_ANTENNA_PORTS_ACT_AS_ONE)
        );
        state = checkReturnCode(state, true);
        if (state != ErrorCode.Ok) {
            return state;
        }
        String param;
        state = ErrorCode.Ok;
        for (int antenna: getAntennas()) {
            param = String.format(PERSISTENCE_RESET_TIME_TEMPLATE_PARAMETER, antenna);
            log.info("{}", param);
            state = readerModule.config().changeConfigPara(param, PERSISTENCE_RESET_TIME);
            state = checkReturnCode(state, true);
        }
        log.info("Reader {}: setting optimal persistence reset time", getName());
        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set optimal persistence reset time.", getName());
            return state;
        }
        return ErrorCode.Ok;
    }

    private int setReaderMode(ReaderModule readerModule) throws ReaderOperationException {
        StringBuilder currentMode = new StringBuilder();
        log.info("Reader {}: setting operating mode to {} mode", getName(), getMode());

        return switch (getMode()) {
            case "host"         -> {
                int state = readerModule.config().setConfigPara(MODE_PARAMETER, MODE_TO_HEX.get("host"));
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                state = setPersistenceResetTime(readerModule);
                if (state != ErrorCode.Ok) yield state;
                yield ErrorCode.Ok;
            }
            case "notification" -> {
                int state = readerModule.config().changeConfigPara(MODE_PARAMETER, MODE_TO_HEX.get("notification"));
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                state = enableMultiplexer(readerModule);
                if (state != ErrorCode.Ok) yield state;
                state = setSelectedAntennas(readerModule);
                if (state != ErrorCode.Ok) yield state;
                state = setTransmittedFields(readerModule);
                if (state != ErrorCode.Ok) yield state;
                state = setConnectionHoldTime(readerModule);
                if (state != ErrorCode.Ok) yield state;
                state = setTransponderValidTime(readerModule);
                if (state != ErrorCode.Ok) yield state;
                state = setChannelAddress(readerModule);
                if (state != ErrorCode.Ok) yield state;
                state = setChannelPortNumber(readerModule);
                if (state != ErrorCode.Ok) yield state;
                yield ErrorCode.Ok;
            }
            default -> {
                log.error("Reader {} has unexpected mode {}", getName(), getMode());
                yield -1;
            }
        };
    }

    private int setReaderRSSIFilters(ReaderModule readerModule) throws ReaderOperationException{
        List<Integer> antennas = getAntennas();
        List<Integer> rssiFilters = getRssiFilters();
        log.info("Reader {}: setting configured RSSI filters", getName());

        if (antennas.size() != rssiFilters.size()) {
            log.error("Reader {}: antennas ({}) and rssiFilters ({}) must be the same length", getName(), antennas.size(), rssiFilters);
            return -1;
        }

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            int rssiVal = rssiFilters.get(i);

            if (antenna < 1 || antenna > 4) {
                log.warn("Reader {}: ignoring invalid antenna index {}", getName(), antenna);
                continue;
            }

            String param = String.format(RSSI_FILTER_ANTENNA_TEMPLATE_PARAMETER, antenna);
            log.info("Reader {}: setting parameter {} to {}", getName(), param, rssiVal);
            int state = readerModule.config().changeConfigPara(param, rssiVal);
            state = checkReturnCode(state, true);
            if (state != ErrorCode.Ok) {
                log.error("Reader {}: failed to set RSSI for antenna {} (error {})",
                    getName(), antenna, state);
                return state;
            }
        }

        return ErrorCode.Ok;
    }

    private int setReaderOutputPowers(ReaderModule readerModule) throws ReaderOperationException {
        List<Integer> antennas = getAntennas();
        List<Double> outputPowers = getOutputPowers();
        log.info("Reader {}: setting configured output powers of the antennas", getName());

        if (antennas.size() != outputPowers.size()) {
            log.error("Reader {}: antennas ({}) and outputPowers ({}) must be the same length",
                getName(), antennas.size(), outputPowers.size());
            return -1;
        }

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            double configuredOutputPowerValue = outputPowers.get(i);

            if (!OUTPUT_POWER_TO_HEX.containsKey(configuredOutputPowerValue)) {
                log.error("Reader {}: Output power value {} not possible, possible values are {}",
                    getName(), configuredOutputPowerValue, OUTPUT_POWER_TO_HEX.keySet()
                );
            }

            if (antenna < 1 || antenna > 4) {
                log.warn("Reader {}: ignoring invalid antenna index {}", getName(), antenna);
                continue;
            }

            int outputPowerHEXValue = OUTPUT_POWER_TO_HEX.get(configuredOutputPowerValue);

            String param = String.format(OUTPUT_POWER_ANTENNA_TEMPLATE_PARAMETER, antenna);
            log.info("Reader {}: setting parameter {} to {}", getName(), param, configuredOutputPowerValue);

            int state = readerModule.config().changeConfigPara(param, (byte) outputPowerHEXValue);
            state = checkReturnCode(state, true);
            if (state != ErrorCode.Ok) {
                log.error("Reader {}: failed to set output power for antenna 0x{} to {} (error {})",
                    getName(), antenna, String.format("%02X", outputPowerHEXValue), state);
                return state;
            }
        }

        return ErrorCode.Ok;
    }

    private int setChannelPortNumber(ReaderModule readerModule) throws ReaderOperationException {
        log.info("Reader {}: setting parameter {} to {}", getName(), PORT_NUMBER_PARAMETER, getListenerPort());
        return switch (getMode()) {
            case "host"         -> {
                log.error("Configuration not expected for 'reader {}' with mode {}", getName(), getMode());
                yield -1;
            }
            case "notification" -> {
                int state = readerModule.config().changeConfigPara(PORT_NUMBER_PARAMETER, (long) getListenerPort());
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                yield ErrorCode.Ok;
            }
            default -> {
                log.error("Reader {} has unexpected mode {}", getName(), getMode());
                yield -1;
            }
        };
    }
}
