package de.bookwaves;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The RSSI filter (software defined): which readings of a tag survive, and whether the tag does. */
class RssiFilterTest {

    private record Reading(int antenna, int rssi) {}

    private static ReaderConfig reader(List<Integer> antennas, List<Integer> rssiFilters) {
        ReaderConfig config = new ReaderConfig();
        config.setName("r");
        config.setMode("host");
        config.setAntennas(antennas);
        config.setRssiFilters(rssiFilters);
        return config;
    }

    private static Optional<List<Reading>> filter(ReaderConfig config, Reading... readings) {
        return RssiFilter.forReader(config).filter("EPC", List.of(readings), Reading::antenna, Reading::rssi);
    }

    @Test
    @DisplayName("a tag heard only weaker than its antenna's filter is discarded")
    void weakTagIsDiscarded() {
        assertEquals(Optional.empty(), filter(reader(List.of(1), List.of(60)), new Reading(1, -70)));
    }

    @Test
    @DisplayName("a tag that reports no signal strength cannot be judged and passes")
    void tagWithoutReadingsPasses() {
        assertEquals(Optional.of(List.of()), filter(reader(List.of(1), List.of(60))));
    }

    @Test
    @DisplayName("a reading exactly at the filter is kept")
    void boundaryIsInclusive() {
        Reading atTheLimit = new Reading(1, -60);

        assertEquals(Optional.of(List.of(atTheLimit)), filter(reader(List.of(1), List.of(60)), atTheLimit));
    }

    @Test
    @DisplayName("each antenna judges its own reading, and the tag keeps the readings that pass")
    void eachAntennaJudgesItsOwnReading() {
        // Antenna 2 allows down to -65 dBm, antenna 1 only to -60.
        Reading weakOnOne = new Reading(1, -63);
        Reading fineOnTwo = new Reading(2, -63);

        assertEquals(Optional.of(List.of(fineOnTwo)),
            filter(reader(List.of(1, 2), List.of(60, 65)), weakOnOne, fineOnTwo));
    }

    @Test
    @DisplayName("a filter of 0 keeps even the weakest reading, as it does on the reader")
    void zeroMeansNoFiltering() {
        Reading faint = new Reading(1, -95);

        assertEquals(Optional.of(List.of(faint)), filter(reader(List.of(1), List.of(0)), faint));
    }

    @Test
    void unknownAntennaBypassRequiresRecoveryOnEveryActiveAntenna() {
        ReaderConfig config = reader(List.of(1, 2, 3), List.of(60, 60, 0));
        RssiFilter filter = config.rssiFilter();
        assertEquals(RssiFilter.State.NOT_YET_OBSERVED, filter.health().orElseThrow().state());

        filter.filter("EPC", List.<Reading>of(), Reading::antenna, Reading::rssi);
        assertEquals(Set.of(RssiFilter.BypassReason.MISSING_RSSI),
            filter.health().orElseThrow().unknownAntennaReasons());

        filter.filter("EPC", List.of(new Reading(1, -55)), Reading::antenna, Reading::rssi);
        // A subsequent bypass starts recovery over, even after antenna 1 recovered.
        filter.filter("EPC", List.<Reading>of(), Reading::antenna, Reading::rssi);
        assertEquals(Optional.empty(),
            filter.filter("EPC", List.of(new Reading(2, -70)), Reading::antenna, Reading::rssi));
        filter.filter("EPC", List.of(new Reading(3, -49)), Reading::antenna, Reading::rssi);
        assertEquals(RssiFilter.State.DEGRADED, config.rssiFilter().health().orElseThrow().state());

        filter.filter("EPC", List.of(new Reading(1, -55)), Reading::antenna, Reading::rssi);
        assertEquals(new RssiFilter.Health(RssiFilter.State.APPLIED, Map.of(), Set.of()),
            filter.health().orElseThrow());
        assertEquals(RssiFilter.State.NOT_YET_OBSERVED,
            reader(List.of(1, 2, 3), List.of(60, 60, 0)).rssiFilter().health().orElseThrow().state());
    }

    @Test
    void invalidReadingDegradesOnlyItsActiveAntenna() {
        RssiFilter filter = reader(List.of(1, 2, 3), List.of(60, 60, 0)).rssiFilter();
        assertEquals(Optional.of(List.of()),
            filter.filter("EPC", List.<Reading>of(), Reading::antenna, Reading::rssi, List.of(1, 3)));
        assertEquals(new RssiFilter.Health(RssiFilter.State.DEGRADED,
            Map.of(1, RssiFilter.BypassReason.INVALID_RSSI), Set.of()), filter.health().orElseThrow());

        filter.filter("EPC", List.of(new Reading(2, -45)), Reading::antenna, Reading::rssi);
        assertEquals(RssiFilter.State.DEGRADED, filter.health().orElseThrow().state());
        filter.filter("EPC", List.of(new Reading(1, -70)), Reading::antenna, Reading::rssi);
        assertEquals(RssiFilter.State.APPLIED, filter.health().orElseThrow().state());
    }

    @Test
    void healthIsAbsentWithoutActiveSoftwareFiltering() {
        ReaderConfig hardwareFilter = reader(List.of(1), List.of(60));
        hardwareFilter.setRssiFilterMode("reader");
        ReaderConfig hf = reader(List.of(1), List.of(60));
        hf.setProtocol("hf");
        for (ReaderConfig config : List.of(hardwareFilter, hf, reader(List.of(1), List.of(0)))) {
            assertEquals(Optional.empty(), config.rssiFilter().health());
        }
    }

    @Test
    @DisplayName("a reader that filters on its own is not filtered a second time")
    void readerModeFiltersNothing() {
        ReaderConfig config = reader(List.of(1), List.of(60));
        config.setRssiFilterMode("reader");
        Reading weak = new Reading(1, -70);

        assertEquals(Optional.of(List.of(weak)), filter(config, weak));
    }
}
