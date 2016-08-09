package hudson.remoting;

import hudson.remoting.ExportTable.Source;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.LinkedList;

/**
 * Record reference counting ups and downs on a specific object
 * for diagnosing a specific reference counting problem in a pinpoint.
 *
 * @author Kohsuke Kawaguchi
 * @see ExportTable.Entry#recorder
 */
class ReferenceCountRecorder {
    /**
     * Cap on the number of events we keep in memory.
     */
    private final int cap;

    /**
     * Total event count. If {@code cap &lt; eventCount} we have some event loss due to the array size capping.
     */
    private int total;

    private LinkedList<Event> events = new LinkedList<Event>();

    ReferenceCountRecorder(int cap) {
        this.cap = cap;
    }

    ReferenceCountRecorder() {
        this(1024);
    }

    synchronized void addEvent(Event ev) {
        total++;
        events.add(ev);
        while (cap<events.size()) {
            events.remove(0);
        }
    }

    void onAddRef(Throwable callSite) {
        addEvent(new AddRefEvent(callSite));
    }

    void onRelease(Throwable callSite) {
        addEvent(new ReleaseEvent(callSite));
    }

    /**
     * Dumps this recorded result into the given {@link PrintWriter}
     */
    synchronized void dump(PrintWriter w) {
        w.printf("  Reference count recording: cap=%d total=%d%n", cap, total);
        for (Event e : events) {
            w.printf("  %s at %s%n", e.getClass().getSimpleName(), new Date(e.site.timestamp));

            StringWriter sw = new StringWriter();
            e.site.printStackTrace(new PrintWriter(sw,true));
            w.println(Util.indent(sw.toString()));
        }
    }

    abstract static class Event {
        ExportTable.Source site;

        protected Event(Throwable callSite) {
            this.site = new Source(callSite);
        }
    }

    static class AddRefEvent extends Event {
        AddRefEvent(Throwable site) {
            super(site);
        }
    }

    static class ReleaseEvent extends Event {
        ReleaseEvent(Throwable site) {
            super(site);
        }
    }
}
