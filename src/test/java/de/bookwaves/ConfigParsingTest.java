package de.bookwaves;

import de.bookwaves.sync.ReaderProfiles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that what {@code config.yaml} is documented to contain is what the loader
 * actually accepts.
 */
class ConfigParsingTest {

    private static InputStream yaml(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the shipped example configuration loads and validates")
    void exampleConfigurationIsValid() throws Exception {
        // config.example.yaml is what operators copy; if it does not load, it is wrong.
        Path example = Path.of("config.example.yaml");
        assertTrue(Files.exists(example), "config.example.yaml is missing");

        try (InputStream stream = Files.newInputStream(example)) {
            ConfigLoader.Configuration configuration = ConfigLoader.parse(stream);
            assertDoesNotThrow(
                () -> ConfigLoader.validateReaderConfigurations(configuration.getReaders()));
        }
    }

    @Test
    @DisplayName("a NewGen reader round-trips from YAML")
    void newGenReaderParses() throws Exception {
        ConfigLoader.Configuration configuration = ConfigLoader.parse(yaml("""
            hostName: "10.0.0.5"
            readers:
              - name: reader2
                type: NewGen
                address: 192.168.1.101
                port: 10001
                listenerPort: 20001
                mode: notification
                antennas: [1, 2]
                rssiFilters: [60, 65]
                outputPowers: [0.1, 0.8]
                username: user
                password: secret
            """));

        ReaderConfig reader = configuration.getReaders().get(0);

        assertEquals("NewGen", reader.getType());
        assertEquals(ReaderProfiles.NEW_GEN, reader.getProfile().orElseThrow());
        assertTrue(reader.isNotificationMode());
        assertTrue(reader.hasCredentials());
        assertEquals(List.of(1, 2), reader.getAntennas());
        assertEquals(List.of(60, 65), reader.getRssiFilters());
        assertEquals(List.of(0.1, 0.8), reader.getOutputPowers());
        assertEquals("10.0.0.5", configuration.getHostName());
    }

    @Test
    @DisplayName("an OldGen reader round-trips from YAML")
    void oldGenReaderParses() throws Exception {
        ConfigLoader.Configuration configuration = ConfigLoader.parse(yaml("""
            readers:
              - name: reader4
                type: OldGen
                address: 192.168.1.103
                port: 10001
                listenerPort: 20003
                mode: notification
                antennas: [1]
                rssiFilters: [60]
                outputPowers: [0.5]
            """));

        ReaderConfig reader = configuration.getReaders().get(0);

        assertEquals(ReaderProfiles.OLD_GEN, reader.getProfile().orElseThrow());
        assertDoesNotThrow(
            () -> ConfigLoader.validateReaderConfigurations(configuration.getReaders()));
    }

    @Test
    @DisplayName("a reader without a type defaults to unmanaged")
    void untypedReaderIsGeneric() throws Exception {
        ConfigLoader.Configuration configuration = ConfigLoader.parse(yaml("""
            readers:
              - name: reader1
                address: 192.168.1.100
                port: 10001
                mode: host
                antennas: [4]
            """));

        ReaderConfig reader = configuration.getReaders().get(0);

        assertEquals(ReaderProfiles.GENERIC, reader.getType());
        assertTrue(reader.getProfile().isEmpty());
        assertTrue(!reader.isManaged());
    }

    @Test
    @DisplayName("readerConfigurationPersistent defaults to true when the key is absent")
    void persistenceDefaultsToTrue() throws Exception {
        ConfigLoader.Configuration absent = ConfigLoader.parse(yaml("""
            readers:
              - name: reader1
                mode: host
            """));
        assertTrue(absent.isReaderConfigurationPersistent());

        ConfigLoader.Configuration explicitlyFalse = ConfigLoader.parse(yaml("""
            readerConfigurationPersistent: false
            readers:
              - name: reader1
                mode: host
            """));
        assertTrue(!explicitlyFalse.isReaderConfigurationPersistent());
    }

    @Test
    @DisplayName("an absent hostName reads as null rather than an empty string")
    void hostNameAbsent() throws Exception {
        ConfigLoader.Configuration configuration = ConfigLoader.parse(yaml("""
            readers:
              - name: reader1
                mode: host
            """));

        assertNull(configuration.getHostName());
    }
}
