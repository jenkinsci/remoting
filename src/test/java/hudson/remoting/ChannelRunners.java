package hudson.remoting;

import java.util.stream.Stream;

public final class ChannelRunners {
    public static final String PROVIDER_METHOD = "hudson.remoting.ChannelRunners#provider";

    private ChannelRunners() {}

    @SuppressWarnings("unused") // used by JUnit
    public static Stream<ChannelRunner> provider() {
        return Stream.of(
                new InProcessRunner(),
                new NioSocketRunner(),
                new NioPipeRunner(),
                new InProcessCompatibilityRunner(),
                new ForkRunner(),
                new ForkEBCDICRunner());
    }
}
