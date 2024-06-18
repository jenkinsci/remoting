package hudson.remoting;

import java.io.IOException;

public class TestStaticGetResources extends CallableBase {

    private static final long serialVersionUID = 1L;

    private static boolean FIRST_RESOURCE;

    static {
        try {
            FIRST_RESOURCE = TestStaticGetResources.class
                    .getClassLoader()
                    .getResources("BLAH")
                    .hasMoreElements();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Object call() {
        return "found the impossible: " + FIRST_RESOURCE;
    }
}
