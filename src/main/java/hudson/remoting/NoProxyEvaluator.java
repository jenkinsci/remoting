/*
 * The MIT License
 *
 * Copyright (c) 2004-2018, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jenkinsci.remoting.org.apache.commons.net.util.SubnetUtils;
import org.jenkinsci.remoting.org.apache.commons.validator.routines.InetAddressValidator;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Restricted(NoExternalUse.class)
public class NoProxyEvaluator {

    private static final Pattern COMMA = Pattern.compile(",");
    private static final Pattern PIPE = Pattern.compile("\\|");

    private final Set<InetAddress> noProxyIpAddresses = new HashSet<>();
    private final Set<SubnetUtils.SubnetInfo> noProxySubnets = new HashSet<>();
    private final Set<String> noProxyDomainsHosts = new HashSet<>();

    public static boolean shouldProxy(String host) {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(getEnvironmentValue());
        return evaluator.shouldProxyHost(host);
    }

    NoProxyEvaluator(String noProxySpecification) {
        if (noProxySpecification != null) {
            processSpecificationsIntoTypes(noProxySpecification);
        }
    }

    boolean shouldProxyHost(String host) {
        if (isLocalhost(host)) {
            return false;
        }
        if (isIpAddress(host)) {
            try {
                InetAddress hostAddress = InetAddress.getByName(host);
                if (hostAddress.isLoopbackAddress()) {
                    return false;
                }
                if (matchesIpAddress(hostAddress)) {
                    return false;
                }
                return !matchesSubnet(host);
            } catch (UnknownHostException e) {
                // Could not process it so just proxy it.
                return true;
            }
        }
        return !matchesDomainHost(host);
    }

    @SuppressFBWarnings(value = "IMPROPER_UNICODE", justification = "host value is provided by administrator at launch")
    private boolean isLocalhost(String host) {
        return host.toLowerCase(Locale.ROOT).equals("localhost");
    }

    private static String getEnvironmentValue() {
        String noProxy = System.getenv("no_proxy");
        if (noProxy == null) {
            noProxy = System.getenv("NO_PROXY");
        }
        return noProxy;
    }

    private boolean matchesIpAddress(InetAddress hostAddress) {
        return noProxyIpAddresses.stream().anyMatch(inetAddress -> inetAddress.equals(hostAddress));
    }

    private void processSpecificationsIntoTypes(String noProxySpecification) {
        noProxySpecification = noProxySpecification.trim();
        String[] noProxySpecifications = splitComponents(noProxySpecification);
        for (String specification : noProxySpecifications) {
            specification = stripLeadingStarDot(stripLeadingDot(specification.trim()));
            if (specification.isEmpty()) {
                continue;
            }
            if (isIpAddress(specification)) {
                try {
                    noProxyIpAddresses.add(InetAddress.getByName(specification));
                } catch (UnknownHostException e) {
                    // Failed to create InetAddress.
                }
                continue;
            }
            try {
                SubnetUtils subnetUtils = new SubnetUtils(specification);
                SubnetUtils.SubnetInfo subnetInfo = subnetUtils.getInfo();
                noProxySubnets.add(subnetInfo);
                continue;
            } catch (IllegalArgumentException iae) {
                // Not a subnet definition.
            }
            noProxyDomainsHosts.add(specification);
        }
    }

    private String[] splitComponents(String noProxySpecification) {
        String[] noProxySpecifications;
        if (noProxySpecification.contains(",")) {
            noProxySpecifications = COMMA.split(noProxySpecification);
        } else if (noProxySpecification.contains("|")) {
            noProxySpecifications = PIPE.split(noProxySpecification);
        } else {
            noProxySpecifications = new String[] {noProxySpecification};
        }
        return noProxySpecifications;
    }

    private String stripLeadingDot(String string) {
        return string.startsWith(".") ? string.substring(1) : string;
    }

    private String stripLeadingStarDot(String string) {
        return string.startsWith("*.") ? string.substring(2) : string;
    }

    private boolean matchesSubnet(String host) {
        return noProxySubnets.stream().anyMatch(subnet -> subnet.isInRange(host));
    }

    private boolean matchesDomainHost(String host) {
        return noProxyDomainsHosts.stream().anyMatch(host::endsWith);
    }

    private boolean isIpAddress(String host) {
        return InetAddressValidator.getInstance().isValid(host);
    }
}
