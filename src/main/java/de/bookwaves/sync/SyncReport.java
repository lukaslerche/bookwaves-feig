package de.bookwaves.sync;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What a synchronisation found, and what it changed.
 *
 * @param readerName the reader this report is about
 * @param drifts     parameters whose value on the reader differs from the configuration
 * @param written    parameters actually written during this synchronisation
 */
public record SyncReport(String readerName, List<Drift> drifts, List<String> written) {

    public SyncReport {
        drifts = List.copyOf(drifts);
        written = List.copyOf(written);
    }

    /** One parameter whose value on the reader does not match the configuration. */
    public record Drift(ParamSpec spec, ParamValue actual) {

        @Override
        public String toString() {
            return spec.name() + " (" + spec.purpose() + "): expected "
                + spec.desired().describe() + ", found " + actual.describe();
        }
    }

    /** Whether the reader already matches its configuration. */
    public boolean inSync() {
        return drifts.isEmpty();
    }

    /** A one-line summary for logging. */
    public String summary() {
        if (inSync() && written.isEmpty()) {
            return "Reader " + readerName + ": configuration matches";
        }
        if (written.isEmpty()) {
            return "Reader " + readerName + ": " + drifts.size() + " parameter(s) drifted - "
                + drifts.stream().map(Drift::toString).collect(Collectors.joining("; "));
        }
        return "Reader " + readerName + ": wrote " + written.size() + " parameter(s) - "
            + String.join(", ", written);
    }
}
