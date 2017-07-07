package test;

import com.google.common.base.Predicate;

import java.io.Serializable;

import static junit.framework.Assert.*;

/**
 * For testing nested objects
 *
 * @author Kohsuke Kawaguchi
 */
public class Foo implements Predicate<Void>, Serializable {
    Foo1 one = new Foo1();
    Foo2 two = new Foo2();

    public static class Foo1 implements Serializable {
        public static class Foo11 implements Serializable {}
        public static class Foo12 implements Serializable {}

        Foo11 one = new Foo11();
        Foo12 two = new Foo12();

        public void validate() {
            assertNotNull(one);
            assertNotNull(two);
        }
    }
    public static class Foo2 implements Serializable {
        public static class Foo21 implements Serializable {}
        public class Foo22 implements Serializable {}

        Foo21 one = new Foo21();
        Foo22 two = new Foo22();

        public void validate() {
            assertNotNull(one);
            assertNotNull(two);
        }
    }

    /**
     * Verify that the object is still in a good state
     */
    public boolean apply(Void aVoid) {
        one.validate();
        two.validate();
        return true;
    }
}
