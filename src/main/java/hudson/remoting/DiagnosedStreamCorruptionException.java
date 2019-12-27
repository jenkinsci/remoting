package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.PrintWriter;
import java.io.StreamCorruptedException;
import java.io.StringWriter;
import javax.annotation.Nonnull;

/**
 * Signals a {@link StreamCorruptedException} with some additional diagnostic information.
 *
 * @author Kohsuke Kawaguchi
 */
public class DiagnosedStreamCorruptionException extends StreamCorruptedException {
    private final Exception diagnoseFailure;

    @Nonnull
    private final byte[] readBack;

    @Nonnull
    private final byte[] readAhead;

    DiagnosedStreamCorruptionException(Exception cause, Exception diagnoseFailure,
            @Nonnull byte[] readBack, @Nonnull byte[] readAhead) {
        initCause(cause);
        this.diagnoseFailure = diagnoseFailure;
        this.readBack = readBack;
        this.readAhead = readAhead;
    }

    public Exception getDiagnoseFailure() {
        return diagnoseFailure;
    }

    @Nonnull
    public byte[] getReadBack() {
        return readBack.clone();
    }

    @Nonnull
    public byte[] getReadAhead() {
        return readAhead.clone();
    }

    @Override
    @SuppressFBWarnings(value = "INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE", justification = "Used for diagnosing stream corruption between agent and server.")
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(super.toString()).append("\n");
        buf.append("Read back: ").append(HexDump.toHex(readBack)).append('\n');
        buf.append("Read ahead: ").append(HexDump.toHex(readAhead));
        if (diagnoseFailure!=null) {
            StringWriter w = new StringWriter();
            PrintWriter p = new PrintWriter(w);
            diagnoseFailure.printStackTrace(p);
            p.flush();

            buf.append("\nDiagnosis problem:\n    ");
            buf.append(w.toString().trim().replace("\n","\n    "));
        }
        return buf.toString();
    }
}
