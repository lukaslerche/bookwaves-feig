package de.bookwaves.sync;

import de.bookwaves.ReaderManager.ReaderOperationException;

import de.feig.fedm.ConfigParamInfo;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.ReaderStatus;
import de.feig.fedm.types.BoolRef;
import de.feig.fedm.types.ByteRef;
import de.feig.fedm.types.LongRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ReaderConfigPort} backed by a connected FEIG {@link ReaderModule}.
 *
 * <p>The only class that knows about the SDK's typed reference wrappers and its
 * per-type parameter overloads.
 */
public class FeigReaderConfigPort implements ReaderConfigPort {

    private static final Logger log = LoggerFactory.getLogger(FeigReaderConfigPort.class);

    /** Answered by a reader that has no RAM-only configuration bank. */
    private static final int NO_RAM_CONFIGURATION = -114;

    private final ReaderModule readerModule;
    private final String readerName;

    public FeigReaderConfigPort(ReaderModule readerModule, String readerName) {
        this.readerModule = readerModule;
        this.readerName = readerName;
    }

    @Override
    public void readCompleteConfiguration() throws ReaderOperationException {
        check(readerModule.config().readCompleteConfiguration(), "read complete configuration");
    }

    @Override
    public boolean supports(String parameter) {
        return readerModule.config().hasConfigPara(parameter, new ConfigParamInfo());
    }

    @Override
    public ParamValue get(String parameter, ParamType type) throws ReaderOperationException {
        switch (type) {
            case BOOL -> {
                BoolRef ref = new BoolRef();
                check(readerModule.config().getConfigPara(parameter, ref), "read " + parameter);
                return ParamValue.bool(ref.getValue());
            }
            case BYTE -> {
                ByteRef ref = new ByteRef();
                check(readerModule.config().getConfigPara(parameter, ref), "read " + parameter);
                // Reader byte parameters are unsigned; mask away the Java sign extension.
                return ParamValue.ofByte(ref.getValue() & 0xFF);
            }
            case LONG -> {
                LongRef ref = new LongRef();
                check(readerModule.config().getConfigPara(parameter, ref), "read " + parameter);
                return ParamValue.ofLong(ref.getValue());
            }
            case STRING -> {
                StringBuilder ref = new StringBuilder();
                check(readerModule.config().getConfigPara(parameter, ref), "read " + parameter);
                return ParamValue.text(ref.toString());
            }
            default -> throw new ReaderOperationException("Unsupported parameter type " + type);
        }
    }

    @Override
    public void set(String parameter, ParamValue value) throws ReaderOperationException {
        // A write that reaches the SDK cannot be taken back, so the check sits on the
        // boundary as well as in the engine.
        if (ProtectedParameters.isProtected(parameter)) {
            throw new ReaderOperationException(ProtectedParameters.refusalMessage(parameter));
        }

        int state = switch (value) {
            case ParamValue.Text text -> readerModule.config().changeConfigPara(parameter, text.value());
            case ParamValue.Numeric numeric -> switch (numeric.type()) {
                case BOOL -> readerModule.config().changeConfigPara(parameter, numeric.asBoolean());
                case BYTE -> readerModule.config().changeConfigPara(parameter, numeric.asByte());
                case LONG -> readerModule.config().changeConfigPara(parameter, numeric.value());
                case STRING -> throw new IllegalStateException("Numeric value typed as STRING");
            };
        };
        check(state, "write " + parameter + "=" + value.describe());
    }

    @Override
    public void apply(boolean persistent) throws ReaderOperationException {
        log.debug("Reader {}: applying configuration (persistent={})", readerName, persistent);

        // Parameters are already permanent by the time this runs, so a non-persistent
        // apply asks for something no reader delivers - including ones that answer ok.
        if (!persistent) {
            log.warn("Reader {}: a non-persistent apply was requested, but no tested reader"
                + " honours one - the parameters already written are expected to survive a"
                + " power cycle. Treat this as a permanent change.", readerName);
        }

        int state = readerModule.config().applyConfiguration(persistent);

        // A bare error code here reads as "nothing happened", when the opposite is true.
        if (!persistent && state == NO_RAM_CONFIGURATION) {
            throw new ReaderOperationException(
                "Reader " + readerName + ": this reader has no RAM-only configuration, so a"
                    + " non-persistent apply is not possible. The parameters written before this"
                    + " point are already on the reader and will survive a power cycle. Set"
                    + " readerConfigurationPersistent: true to say so deliberately.",
                state);
        }

        check(state, "apply configuration");
    }

    /**
     * Translates an SDK status code into an exception, or returns quietly on success.
     *
     * <p>{@code changeConfigPara} answers 1 when it changed the local value and 0 when
     * the value already matched; both are successes. Anything negative is an SDK error
     * code, anything above 1 a reader status.
     */
    private void check(int state, String what) throws ReaderOperationException {
        if (state == ErrorCode.Ok || state == 1) {
            return;
        }
        String detail = state < ErrorCode.Ok ? ErrorCode.toString(state) : ReaderStatus.toString(state);
        throw new ReaderOperationException(
            "Reader " + readerName + ": failed to " + what + " - " + detail + " (code " + state + ")",
            state
        );
    }
}
