import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.Set;

/**
 * Experimenting with inspecting stream contents as it is read.
 *
 * @author Kohsuke Kawaguchi
 */
public class OISInterception {
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(Set.of("foo"));
        }

        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray())) {
            @Override
            protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
                ObjectStreamClass d = super.readClassDescriptor();
                // this can be used to filter out classes
                System.out.println(d.getName());
                return d;
            }
        };

        System.out.println(ois.readObject());
    }
}
