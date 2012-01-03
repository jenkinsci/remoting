package hudson.remoting;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a listener which listens write event, created by listenable output stream (@link OuputStreamImpl), and writes into its output streams (@link OutputStream). 
 * 
 * @author Lucie Votypkova
 */
public class OutputListener {

    private List<OutputStream> outputStreams = new ArrayList<OutputStream>();

    public OutputListener(List<OutputStream> streams) throws java.io.IOException {
        this.outputStreams = streams;
    }

    public OutputListener(OutputStream stream) throws java.io.IOException {
        this.outputStreams.add(stream);
    }

    public OutputStream createListenableOutputStream(OutputStream stream) {
        outputStreams.add(0,stream);
        return new OutputStreamImpl(this);
    }

    public void writeEvent(int b) {
        for (OutputStream stream : outputStreams) {
            try {
                stream.write(b);
            } catch (IOException ex) {
                Logger.getLogger(OutputListener.class.getName()).log(Level.SEVERE, null, ex);
            }

        }
    }

    public void addOutputStream(OutputStream out) {
        outputStreams.add(out);
    }

    /**
     * Represents listenable output stream, which creates write event for its listener if the write method is called/
     * 
     */
    private class OutputStreamImpl extends OutputStream {

        private OutputListener listener;

        public OutputStreamImpl(OutputListener listener) {
            this.listener = listener;
        }

        @Override
        public void write(int b) {
            listener.writeEvent(b);
        }
    }
}
