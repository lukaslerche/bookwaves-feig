package de.bookwaves.sync;

import de.bookwaves.ReaderConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderProfilesTest {

    private static ReaderConfig reader(String type, String mode) {
        ReaderConfig config = new ReaderConfig();
        config.setName("r");
        config.setType(type);
        config.setMode(mode);
        config.setListenerPort(20001);
        config.setAntennas(List.of(1, 2));
        config.setRssiFilters(List.of(60, 65));
        config.setOutputPowers(List.of(0.1, 0.5));
        return config;
    }

    private static List<String> names(ReaderProfile profile, ReaderConfig config, String hostName) {
        return profile.parametersFor(config, hostName).stream().map(ParamSpec::name).toList();
    }

    @Test
    @DisplayName("the generations use different operating mode parameter trees")
    void generationsUseDifferentTrees() {
        ReaderConfig config = reader("NewGen", "notification");

        assertTrue(names(ReaderProfiles.NEW_GEN, config, "h")
            .contains("OperatingMode.AutoReadModes.DataSelector.IDD"));
        assertTrue(names(ReaderProfiles.OLD_GEN, config, "h")
            .contains("OperatingMode.NotificationMode.DataSelector.UID"));
        assertFalse(names(ReaderProfiles.OLD_GEN, config, "h").stream()
            .anyMatch(name -> name.contains("AutoReadModes")));
    }

    @Test
    @DisplayName("only the current generation has user login")
    void onlyNewGenAuthenticates() {
        assertTrue(ReaderProfiles.NEW_GEN.supportsAuthentication());
        assertFalse(ReaderProfiles.OLD_GEN.supportsAuthentication());
    }

    @Test
    @DisplayName("the older generation does not get persistence reset parameters")
    void oldGenSkipsPersistenceReset() {
        ReaderConfig config = reader("OldGen", "notification");

        assertTrue(names(ReaderProfiles.NEW_GEN, config, "h").stream()
            .anyMatch(name -> name.startsWith("Transponder.PersistenceReset")));
        assertFalse(names(ReaderProfiles.OLD_GEN, config, "h").stream()
            .anyMatch(name -> name.startsWith("Transponder.PersistenceReset")));
    }

    @Test
    @DisplayName("host mode gets no notification channel parameters")
    void hostModeHasNoListenerPort() {
        List<String> hostNames = names(ReaderProfiles.NEW_GEN, reader("NewGen", "host"), "h");

        assertFalse(hostNames.contains("HostInterface.LAN.Remote.Channel1.PortNumber"));
        assertFalse(hostNames.contains("HostInterface.LAN.Remote.Channel1.Address"));
        assertTrue(hostNames.contains("OperatingMode.Mode"));
    }

    @Test
    @DisplayName("host and notification mode ask for different operating mode values")
    void operatingModeDiffersByMode() {
        ParamSpec host = ReaderProfiles.NEW_GEN.parametersFor(reader("NewGen", "host"), "h").get(0);
        ParamSpec notification =
            ReaderProfiles.NEW_GEN.parametersFor(reader("NewGen", "notification"), "h").get(0);

        assertEquals("OperatingMode.Mode", host.name());
        assertEquals(ParamValue.ofByte(0x00), host.desired());
        assertEquals(ParamValue.ofByte(0xC0), notification.desired());
        assertNotEquals(host.desired(), notification.desired());
    }

    @Test
    @DisplayName("antenna parameters are generated only for the configured antennas")
    void onlyConfiguredAntennasAreAddressed() {
        ReaderConfig config = reader("NewGen", "notification");
        config.setAntennas(List.of(2));
        config.setRssiFilters(List.of(70));
        config.setOutputPowers(List.of(0.5));

        List<String> parameters = names(ReaderProfiles.NEW_GEN, config, "h");

        assertTrue(parameters.contains("AirInterface.Antenna.UHF.No2.RSSIFilter"));
        assertFalse(parameters.contains("AirInterface.Antenna.UHF.No1.RSSIFilter"));
    }

    @Test
    @DisplayName("the callback address is omitted when no host name is configured")
    void noHostNameMeansNoAddressParameter() {
        List<String> parameters = names(ReaderProfiles.NEW_GEN, reader("NewGen", "notification"), null);

        assertFalse(parameters.contains("HostInterface.LAN.Remote.Channel1.Address"));
        assertTrue(parameters.contains("HostInterface.LAN.Remote.Channel1.PortNumber"));
    }

    /** A host name both generations can store: the older one needs a literal IPv4. */
    private static final String HOST = "192.168.1.235";

    private static ParamSpec spec(ReaderProfile profile, ReaderConfig config, String name) {
        return profile.parametersFor(config, HOST).stream()
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError(name + " is not in the " + profile.id() + " profile"));
    }

    @Test
    @DisplayName("times are written in the steps the reader counts them in, not in seconds")
    void timesUseReaderSteps() {
        ReaderConfig config = reader("NewGen", "notification");

        // TransponderValidTime counts in 100 ms steps, so one second is 10.
        assertEquals(ParamValue.ofLong(10),
            spec(ReaderProfiles.NEW_GEN, config, "OperatingMode.AutoReadModes.Filter.TransponderValidTime")
                .desired());

        // PersistenceResetTime counts in 5 ms steps, so one second is 200.
        assertEquals(ParamValue.ofLong(200),
            spec(ReaderProfiles.NEW_GEN, config,
                "Transponder.PersistenceReset.Antenna.No1.PersistenceResetTime").desired());

        // ConnectionHoldTime is already in milliseconds.
        assertEquals(ParamValue.ofLong(10000),
            spec(ReaderProfiles.NEW_GEN, config,
                "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime").desired());
    }

    @Test
    @DisplayName("the generations store the connection hold time differently")
    void holdTimeEncodingDiffersByGeneration() {
        ReaderConfig config = reader("NewGen", "notification");

        // Ten seconds: two bytes of milliseconds on one generation, one byte of seconds
        // on the other, where 10000 would not fit.
        assertEquals(ParamValue.ofLong(10000),
            spec(ReaderProfiles.NEW_GEN, config,
                "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime").desired());
        assertEquals(ParamValue.ofByte(10),
            spec(ReaderProfiles.OLD_GEN, config,
                "OperatingMode.NotificationMode.Transmission.Destination.ConnectionHoldTime")
                .desired());
    }

    @Test
    @DisplayName("parameters are written as the type the reader stores them as")
    void parameterTypesMatchTheReader() {
        ReaderConfig config = reader("NewGen", "notification");

        assertEquals(ParamType.BYTE,
            spec(ReaderProfiles.NEW_GEN, config, "AirInterface.Antenna.UHF.No1.RSSIFilter").type());
        assertEquals(ParamType.BOOL,
            spec(ReaderProfiles.NEW_GEN, config, "Transponder.PersistenceReset.Mode").type());
    }

    @Test
    @DisplayName("the generations use different notification target parameters")
    void generationsUseDifferentNotificationTargets() {
        ReaderConfig config = reader("NewGen", "notification");

        List<String> newGen = names(ReaderProfiles.NEW_GEN, config, HOST);
        assertTrue(newGen.contains("HostInterface.LAN.Remote.Channel1.Address"));
        assertTrue(newGen.contains("HostInterface.LAN.Remote.Channel1.PortNumber"));
        assertTrue(newGen.contains("HostInterface.LAN.Remote.Channel1.ConnectionHoldTime"));

        List<String> oldGen = names(ReaderProfiles.OLD_GEN, config, HOST);
        assertTrue(oldGen.contains(
            "OperatingMode.NotificationMode.Transmission.Destination.IPv4.IPAddress"));
        assertTrue(oldGen.contains(
            "OperatingMode.NotificationMode.Transmission.Destination.PortNumber"));
        assertTrue(oldGen.contains(
            "OperatingMode.NotificationMode.Transmission.Destination.ConnectionHoldTime"));
        assertFalse(oldGen.stream().anyMatch(name -> name.startsWith("HostInterface.LAN.Remote")));
    }

    @Test
    @DisplayName("the generations store the notification address differently")
    void addressEncodingDiffersByGeneration() {
        ReaderConfig config = reader("NewGen", "notification");

        // 192.168.1.235 packed most significant byte first.
        assertEquals(ParamValue.ofLong(3232236011L),
            spec(ReaderProfiles.OLD_GEN, config,
                "OperatingMode.NotificationMode.Transmission.Destination.IPv4.IPAddress")
                .desired());
        assertEquals(ParamValue.text("192.168.1.235"),
            spec(ReaderProfiles.NEW_GEN, config,
                "HostInterface.LAN.Remote.Channel1.Address").desired());
    }

    @Test
    @DisplayName("a host name the packed field cannot hold is left off rather than guessed")
    void unpackableHostNameIsOmitted() {
        ReaderConfig config = reader("NewGen", "notification");

        List<String> oldGen = names(ReaderProfiles.OLD_GEN, config, "gate-in.example.org");
        assertFalse(oldGen.contains(
            "OperatingMode.NotificationMode.Transmission.Destination.IPv4.IPAddress"));

        // The generation with a text field takes it happily.
        List<String> newGen = names(ReaderProfiles.NEW_GEN, config, "gate-in.example.org");
        assertTrue(newGen.contains("HostInterface.LAN.Remote.Channel1.Address"));
    }

    @Test
    @DisplayName("the older generation has no date data selector")
    void oldGenHasNoDateSelector() {
        List<String> parameters = names(ReaderProfiles.OLD_GEN, reader("OldGen", "notification"), "h");

        assertFalse(parameters.contains("OperatingMode.NotificationMode.DataSelector.Date"));
        assertTrue(parameters.contains("OperatingMode.NotificationMode.DataSelector.AntennaNo"));
        assertTrue(parameters.contains("OperatingMode.NotificationMode.DataSelector.UID"));
        assertTrue(parameters.contains("OperatingMode.NotificationMode.DataSelector.Time"));
    }

    @Test
    @DisplayName("each generation carries the output power codec its hardware supports")
    void generationsCarryTheirOwnPowerCodec() {
        assertEquals(OutputPowerCodec.NEW_GEN, ReaderProfiles.NEW_GEN.outputPowerCodec());
        assertEquals(OutputPowerCodec.OLD_GEN, ReaderProfiles.OLD_GEN.outputPowerCodec());
    }

    @Test
    @DisplayName("a power one generation cannot reach is refused by that generation's profile")
    void aPowerBeyondAGenerationIsRefused() {
        ReaderConfig config = reader("NewGen", "notification");
        config.setOutputPowers(List.of(0.1, 0.8));

        assertFalse(ReaderProfiles.NEW_GEN.parametersFor(config, "h").isEmpty());
        assertThrows(IllegalArgumentException.class,
            () -> ReaderProfiles.OLD_GEN.parametersFor(config, "h"));
    }

    @Test
    @DisplayName("type lookup is case insensitive and generic means unmanaged")
    void typeLookup() {
        assertEquals(Optional.empty(), ReaderProfiles.find(null));
        assertEquals(Optional.empty(), ReaderProfiles.find("GENERIC"));
        assertEquals(Optional.empty(), ReaderProfiles.find("generic"));
        assertEquals(ReaderProfiles.NEW_GEN, ReaderProfiles.find("newgen").orElseThrow());
        assertEquals(ReaderProfiles.OLD_GEN, ReaderProfiles.find("OldGen").orElseThrow());
    }

    @Test
    @DisplayName("an unknown type is rejected and the supported types are listed")
    void unknownTypeIsRejected() {
        IllegalArgumentException thrown =
            assertThrows(IllegalArgumentException.class, () -> ReaderProfiles.find("MRU400"));

        assertTrue(thrown.getMessage().contains("MRU400"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("NewGen"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("OldGen"), thrown.getMessage());
    }
}
