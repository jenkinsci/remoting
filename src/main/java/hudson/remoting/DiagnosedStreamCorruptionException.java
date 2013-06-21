package hudson.remoting;

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
    private final byte[] readAhead;

    public DiagnosedStreamCorruptionException(StreamCorruptedException cause, Exception diagnoseFailure, byte[] readAhead) {
        initCause(cause);
        this.diagnoseFailure = diagnoseFailure;
        this.readAhead = readAhead;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(super.toString()).append("\n");
        buf.append("Read ahead: "+HexDump.toHex(readAhead));
        if (diagnoseFailure!=null) {
            StringWriter w = new StringWriter();
            PrintWriter p = new PrintWriter(w);
            diagnoseFailure.printStackTrace(p);
            p.flush();

            buf.append("Diagnosis problem:\n    ");
            buf.append(w.toString().trim().replace("\n","\n    "));
        }
        return buf.toString();
    }
}
