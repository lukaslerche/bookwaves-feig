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
        config.setOutputPowers(List.of(0.1, 0.8));
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
