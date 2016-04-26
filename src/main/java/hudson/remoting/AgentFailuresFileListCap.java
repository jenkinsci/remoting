package hudson.remoting;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maintains most recent N files in a directory in cooperation with the writer.
 *
 * Useful to record incidents as files without bankrupting
 *
 */
public class AgentFailuresFileListCap {

    private static final Logger LOGGER = Logger.getLogger(AgentFailuresFileListCap.class.getName());

    private final File folder;
    private final LinkedHashSet<File> files = new LinkedHashSet<File>();
    private int size;

    public AgentFailuresFileListCap(File folder, int size) {
        this(folder,null,size);
    }

    public AgentFailuresFileListCap(File folder, FilenameFilter filter, int size) {
        this.folder = folder;
        this.size = size;

        if (!folder.exists() && !folder.mkdirs())
            throw new Error("Failed to create "+folder);

        File[] sortedFiles = folder.listFiles(filter);
        if (sortedFiles==null)  return;

        // Sorting in ascending order.
        Arrays.sort(sortedFiles, new Comparator<File>() {
            public int compare(File o1, File o2) {
                long l = o1.lastModified() - o2.lastModified();
                if (l<0)    return -1;
                if (l>0)    return 1;
                return 0;
            }
        });

        files.addAll(Arrays.asList(sortedFiles));
    }

    public File getFolder() {
        return folder;
    }

    public synchronized void add(File f) {
        if (size <= files.size()) {
            Iterator<File> itr = files.iterator();
            while (size <= files.size()) {
                File old = itr.next();
                if (!old.delete()) {
                    LOGGER.log(Level.WARNING, "Failed to delete {0}", old);
                }
                itr.remove();
            }
        }

        files.add(f);
    }

    public synchronized void touch(File f){
        files.remove(f);
        add(f);
    }

    /**
     * Creates a new file object in this directory without changing the
     */
    public File file(String path) {
        return new File(folder,path);
    }
}
