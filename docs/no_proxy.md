# NO_PROXY Environment Variable

This optional setting provides a way to specify one or more hosts where the connection should not be proxied.
Similar settings are available in many applications. Unfortunately, there is no standard specification across the different implementations.
Each one defines its own syntax and supported capabilities.

Remoting provides an implementation that is similar to a number of other applications and systems. Its simple rules provide
sufficient power and flexibility for many needs.

Rules:
1. Environment variable may be named `NO_PROXY` or `no_proxy`.
1. Different specification pieces may be separated by a comma (`,`) or a pipe (`|`).
1. Each piece may specify an IP address, a network, or a host / domain.
1. An IP address may be IPv4 or IPv6.
1. An IPv6 address may be expressed in full or compressed form.
1. A network is expressed in CIDR notation, for example, `192.168.17.0/24`.
1. Localhost and loopback addresses are not proxied by default.
1. All subdomains matching a domain or host are not proxied.
1. A `NO_PROXY` setting of `jenkins.io` matches `repo.jenkins.io`, `sub.sub.jenkins.io`, and `jenkins.io`. It also matches `myjenkins.io`.
1. The following forms are identical: `jenkins.io`, `.jenkins.io`, and `*.jenkins.io`.
1. All other notations are ignored.

## History

The exact implementation has changed in different remoting versions. The 3.28 release of Remoting clarified and expanded a
number of these capabilities. All specifications that worked prior to 3.28 should still work. Enhancements added at 3.28
include environment variable capitalization, pipe separation, compressed
IPv6 form, networks, and simple hostnames or root domains. The [Changelog](../CHANGELOG.md) provides information on some of the prior changes.
