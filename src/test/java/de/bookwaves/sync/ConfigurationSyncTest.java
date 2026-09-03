package de.bookwaves.sync;

import de.bookwaves.ReaderConfig;
import de.bookwaves.ReaderManager.ReaderOperationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationSyncTest {

    private static final String HOST = "10.0.0.5";
    private static final String MODE = "OperatingMode.Mode";
    private static final String RSSI_ANTENNA_1 = "AirInterface.Antenna.UHF.No1.RSSIFilter";
    private static final String POWER_ANTENNA_1 = "AirInterface.Antenna.UHF.No1.OutputPower";

    private static ReaderConfig notificationReader() {
        ReaderConfig config = new ReaderConfig();
        config.setName("reader2");
        config.setAddress("192.168.1.101");
        config.setPort(10001);
        config.setListenerPort(20001);
        config.setMode("notification");
        config.setType("NewGen");
        config.setAntennas(List.of(1, 2));
        config.setRssiFilters(List.of(60, 65));
        config.setOutputPowers(List.of(0.1, 0.8));
        return config;
    }

    private static ConfigurationSync syncFor(ReaderProfile profile) {
        return new ConfigurationSync(profile, HOST, true);
    }

    @Nested
    @DisplayName("drift detection")
    class DriftDetection {

        @Test
        @DisplayName("a reader already in the configured mode is not reported as drifted")
        void readerInConfiguredModeIsNotDrifted() throws Exception {
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port =
                FakeReaderConfigPort.inSyncWith(ReaderProfiles.NEW_GEN, config, HOST);

            SyncReport report = syncFor(ReaderProfiles.NEW_GEN).check(port, config);

            assertTrue(report.inSync(), "expected no drift but got: " + report.summary());
        }

        @Test
        @DisplayName("the notification operating mode 0xC0 survives the round trip")
        void notificationModeValueIsNotSignExtended() throws Exception {
            // 0xC0 does not fit a Java byte; holding it as one makes it -64.
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port = FakeReaderConfigPort
                .inSyncWith(ReaderProfiles.NEW_GEN, config, HOST)
                .with(MODE, ParamValue.ofByte(0xC0));

            SyncReport report = syncFor(ReaderProfiles.NEW_GEN).check(port, config);

            assertTrue(report.inSync(), "0xC0 should compare equal to itself");
        }

        @Test
        @DisplayName("only the parameters that actually differ are reported")
        void reportsOnlyDriftedParameters() throws Exception {
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port = FakeReaderConfigPort
                .inSyncWith(ReaderProfiles.NEW_GEN, config, HOST)
                .with(RSSI_ANTENNA_1, ParamValue.ofLong(99));

            SyncReport report = syncFor(ReaderProfiles.NEW_GEN).check(port, config);

            assertFalse(report.inSync());
            assertEquals(1, report.drifts().size(), report.summary());
            assertEquals(RSSI_ANTENNA_1, report.drifts().get(0).spec().name());
        }

        @Test
        @DisplayName("a wrong mode does not drag every other parameter with it")
        void wrongModeDoesNotImplyEverythingDrifted() throws Exception {
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port = FakeReaderConfigPort
                .inSyncWith(ReaderProfiles.NEW_GEN, config, HOST)
                .with(MODE, ParamValue.ofByte(0x00));

            SyncReport report = syncFor(ReaderProfiles.NEW_GEN).check(port, config);

            assertEquals(1, report.drifts().size(), report.summary());
            assertEquals(MODE, report.drifts().get(0).spec().name());
        }

        @Test
        @DisplayName("a parameter the reader does not support fails naming reader and parameter")
        void unsupportedParameterFailsLoudly() {
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port = FakeReaderConfigPort
                .inSyncWith(ReaderProfiles.NEW_GEN, config, HOST)
                .without(RSSI_ANTENNA_1);

            ReaderOperationException thrown = assertThrows(ReaderOperationException.class,
                () -> syncFor(ReaderProfiles.NEW_GEN).check(port, config));

            assertTrue(thrown.getMessage().contains("reader2"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains(RSSI_ANTENNA_1), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("repair")
    class Repair {

        @Test
        @DisplayName("only drifted parameters are written")
        void writesOnlyDriftedParameters() throws Exception {
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port = FakeReaderConfigPort
                .inSyncWith(ReaderProfiles.NEW_GEN, config, HOST)
                .with(POWER_ANTENNA_1, ParamValue.ofByte(0x19));

            SyncReport report = syncFor(ReaderProfiles.NEW_GEN).apply(port, config, false);

            assertEquals(List.of(POWER_ANTENNA_1), port.writes());
            assertEquals(List.of(POWER_ANTENNA_1), report.written());
            assertEquals(ParamValue.ofByte(0x10), port.valueOf(POWER_ANTENNA_1));
        }

        @Test
        @DisplayName("a reader already in sync is not written to at all")
        void inSyncReaderIsNotWritten() throws Exception {
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port =
                FakeReaderConfigPort.inSyncWith(ReaderProfiles.NEW_GEN, config, HOST);

            SyncReport report = syncFor(ReaderProfiles.NEW_GEN).apply(port, config, false);

            assertTrue(port.writes().isEmpty(), "expected no writes, got " + port.writes());
            assertEquals(0, port.applyCount(), "an unchanged reader should not be applied to");
            assertTrue(report.inSync());
        }

        @Test
        @DisplayName("force rewrites every parameter regardless of drift")
        void forceWritesEverything() throws Exception {
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port =
                FakeReaderConfigPort.inSyncWith(ReaderProfiles.NEW_GEN, config, HOST);
            int expected = ReaderProfiles.NEW_GEN.parametersFor(config, HOST).size();

            SyncReport report = syncFor(ReaderProfiles.NEW_GEN).apply(port, config, true);

            assertEquals(expected, port.writes().size());
            assertEquals(expected, report.written().size());
            assertEquals(1, port.applyCount());
        }

        @Test
        @DisplayName("a failed write aborts the synchronisation naming the parameter")
        void failedWriteAborts() {
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port = FakeReaderConfigPort
                .inSyncWith(ReaderProfiles.NEW_GEN, config, HOST)
                .failingWriteOf(POWER_ANTENNA_1);

            ReaderOperationException thrown = assertThrows(ReaderOperationException.class,
                () -> syncFor(ReaderProfiles.NEW_GEN).apply(port, config, true));

            assertTrue(thrown.getMessage().contains(POWER_ANTENNA_1), thrown.getMessage());
            assertEquals(0, port.applyCount(), "a failed write must not be applied");
        }

        @Test
        @DisplayName("persistence is passed through to apply")
        void persistenceIsPassedThrough() throws Exception {
            ReaderConfig config = notificationReader();
            FakeReaderConfigPort port = FakeReaderConfigPort
                .inSyncWith(ReaderProfiles.NEW_GEN, config, HOST)
                .with(RSSI_ANTENNA_1, ParamValue.ofLong(1));

            new ConfigurationSync(ReaderProfiles.NEW_GEN, HOST, false).apply(port, config, false);

            assertEquals(Boolean.FALSE, port.lastApplyPersistent());
        }
    }

    @Nested
    @DisplayName("protected parameters")
    class Protected {

        @Test
        @DisplayName("the reader's own IP address is never written")
        void readerIpIsProtected() {
            assertTrue(ProtectedParameters.isProtected("HostInterface.LAN.IPv4.IPAddress"));
            assertTrue(ProtectedParameters.isProtected("HostInterface.LAN.IPv6.IPAddress"));
            assertTrue(ProtectedParameters.isProtected("HostInterface.LAN.Hostname.Name"));
        }

        @Test
        @DisplayName("access protection and credentials are never written")
        void credentialsAreProtected() {
            assertTrue(ProtectedParameters.isProtected("AccessProtection.Lock_CFG0"));
            assertTrue(ProtectedParameters.isProtected("AccessProtection.CryptoMode"));
            assertTrue(ProtectedParameters.isProtected("HostInterface.Password"));
        }

        @Test
        @DisplayName("the notification callback address is not protected")
        void callbackAddressIsWritable() {
            // Channel1.Address holds the host's address, not the reader's own.
            assertFalse(ProtectedParameters.isProtected("HostInterface.LAN.Remote.Channel1.Address"));
        }

        @Test
        @DisplayName("no profile emits a protected parameter")
        void profilesEmitNoProtectedParameters() {
            ReaderConfig config = notificationReader();
            for (ReaderProfile profile : List.of(ReaderProfiles.NEW_GEN, ReaderProfiles.OLD_GEN)) {
                for (ParamSpec spec : profile.parametersFor(config, HOST)) {
                    assertFalse(ProtectedParameters.isProtected(spec.name()),
                        profile.id() + " emits protected parameter " + spec.name());
                }
            }
        }
    }
}
