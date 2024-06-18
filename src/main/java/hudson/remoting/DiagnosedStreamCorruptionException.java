package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.PrintWriter;
import java.io.StreamCorruptedException;
import java.io.StringWriter;

/**
 * Signals a {@link StreamCorruptedException} with some additional diagnostic information.
 *
 * @author Kohsuke Kawaguchi
 */
public class DiagnosedStreamCorruptionException extends StreamCorruptedException {
    private final Exception diagnoseFailure;

    @NonNull
    private final byte[] readBack;

    @NonNull
    private final byte[] readAhead;

    DiagnosedStreamCorruptionException(
            Exception cause, Exception diagnoseFailure, @NonNull byte[] readBack, @NonNull byte[] readAhead) {
        initCause(cause);
        this.diagnoseFailure = diagnoseFailure;
        this.readBack = readBack;
        this.readAhead = readAhead;
    }

    public Exception getDiagnoseFailure() {
        return diagnoseFailure;
    }

    @NonNull
    public byte[] getReadBack() {
        return readBack.clone();
    }

    @NonNull
    public byte[] getReadAhead() {
        return readAhead.clone();
    }

    @Override
    @SuppressFBWarnings(
            value = "INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE",
            justification = "Used for diagnosing stream corruption between agent and server.")
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(super.toString()).append("\n");
        buf.append("Read back: ").append(HexDump.toHex(readBack)).append('\n');
        buf.append("Read ahead: ").append(HexDump.toHex(readAhead));
        if (diagnoseFailure != null) {
            StringWriter w = new StringWriter();
            PrintWriter p = new PrintWriter(w);
            diagnoseFailure.printStackTrace(p);
            p.flush();

            buf.append("\nDiagnosis problem:\n    ");
            buf.append(w.toString().trim().replace("\n", "\n    "));
        }
        return buf.toString();
    }
}
