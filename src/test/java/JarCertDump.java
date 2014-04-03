import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.security.cert.Certificate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Tool to parse the certificate chain in the jar file.
 *
 * @author Kohsuke Kawaguchi
 */
public class JarCertDump {
    public static void main(String[] args) throws IOException {
        JarFile j = new JarFile(new File(args[0]));
        JarEntry je = j.getJarEntry("hudson/remoting/Channel.class");
        if (je==null)   throw new IllegalArgumentException();
        IOUtils.readLines(j.getInputStream(je));
        for (Certificate c : je.getCertificates()) {
            System.out.println("################# Certificate #################");
            System.out.println(c);
        }
    }
}
