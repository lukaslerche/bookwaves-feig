package de.bookwaves;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Configurations that must be rejected at load rather than partway through a sync. */
class ConfigLoaderValidationTest {

    private static ReaderConfig managedReader() {
        ReaderConfig config = new ReaderConfig();
        config.setName("reader2");
        config.setAddress("192.168.1.101");
        config.setPort(10001);
        config.setListenerPort(20001);
        config.setMode("notification");
        config.setType("NewGen");
        config.setUsername("u");
        config.setPassword("p");
        config.setAntennas(List.of(1, 2));
        config.setRssiFilters(List.of(60, 65));
        config.setOutputPowers(List.of(0.1, 0.8));
        return config;
    }

    /** The same reader as an older generation, which has no login. */
    private static ReaderConfig oldGenReader() {
        ReaderConfig config = managedReader();
        config.setType("OldGen");
        config.setUsername(null);
        config.setPassword(null);
        return config;
    }

    private static String messageOf(ReaderConfig config) {
        Exception thrown = assertThrows(Exception.class,
            () -> ConfigLoader.validateReaderConfigurations(List.of(config)));
        return thrown.getMessage();
    }

    @Test
    @DisplayName("a well-formed configuration is accepted")
    void validConfigurationPasses() {
        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(managedReader())));
    }

    @Test
    @DisplayName("outputPowers must match the antenna count")
    void outputPowersMustMatchAntennas() {
        ReaderConfig config = managedReader();
        config.setOutputPowers(List.of(0.1));

        assertTrue(messageOf(config).contains("outputPowers"), messageOf(config));
    }

    @Test
    @DisplayName("rssiFilters must match the antenna count")
    void rssiFiltersMustMatchAntennas() {
        ReaderConfig config = managedReader();
        config.setRssiFilters(List.of(60));

        assertTrue(messageOf(config).contains("rssiFilters"), messageOf(config));
    }

    @Test
    @DisplayName("an output power the reader cannot store is rejected with the allowed values")
    void unsupportedOutputPowerIsRejected() {
        ReaderConfig config = managedReader();
        config.setOutputPowers(List.of(0.0, 0.8));

        String message = messageOf(config);
        assertTrue(message.contains("0.0"), message);
        assertTrue(message.contains("supported values"), message);
    }

    @Test
    @DisplayName("an output power is judged against the generation that has to store it")
    void outputPowerIsCheckedPerGeneration() {
        ReaderConfig newGen = managedReader();
        newGen.setOutputPowers(List.of(0.1, 0.8));
        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(newGen)));

        // 0.8 W is beyond the older generation's full power of 0.5 W.
        ReaderConfig oldGen = oldGenReader();
        oldGen.setOutputPowers(List.of(0.1, 0.8));

        String message = messageOf(oldGen);
        assertTrue(message.contains("0.8"), message);
        assertTrue(message.contains("supported values"), message);
    }

    @Test
    @DisplayName("the older generation accepts its own low power step")
    void oldGenAcceptsItsLowestStep() {
        ReaderConfig config = oldGenReader();
        config.setOutputPowers(List.of(0.05, 0.5));

        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(config)));
    }

    @Test
    @DisplayName("an RSSI filter outside the reader's byte range is rejected at load")
    void rssiFilterOutOfRangeIsRejected() {
        ReaderConfig config = managedReader();
        config.setRssiFilters(Arrays.asList(60, 300));

        String message = messageOf(config);
        assertTrue(message.contains("300"), message);
        assertTrue(message.contains("rssiFilter"), message);
    }

    @Test
    @DisplayName("an unrecognised RSSI filter mode is rejected with the supported ones")
    void unknownRssiFilterModeIsRejected() {
        ReaderConfig config = managedReader();
        config.setRssiFilterMode("both");

        String message = messageOf(config);
        assertTrue(message.contains("both"), message);
        assertTrue(message.contains("software, reader"), message);
    }

    /** A reader this service leaves alone, though it still filters its reads. */
    private static ReaderConfig unmanagedReader() {
        ReaderConfig config = new ReaderConfig();
        config.setName("reader3");
        config.setAddress("192.168.1.102");
        config.setPort(10001);
        config.setMode("host");
        return config;
    }

    @Test
    @DisplayName("an unmanaged reader without RSSI filters is accepted")
    void unmanagedReaderNeedsNoFilters() {
        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(unmanagedReader())));
    }

    @Test
    @DisplayName("an unmanaged reader filtered in software needs one RSSI filter per antenna")
    void unmanagedSoftwareFilterMustMatchAntennas() {
        ReaderConfig config = unmanagedReader();
        config.setAntennas(List.of(1, 2));
        config.setRssiFilters(List.of(60));

        assertTrue(messageOf(config).contains("rssiFilters"), messageOf(config));
    }

    @Test
    @DisplayName("an unmanaged software filter rejects invalid antenna entries at load")
    void unmanagedSoftwareFilterValidatesAntennas() {
        for (Integer antenna : Arrays.asList(null, 0, 9)) {
            ReaderConfig config = unmanagedReader();
            config.setAntennas(Arrays.asList(1, antenna));
            config.setRssiFilters(List.of(60, 60));

            assertTrue(messageOf(config).contains("out of range"));
        }
    }

    @Test
    @DisplayName("unmanaged readers retain support for the eight-port antenna mask")
    void unmanagedSoftwareFilterAcceptsEightPorts() {
        ReaderConfig config = unmanagedReader();
        config.setAntennas(List.of(8));
        config.setRssiFilters(List.of(60));

        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(config)));
    }

    @Test
    @DisplayName("a managed reader filtered in software may leave out RSSI filters")
    void managedSoftwareFilterIsOptional() {
        ReaderConfig config = managedReader();
        config.setRssiFilters(List.of());

        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(config)));
    }

    @Test
    @DisplayName("a managed reader that filters on its own still needs one RSSI filter per antenna")
    void managedReaderFilterIsRequired() {
        ReaderConfig config = managedReader();
        config.setRssiFilterMode("reader");
        config.setRssiFilters(List.of());

        assertTrue(messageOf(config).contains("rssiFilters"), messageOf(config));
    }

    @Test
    @DisplayName("RSSI filters nothing acts on are not checked")
    void unusedRssiFiltersAreIgnored() {
        // Unmanaged and filtering on the reader: neither this service nor sync uses them.
        ReaderConfig config = unmanagedReader();
        config.setRssiFilterMode("reader");
        config.setAntennas(List.of(1, 2));
        config.setRssiFilters(List.of(60));

        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(config)));
    }

    @Test
    @DisplayName("an HF reader cannot be given RSSI filters")
    void hfReaderHasNoRssiFilters() {
        ReaderConfig config = unmanagedReader();
        config.setProtocol("hf");
        config.setRssiFilters(List.of(60));

        String message = messageOf(config);
        assertTrue(message.contains("rssiFilters"), message);
        assertTrue(message.contains("hf"), message);
    }

    @Test
    @DisplayName("an unrecognised mode is rejected at load")
    void unknownModeIsRejected() {
        ReaderConfig config = managedReader();
        config.setMode("polling");

        assertTrue(messageOf(config).contains("polling"), messageOf(config));
    }

    @Test
    @DisplayName("an unrecognised type is rejected at load")
    void unknownTypeIsRejected() {
        ReaderConfig config = managedReader();
        config.setType("MRU400");

        assertTrue(messageOf(config).contains("MRU400"), messageOf(config));
    }

    @Test
    @DisplayName("a notification reader without a listener port is rejected")
    void notificationReaderNeedsListenerPort() {
        ReaderConfig config = managedReader();
        config.setListenerPort(null);

        assertTrue(messageOf(config).contains("listenerPort"), messageOf(config));
    }

    @Test
    @DisplayName("a managed reader with no antennas is rejected")
    void managedReaderNeedsAntennas() {
        ReaderConfig config = managedReader();
        config.setAntennas(List.of());
        config.setRssiFilters(List.of());
        config.setOutputPowers(List.of());

        assertTrue(messageOf(config).contains("antenna"), messageOf(config));
    }

    @Test
    @DisplayName("an antenna outside the reader's ports is rejected")
    void antennaRangeIsChecked() {
        ReaderConfig config = managedReader();
        config.setAntennas(Arrays.asList(1, 9));

        assertTrue(messageOf(config).contains("out of range"), messageOf(config));
    }

    @Test
    @DisplayName("a null antenna entry is rejected rather than silently skipped")
    void nullAntennaIsRejected() {
        ReaderConfig config = managedReader();
        List<Integer> withNull = new ArrayList<>();
        withNull.add(1);
        withNull.add(null);
        config.setAntennas(withNull);

        assertTrue(messageOf(config).contains("out of range"), messageOf(config));
    }

    @Test
    @DisplayName("an hf reader must not declare antennas")
    void hfReaderMustNotDeclareAntennas() {
        ReaderConfig config = new ReaderConfig();
        config.setName("reader3");
        config.setMode("notification");
        config.setListenerPort(20002);
        config.setProtocol("hf");
        config.setAntennas(List.of(1));

        assertTrue(messageOf(config).contains("hf"), messageOf(config));
    }

    @Test
    @DisplayName("configuration sync is refused for an hf reader given a uhf type")
    void hfReaderCannotBeManaged() {
        ReaderConfig config = new ReaderConfig();
        config.setName("reader3");
        config.setMode("host");
        config.setProtocol("hf");
        config.setType("NewGen");

        assertTrue(messageOf(config).contains("only available for uhf"), messageOf(config));
    }

    @Test
    @DisplayName("a listenerPort outside the TCP port range is rejected at load")
    void listenerPortRangeIsChecked() {
        ReaderConfig tooHigh = managedReader();
        tooHigh.setListenerPort(70000);
        ReaderConfig zero = managedReader();
        zero.setListenerPort(0);

        assertTrue(messageOf(tooHigh).contains("listenerPort 70000"), messageOf(tooHigh));
        assertTrue(messageOf(zero).contains("listenerPort 0"), messageOf(zero));
    }

    @Test
    @DisplayName("the highest TCP port is a valid listenerPort")
    void highestListenerPortIsAccepted() {
        ReaderConfig config = managedReader();
        config.setListenerPort(65535);

        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(config)));
    }

    @Test
    @DisplayName("the example config's credential placeholder is recognised, real values are not")
    void placeholderCredentialsAreRecognised() {
        assertTrue(ConfigLoader.isPlaceholderCredential("SET_IN_SERVER"));
        assertFalse(ConfigLoader.isPlaceholderCredential("s3cret"));
        assertFalse(ConfigLoader.isPlaceholderCredential(null));
    }

    @Test
    @DisplayName("placeholder credentials warn but still load")
    void placeholderCredentialsStillLoad() {
        ReaderConfig config = managedReader();
        config.setUsername("SET_IN_SERVER");
        config.setPassword("SET_IN_SERVER");

        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(config)));
    }

    @Test
    @DisplayName("a generic reader needs no antenna or power settings")
    void genericReaderIsNotConstrained() {
        ReaderConfig config = new ReaderConfig();
        config.setName("reader1");
        config.setMode("host");

        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(config)));
    }

    @Test
    @DisplayName("duplicate reader names are rejected")
    void duplicateNamesAreRejected() {
        // Readers are held in a map keyed by name; a duplicate would drop one reader.
        Exception thrown = assertThrows(Exception.class,
            () -> ConfigLoader.validateReaderConfigurations(List.of(managedReader(), managedReader())));

        assertTrue(thrown.getMessage().contains("duplicate"), thrown.getMessage());
    }

    @Test
    @DisplayName("a reader without a name is rejected")
    void unnamedReaderIsRejected() {
        ReaderConfig config = managedReader();
        config.setName("  ");

        assertTrue(messageOf(config).contains("name"), messageOf(config));
    }

    @Test
    @DisplayName("tag timings are optional")
    void tagTimingsAreOptional() {
        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(managedReader())));
    }

    @Test
    @DisplayName("a tag timing the reader cannot store is rejected, naming the reader and the key")
    void unstorableTagTimingIsRejected() {
        ReaderConfig config = managedReader();
        config.setTransponderValidTime("150");

        String message = messageOf(config);
        assertTrue(message.contains("reader2"), message);
        assertTrue(message.contains("transponderValidTime"), message);
    }

    @Test
    @DisplayName("a persistence reset time the reader cannot store is rejected")
    void unstorablePersistenceResetTimeIsRejected() {
        ReaderConfig config = managedReader();
        config.setPersistenceResetTime("327675");

        assertTrue(messageOf(config).contains("persistenceResetTime"), messageOf(config));
    }

    @Test
    @DisplayName("tag timings in whole reader steps are accepted, and so is never")
    void storableTagTimingsAreAccepted() {
        ReaderConfig config = managedReader();
        config.setTransponderValidTime("5500");
        config.setPersistenceResetTime("never");

        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(config)));
    }

    @Test
    @DisplayName("an explicit OldGen persistence reset time still loads")
    void persistenceResetTimeOnOldGenLoads() {
        ReaderConfig oldGen = oldGenReader();
        oldGen.setOutputPowers(List.of(0.1, 0.5));
        oldGen.setPersistenceResetTime("2000");

        assertDoesNotThrow(() -> ConfigLoader.validateReaderConfigurations(List.of(oldGen)));
    }
}
