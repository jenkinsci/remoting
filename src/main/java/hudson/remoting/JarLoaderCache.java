package hudson.remoting;

import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class JarLoaderCache {
    static final ConcurrentMap<Checksum, URL> knownJars = new ConcurrentHashMap<>();
    static final AtomicInteger knownJarsHits = new AtomicInteger(0);
    static final ConcurrentMap<URL, Checksum> checksums = new ConcurrentHashMap<>();

    static final AtomicInteger checksumsHits = new AtomicInteger(0);

    static public void showInfo(){
        System.out.println(" KnownJar Hits " + knownJarsHits.get());
        System.out.println(" Checksum Hits " + checksumsHits.get());
    }
}
