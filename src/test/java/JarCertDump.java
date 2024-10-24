import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.apache.commons.io.IOUtils;

/**
 * Tool to parse the certificate chain in the jar file.
 *
 * @author Kohsuke Kawaguchi
 */
public class JarCertDump {
    public static void main(String[] args) throws IOException {
        try (JarFile j = new JarFile(new File(args[0]))) {
            JarEntry je = j.getJarEntry("hudson/remoting/Channel.class");
            if (je == null) {
                throw new IllegalArgumentException();
            }
            IOUtils.readLines(j.getInputStream(je), StandardCharsets.UTF_8);
            for (Certificate c : je.getCertificates()) {
                System.out.println("################# Certificate #################");
                System.out.println(c);
            }
        }
    }
}
