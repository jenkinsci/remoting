package hudson.remoting;

public class TestStaticResourceReference extends CallableBase {

    private static final long serialVersionUID = 1L;

    // this is really just to check that we can initialize a static property from searching a classpath resource
    private static boolean FALSE =
            TestStaticResourceReference.class.getClassLoader().getResource("BLAH") != null;

    @Override
    public Object call() {
        return "found the impossible: " + FALSE;
    }
}
