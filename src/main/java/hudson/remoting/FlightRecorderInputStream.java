package hudson.remoting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Filter input stream that records the content as it's read, so that it can be reported
 * in case of a catastrophic stream corruption problem.
 *
 * @author Kohsuke Kawaguchi
 */
class FlightRecorderInputStream extends InputStream {
    private final InputStream source;
    private final ByteArrayOutputStream recorder = new ByteArrayOutputStream();

    FlightRecorderInputStream(InputStream source) {
        this.source = source;
    }

    /**
     * Rewinds the record buffer and forget everything that was recorded.
     */
    public void clear() {
        recorder.reset();
    }

    /**
     * Gets the recorded content.
     */
    public byte[] getRecord() {
        return recorder.toByteArray();
    }

    /**
     * Creates a {@link DiagnosedStreamCorruptionException} based on the recorded content plus read ahead.
     * The caller is responsible for throwing the exception.
     */
    public DiagnosedStreamCorruptionException analyzeCrash(Exception problem, String diagnosisName) {
        final ByteArrayOutputStream readAhead = new ByteArrayOutputStream();
        final IOException[] error = new IOException[1];

        Thread diagnosisThread = new Thread(diagnosisName+" stream corruption diagnosis thread") {
            public void run() {
                int b;
                try {
                    // not all InputStream will look for the thread interrupt flag, so check that explicitly to be defensive
                    while (!Thread.interrupted() && (b=source.read())!=-1) {
                        readAhead.write(b);
                    }
                } catch (IOException e) {
                    error[0] = e;
                }
            }
        };

        // wait up to 1 sec to grab as much data as possible
        diagnosisThread.start();
        try {
            diagnosisThread.join(1000);
        } catch (InterruptedException _) {
            // we are only waiting for a fixed amount of time, so we'll pretend like we were in a busy loop
            Thread.currentThread().interrupt();
            // fall through
        }

        IOException diagnosisProblem = error[0]; // capture the error, if any, before we kill the thread
        if (diagnosisThread.isAlive())
            diagnosisThread.interrupt();    // if it's not dead, kill

        return new DiagnosedStreamCorruptionException(problem,diagnosisProblem,getRecord(),readAhead.toByteArray());

    }

    @Override
    public int read() throws IOException {
        int i = source.read();
        if (i>=0)
            recorder.write(i);
        return i;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        len = source.read(b, off, len);
        if (len>0)
            recorder.write(b,off,len);
        return len;
    }

    /**
     * To record the bytes we've skipped, convert the call to read.
     */
    @Override
    public long skip(long n) throws IOException {
        byte[] buf = new byte[(int)Math.min(n,64*1024)];
        return read(buf,0,buf.length);
    }

    @Override
    public int available() throws IOException {
        return source.available();
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
