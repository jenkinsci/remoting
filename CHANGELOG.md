Changelog
====

Below you can read the changelogs for the trunk version of remoting.
This file also provides links to Jenkins versions,
which bundle the specified remoting version.
See [Jenkins changelog](https://jenkins.io/changelog/) for more details.

##### 3.34 and later

Changelog moved to [GitHub Releases](https://github.com/jenkinsci/remoting/releases)

##### 3.33

Release date: June 20, 2019

* [JENKINS-57959](https://issues.jenkins-ci.org/browse/JENKINS-57959) Upgrade args4j dependency to 2.33 (latest).

##### 3.32

Release date: June 19, 2019

* [JENKINS-50095](https://issues.jenkins-ci.org/browse/JENKINS-50095) Check for Remoting minimum version when connecting to master.
* [JENKINS-57959](https://issues.jenkins-ci.org/browse/JENKINS-57959) Preparatory fix for JENKINS-57959 by removing "final" keyword from variables used with args4j.
* Switch to Spotbugs from Findbugs.
* Fix log message typo.

##### 3.31

Release date: June 12, 2019

* [JENKINS-57713](https://issues.jenkins-ci.org/browse/JENKINS-57713) Revert change for [JENKINS-46515](https://issues.jenkins-ci.org/browse/JENKINS-46515) / [PR#193](https://github.com/jenkinsci/remoting/pull/193) because it broke the init sequence for some cloud agent scenarios.

##### 3.30

Release date: April 30, 2019

* [JENKINS-51004](https://issues.jenkins-ci.org/browse/JENKINS-51004) Pass "-loggingConfig" argument for protocols besides JNLP.
* [JENKINS-57107](https://issues.jenkins-ci.org/browse/JENKINS-57107) Improve URL proxy handling.
* [JENKINS-46515](https://issues.jenkins-ci.org/browse/JENKINS-46515) Exit the Launcher process on 4xx errors but continue trying on 5xx.

##### 3.29

Release date: February 5, 2019

* [JENKINS-55976](https://issues.jenkins-ci.org/browse/JENKINS-55976) Add missing log call.

##### 3.28

Release date: December 10, 2018

* [JENKINS-48778](https://issues.jenkins-ci.org/browse/JENKINS-48778) Enhance the no_proxy configurations. See [NO_PROXY Environment Variable](docs/no_proxy.md) for documentation.
* Better diagnostics for errors from RemoteClassLoader.fetch4.
* Ignore attempts to flush a ProxyOutputStream which has already been finalized.
* [JENKINS-51108](https://issues.jenkins-ci.org/browse/JENKINS-51108) - Allow remoting to publish incrementals.
* [JENKINS-47977](https://issues.jenkins-ci.org/browse/JENKINS-47977) - Jenkins build failed if Remoting could not create the JAR cache.
* [JENKINS-50730](https://issues.jenkins-ci.org/browse/JENKINS-50730) - Improve log messaging on reconnect.
* [JENKINS-49987](https://issues.jenkins-ci.org/browse/JENKINS-49987) - Clean up warnings about anonymous callable.
* [JENKINS-54005](https://issues.jenkins-ci.org/browse/JENKINS-54005) - Another instance of an unnecessarily severe warning when unexporting.

##### 3.27

Release date: September 28, 2018

* Channel.notifyJar was being called too often.
* Downgrade error messages from SynchronousCommandTransport.
* [JENKINS-53569](https://issues.jenkins-ci.org/browse/JENKINS-53569) - Remove unnecessary locking that could cause deadlock when removing a filter from the ProtocolStack.

##### 3.26

Release date: August 31, 2018

* [JENKINS-52945](https://issues.jenkins-ci.org/browse/JENKINS-52945) - AnonymousClassWarnings should not warn about enums.
* [JENKINS-42533](https://issues.jenkins-ci.org/browse/JENKINS-42533) - Eliminate another excessively severe warning about trying to export already unexported object.

##### 3.25

Release date: July 31, 2018 => Weekly 2.138 / LTS 2.121.3

* [SECURITY-637](https://jenkins.io/security/advisory/2018-08-15/) - Prevent deserialization of URL objects with host components

##### 3.24

Release date: July 12, 2018

* Refresh the code-signing certificate
* No functional changes

##### 3.23

Release date: June 29, 2018

* [JENKINS-52204](https://issues.jenkins-ci.org/browse/JENKINS-52204) -
Skip Tcp Agent Listener port availability check when `-tunnel` option is set
(regression in 3.22)

##### 3.22

Release date: Jun 22, 2018 => 2.129

* [JENKINS-51818](https://issues.jenkins-ci.org/browse/JENKINS-51818) -
When connecting over TCP, agents will check availability of the master's TCP Agent Listener port
* [JENKINS-51841](https://issues.jenkins-ci.org/browse/JENKINS-51841) -
Extensibility: Offer a new `Channel#readFrom(Channel, byte[] payload)` method for a standardized command deserialization from the channel
* [PR #277](https://github.com/jenkinsci/remoting/pull/277) -
API: be explicit that `ChannelBuilder#getHeaderStream()` may return null

##### 3.21

Enhancements: Jun 8, 2018 => Jenkins 2.127

* [JENKINS-51551](https://issues.jenkins-ci.org/browse/JENKINS-51551) -
Developer API: Allow creating custom `CommandTransport` implementation in external 
components.
  * Reference implementation: [Remoting Kafka Plugin](https://github.com/jenkinsci/remoting-kafka-plugin)
* [PR #274](https://github.com/jenkinsci/remoting/pull/274) -
Do not print channel close reason stack traces for non-sent request responses when
`hudson.remoting.Request` logging level is lower than `FINE`.

Fixed issues:

* [JENKINS-51223](https://issues.jenkins-ci.org/browse/JENKINS-51223) -
`no_proxy` environment variable parsing logic did not properly support
domain suffixes in fully-qualified names. 
Now it is possible to set suffixes like `.com` in `no_proxy`.
* [JENKINS-50965](https://issues.jenkins-ci.org/browse/JENKINS-50965) -
Fix malformed log message when loading of classes is forced by
the `hudson.remoting.RemoteClassLoader.force` system property.
* [PR #274](https://github.com/jenkinsci/remoting/pull/274) -
Prevent exception in IOHub when retrieving base thread name for handlers
when NIO selector is already closed (race condition).

##### 3.20

Release date: Apr 18, 2018 => Jenkins 2.118, 2.121.1 LTS

* Refresh the Code-signing certificate
* No functional changes

##### 3.19

Release date: Mar 22, 2018 => Jenkins 2.113

* [JENKINS-49618](https://issues.jenkins-ci.org/browse/JENKINS-49618) -
Display Remoting version in the agent log when starting up the agent
* [JENKINS-50237](https://issues.jenkins-ci.org/browse/JENKINS-50237) -
Include a ProxyException to responses when returning an exception to a `UserRequest`s.
  * This allows returning the exception details even if Jenkins 2.102+ refuses to deserialize the original exception
    due to the whitelist violation
  * More info: [JEP-200 announcement](https://jenkins.io/blog/2018/03/15/jep-200-lts/)

##### 3.18

Release date: Mar 9, 2018 => Jenkins 2.112

* [JENKINS-49415](https://issues.jenkins-ci.org/browse/JENKINS-49415) -
Add uncaught exception handler to the Engine's executor service 
* [JENKINS-49472](https://issues.jenkins-ci.org/browse/JENKINS-49472) -
Log channel name in StreamCorruptedExceptions
* [JENKINS-48561](https://issues.jenkins-ci.org/browse/JENKINS-48561) -
Give precedence to proxy exclusion list system property over environmental vars.
* [JENKINS-49994](https://issues.jenkins-ci.org/browse/JENKINS-49994) -
Add infrastructure for warning about remoting serialization of anonymous inner classes.
* [PR #258](https://github.com/jenkinsci/remoting/pull/258) -
Improve performance by disabling expensive diagnostics in `RemoteClassLoader`

##### 3.17

Release date: Jan 30, 2018 => Jenkins 2.106

* [JENKINS-49027](https://issues.jenkins-ci.org/browse/JENKINS-49027) -
Improve reporting of JEP-200 violations in Remoting serialization.
  * More info: [Announcement Blogpost](https://jenkins.io/blog/2018/01/13/jep-200/)
* [JENKINS-27035](https://issues.jenkins-ci.org/browse/JENKINS-27035) -
Add read/write events to [Channel.Listener](http://javadoc.jenkins.io/component/remoting/hudson/remoting/Channel.Listener.html)
to support collection of Request/Response statistics.
  * More info: [Event Listeners Documentation](/docs/logging.md#event-listeners)
* [JENKINS-45897](https://issues.jenkins-ci.org/browse/JENKINS-45897) -
Improve string representation of `Request` types to improve log messages

##### 3.16

Release date: Jan 10, 2018 => Jenkins 2.102

* [PR #208](https://github.com/jenkinsci/remoting/pull/208) -
Introduce the new `ClassFilter.setDefault` API which allows replacing the default Class Filter
  * This is a foundation work for [JEP-200](https://github.com/jenkinsci/jep/tree/master/jep/200)/[JENKINS-47736](https://issues.jenkins-ci.org/browse/JENKINS-47736), 
  which switches the default Remoting/XStream blacklist to whitelist in the Jenkins core
  * Other Remoting API users are adviced to do the same
* [PR #208](https://github.com/jenkinsci/remoting/pull/208) -
Update the blacklist in the default Class Filter to align it with the Jenkins core. 
New entries:
  * `^java[.]lang[.]reflect[.]Method$`
  * `^net[.]sf[.]json[.].*`
  * `^java[.]security[.]SignedObject$` ([SECURITY-429 advisory](https://jenkins.io/security/advisory/2017-04-26/#cli-unauthenticated-remote-code-execution))
* [JENKINS-48686](https://issues.jenkins-ci.org/browse/JENKINS-48686) -
Replace the _slave_ term by _agent_ in logging, UI and Javadocs

##### 3.15

Release date: Dec 22, 2017 => Jenkins 2.98

Enhancements:

* [JENKINS-48133](https://issues.jenkins-ci.org/browse/JENKINS-48133) -
Channel exceptions now record the channel name and other information when possible
* [PR #210](https://github.com/jenkinsci/remoting/pull/210) - 
Allow disabling HTTPs certificate validation of JNLP endpoint when starting Remoting
  * **WARNING**: This option undermines the HTTPs security and opens the connection to MiTM attacks.
    Use it at your own risk
* [JENKINS-48055](https://issues.jenkins-ci.org/browse/JENKINS-48055) -
API: Introduce new `getChannelOrFail()` and `getOpenChannelOrFail()` methods in
[hudson.remoting.Callable](http://javadoc.jenkins.io/component/remoting/hudson/remoting/Callable.html).
* [JENKINS-37566](https://issues.jenkins-ci.org/browse/JENKINS-37566) -
API: `Channel#current()` now explicitly requires checking for `null`.
* [PR #227](https://github.com/jenkinsci/remoting/pull/227) - 
API: Deprecate and restrict the obsolete [JNLP3 protocol](docs/protocols.md) utility classes

Fixed issues:

* [JENKINS-48309](https://issues.jenkins-ci.org/browse/JENKINS-48309) -
Prevent timeout in `AsyncFutureImpl#get(timeout)` when a spurious thread wakeup happens 
before the timeout expiration.
  * The issue also impacts [FutureImpl](http://javadoc.jenkins.io/hudson/model/queue/FutureImpl.html) in the Jenkins core
* [JENKINS-47965](https://issues.jenkins-ci.org/browse/JENKINS-47965) -
Prevent infinite hanging of JNLP4 `IOHub` selector threads when `IOHub` does not get closed properly 
  * Affected [protocols](docs/protocols.md): JNLP4 only
* [JENKINS-48130](https://issues.jenkins-ci.org/browse/JENKINS-48130) -
Prevent fatal failure of `NIOChannelHub` when an underlying executor service rejects a task execution.
After the change such failure terminates only a single channel
  * Affected [protocols](docs/protocols.md): JNLP, JNLP2, CLI and CLI2. JNLP4 is not affected
  * The change also improves diagnostics of `RejectedExecutionException` in other execution services
* [JENKINS-37670](https://issues.jenkins-ci.org/browse/JENKINS-37670) -
Throw the standard `UnsupportedClassVersionError` in `RemoteClassLoader` 
when the bytecode is not supported.
* [JENKINS-37566](https://issues.jenkins-ci.org/browse/JENKINS-37566) - 
Clean up all issues reported by FindBugs. Notable issues:
  * Prevent infinite hanging of `Channel#waitForProperty()` when the channel hangs in the closing state.
  * Prevent `NullPointerException`s in `Command#createdAt` handling logic and API
  * Prevent serialization of `Callable`s in `NioChannelHub` selectors (JNLP1 and JNLP2 protocols)
* [JENKINS-46724](https://issues.jenkins-ci.org/browse/JENKINS-46724) - 
Remove obsolete reflection calls in `RemoteClassloader` and `Launcher#checkTty()`
* [PR #234](https://github.com/jenkinsci/remoting/pull/234) - 
`hudson.remoting.Capability` preamble initialization cannot longer throw exceptions

Build flow:

* [JENKINS-38696](https://issues.jenkins-ci.org/browse/JENKINS-38696) -
Fix Windows tests and enable them in the pull request builder
* [JENKINS-37566](https://issues.jenkins-ci.org/browse/JENKINS-37566) -
Enforce FindBugs in the pull request builder

##### 3.14

Release date: Nov 10, 2017 => [Jenkins 2.90](https://jenkins.io/changelog/#v2.90)

Fixed issues:

* [JENKINS-45294](https://issues.jenkins-ci.org/browse/JENKINS-45294) -
User-space RMI calls (including Jenkins core ones) will be rejected when the channel is being closed 
(similar to [JENKINS-45023](https://issues.jenkins-ci.org/browse/JENKINS-45023) in 3.11).
It prevents channel hanging in edge cases.
* [JENKINS-47425](https://issues.jenkins-ci.org/browse/JENKINS-47425) - 
Do not print full stack traces on network connection errors.
* [JENKINS-37566](https://issues.jenkins-ci.org/browse/JENKINS-37566) - 
Cleanup a number of issues reported by FindBugs.
Notable ones: Unchecked file operations, improper synchronization.
* [JENKINS-47901](https://issues.jenkins-ci.org/browse/JENKINS-47901) -
Prevent uncaught `InvalidPathException` for file operations if and invalid path is passed from command line or API.
* [JENKINS-47942](https://issues.jenkins-ci.org/browse/JENKINS-47942) -
Performance: Reduce scope of Channel instance locks by property management.

Build flow:

* [PR #207](https://github.com/jenkinsci/remoting/pull/207) -
Jacoco does not longer run by default in the build, `jacoco` profile should be used.
* [PR #207](https://github.com/jenkinsci/remoting/pull/207) -
Update Jacoco version to make the reports compatible with Jenkins [Jacoco Plugin](https://plugins.jenkins.io/jacoco).

##### 3.13 => [Jenkins 2.85](https://jenkins.io/changelog/#v2.85)

Release date: Oct 05, 2017

Improvements:

* [JENKINS-38711](https://issues.jenkins-ci.org/browse/JENKINS-38711) -
Add uncaught exception handling logic to remoting threads.
Threads now either have failover or proper termination.

Fixed issues:

* [JENKINS-47132](https://issues.jenkins-ci.org/browse/JENKINS-47132) -
When an agent is waiting for master to be ready, 
the port was not filled in the `Master isnt ready to talk to us on {0}. Will retry again` log message.

##### 3.12 => [Jenkins 2.79](https://jenkins.io/changelog/#v2.79)

Release date: Sep 14, 2017 => [Jenkins 2.79](https://jenkins.io/changelog/#v2.79)

* [JENKINS-45755](https://issues.jenkins-ci.org/browse/JENKINS-45755) -
Prevent channel initialization failure when JAR Cache directory is not writable and the channel does not need this cache
(regression in 3.10).
  * This issue causes a regression in Jenkins LTS 2.73.1
  See the [upgrade guide](https://jenkins.io/doc/upgrade-guide/2.73/#known-issue-agent-connection-failures-involving-jenkins-masters-with-undefined-or-non-writable-home-directory) for more info.
* [JENKINS-46140](https://issues.jenkins-ci.org/browse/JENKINS-46140) -
Improve representation of remote operation exceptions in logs.

##### 3.11

Release date: Aug 18, 2017 => [Jenkins 2.76](https://jenkins.io/changelog/#v2.76)

:exclamation: **Warning!** Starting from this release, Jenkins Remoting requires Java 8 to run.
In edge cases it may require manual actions during the upgrade.
See compatibility notes in [this blogpost](https://jenkins.io/blog/2017/08/11/remoting-update/).
Old `JNLP-connect` and `JNLP2-connect` agent protocols are now officially deprecated.
There are also changes in the JAR signing.

Enhancements:

* [JENKINS-43985](https://issues.jenkins-ci.org/browse/JENKINS-43985) -
Require Java 8 in the Remoting executable.
* [JENKINS-45841](https://issues.jenkins-ci.org/browse/JENKINS-45841) -
Deprecate `JNLP-connect` and `JNLP2-connect` [agent protocols](docs/protocols.md).
* [JENKINS-45522](https://issues.jenkins-ci.org/browse/JENKINS-45522) -
Introduce public API for adding classes to the default blacklist in remote operations.
* [JENKINS-46259](https://issues.jenkins-ci.org/browse/JENKINS-46259) -
 Log all linkage errors when executing `UserRequest`s (generic remote operations started from API).
* [JENKINS-45233](https://issues.jenkins-ci.org/browse/JENKINS-45233) -
 Log errors when Response message cannot be delivered due to the closed channel.
* [PR #172](https://github.com/jenkinsci/remoting/pull/172) -
Improve handling of input/output streams in `hudson.remoting.Checksum` to suppress static analysis warnings.

Fixed issues:

* [JENKINS-45023](https://issues.jenkins-ci.org/browse/JENKINS-45023) -
Prevent execution of `UserRequest`s when the channel is closed or being closed.
It prevents hanging of the channel in some cases.

Build Flow:

* [JENKINS-37567](https://issues.jenkins-ci.org/browse/JENKINS-37567) -
Code signing: [@oleg-nenashev](https://github.com/oleg-nenashev) will be releasing Remoting JARs signed with his certificate.
  * The certificate should be trusted by all Java versions by default.
  Please create an issue to Remoting if it's not.
* [PR #173](https://github.com/jenkinsci/remoting/pull/173) -
Remoting build was failing when user name contained metacharacters.
* [PR #190](https://github.com/jenkinsci/remoting/pull/190) -
Enforce code signing verification when building Remoting with the `release` profile.

##### 3.10.2

Release date: Oct 05, 2017

:exclamation: This is a backport release for Jenkins 2.73.2, 
which integrates changes from 3.11 and 3.12.

* [JENKINS-45755](https://issues.jenkins-ci.org/browse/JENKINS-45755) -
Prevent channel initialization failure when JAR Cache directory is not writable and the channel does not need this cache
(regression in 3.10).
* [JENKINS-45023](https://issues.jenkins-ci.org/browse/JENKINS-45023) -
Prevent execution of `UserRequest`s when the channel is closed or being closed.
It prevents hanging of the channel in some cases.
* [JENKINS-46259](https://issues.jenkins-ci.org/browse/JENKINS-46259) -
 Log all linkage errors when executing `UserRequest`s (generic remote operations started from API).
* [JENKINS-45233](https://issues.jenkins-ci.org/browse/JENKINS-45233) -
 Log errors when Response message cannot be delivered due to the closed channel.

Build Flow:

* [JENKINS-37567](https://issues.jenkins-ci.org/browse/JENKINS-37567) -
Code signing: [@oleg-nenashev](https://github.com/oleg-nenashev) will be releasing Remoting JARs signed with his certificate 
for the next 3.10.x releases.

##### 3.10.1

This release is burned.

##### 3.10

Release date: (Jun 26, 2017) => Jenkins 2.68

Enhancements:

* [JENKINS-18578](https://issues.jenkins-ci.org/browse/JENKINS-18578) -
Do not use the old cache when starting agents from CLI with work directory.
  * It is a follow-up fix to the `3.8` version.
* [PR #165](https://github.com/jenkinsci/remoting/pull/165) -
Suppress `ClosedSelectorException` when it happens in `IOHub`'s Selector keys.
  * This issue impacts Jenkins test suites, there should be no user-visible impact.

Fixed issues:

* [PR #169](https://github.com/jenkinsci/remoting/pull/169) -
Prevent `NullPointerException` in ResourceImageBoth if cannot initialize JAR retrieval.
* [PR #170](https://github.com/jenkinsci/remoting/pull/170) -
Prevent `NullPointerException` in Remote ClassLoader when not all sources can be converted to URLs.

##### 3.9

Release date: (May 19, 2017) => Jenkins 2.68

Fixed issues:

* [JENKINS-44290](https://issues.jenkins-ci.org/browse/JENKINS-44290) -
Prevent crash when starting Remoting agents in the default mode (regression in 3.8).

##### 3.8

Release date: (May 12, 2017) => Jenkins 2.68

This version of Remoting introduces support for [Work Directories](./docs/workDir.md) ([JENKINS-39370](https://issues.jenkins-ci.org/browse/JENKINS-39370)).
This feature has been implemented as a part of the [JENKINS-44108](https://issues.jenkins-ci.org/browse/JENKINS-44108) EPIC, which is devoted to better diagnosability of Jenkins agents.

Work Directory mode is disabled by default.
It can be enabled via the `-workDir` argument in the command line.
Once enabled, the following issues are addressed:

* [JENKINS-39369](https://issues.jenkins-ci.org/browse/JENKINS-39369) -
Write Remoting agent logs to the disk by default.
* [JENKINS-18578](https://issues.jenkins-ci.org/browse/JENKINS-18578) -
Change the default JAR Cache location from `~/.jenkins/cache/jars` to `${WORK_DIR}/remoting/jarCache`.
* [JENKINS-39130](https://issues.jenkins-ci.org/browse/JENKINS-39130) -
If the work directory is not writable, fail the agent initialization.
* [JENKINS-39130](https://issues.jenkins-ci.org/browse/JENKINS-39130) -
Add the `-failIfWorkDirIsMissing` flag to CLI.
It may be useful to prevent startup in the case if the shared directory is not mounted.

See the [Work Directories page](./docs/workDir.md) for more information and migration guidelines.

Other changes:

* [PR #129](https://github.com/jenkinsci/remoting/pull/129) -
Allow configuring `java.util.logging` settings via a property file (`-loggingConfig` or system property).
See the [Logging page](./docs/logging.md) for more details.
* [PR #157](https://github.com/jenkinsci/remoting/pull/157) -
Cleanup FindBugs-reported issues in ExportTable implementation (regression in 2.40).
* [PR #153](https://github.com/jenkinsci/remoting/pull/153) -
Prevent `NullPointerException` in `hudson.remoting.Channel.Ref()` when creating a reference to a `null` channel.
* [JENKINS-5374](https://issues.jenkins-ci.org/browse/JENKINS-5374) - 
Plrevent `NullPointerException` when executing a `UserRequest` constructed with a null classloader reference.

##### 3.7

Release date: (Mar 05, 2017) => Jenkins 2.50, 2.46.1 LTS

Fixed issues:

* [JENKINS-42371](https://issues.jenkins-ci.org/browse/JENKINS-42371) -
Properly close the `URLConnection` when parsing connection arguments from the JNLP file.
It was causing a descriptor leak in the case of multiple connection attempts.
([PR #152](https://github.com/jenkinsci/remoting/pull/152))

##### 3.6

The release has been skipped due to the release process issue.

##### 3.5

Release date: (Feb 16, 2017) => Jenkins 2.47

Fixed issues:

* [JENKINS-40710](https://issues.jenkins-ci.org/browse/JENKINS-40710) -
Match headers case-insensitively in `JnlpAgentEndpointResolver` in order to be compliant with HTTP2 lower-case headers.
([PR #139](https://github.com/jenkinsci/remoting/pull/139), [PR #140](https://github.com/jenkinsci/remoting/pull/140))
* [JENKINS-41513](https://issues.jenkins-ci.org/browse/JENKINS-41513) -
Prevent `NullPointerException` in `JnlpAgentEndpointResolver` when receiving a header with `null` name.
([PR #140](https://github.com/jenkinsci/remoting/pull/140))
* [JENKINS-41852](https://issues.jenkins-ci.org/browse/JENKINS-41852) -
Fix exported object pinning logic to prevent release due to the integer overflow.
([PR #148](https://github.com/jenkinsci/remoting/pull/148))

Improvements:

* [JENKINS-41730](https://issues.jenkins-ci.org/browse/JENKINS-41730) -
 Add the new `org.jenkinsci.remoting.engine.JnlpAgentEndpointResolver.ignoreJenkinsAgentProtocolsHeader` property, which allows specifying a custom list of supported protocols instead of the one returned by the Jenkins master.
([PR #146](https://github.com/jenkinsci/remoting/pull/146))
* Print the Filesystem Jar Cache directory location in the error message when this cache directory is not writable.
([PR #143](https://github.com/jenkinsci/remoting/pull/143))
* Replace `MimicException` with the older `ProxyException` when serializing non-serializable exceptions thrown by the remote code.
([PR #141](https://github.com/jenkinsci/remoting/pull/141))
* Use OID of the `ClassLoaderProxy` in error message when the proxy cannot be located in the export table.
([PR #147](https://github.com/jenkinsci/remoting/pull/147))

##### 3.4.1

Release date: (Feb 01, 2017) => Jenkins 2.44, 2.32.2 LTS

Fixed issues:

* [SECURITY-383](https://wiki.jenkins-ci.org/display/SECURITY/Jenkins+Security+Advisory+2017-02-01) -
Blacklist classes vulnerable to a remote code execution involving the deserialization of various types in
`javax.imageio.*`, `java.util.ServiceLoader`, and `java.net.URLClassLoader`.

##### 3.4

Release date: (Dec 24, 2016) => Jenkins 2.39

Fixed issues:

* [JENKINS-39835](https://issues.jenkins-ci.org/browse/JENKINS-39835) -
Be extra defensive about unhandled `Errors` and `Exception`s.
In the case of such issues remoting tries to properly terminate the connection instead of just leaving the hanging channel.
([PR #133](https://github.com/jenkinsci/remoting/pull/133))

##### 3.3

Release date: (Dec 16, 2016) => Jenkins 2.37

Fixed issues:

* [JENKINS-25218](https://issues.jenkins-ci.org/browse/JENKINS-25218) -
Hardening of FifoBuffer operation logic.
The change improves the original fix in `remoting-2.54`.
([PR #100](https://github.com/jenkinsci/remoting/pull/100))
* [JENKINS-39547](https://issues.jenkins-ci.org/browse/JENKINS-39547) -
Corrupt agent JAR cache causes agents to malfunction.
([PR #130](https://github.com/jenkinsci/remoting/pull/130))

Improvements:

* [JENKINS-40491](https://issues.jenkins-ci.org/browse/JENKINS-40491) -
Improve diagnostics of the preliminary FifoBuffer termination.
([PR #138](https://github.com/jenkinsci/remoting/pull/138))
* ProxyException now retains any suppressed exceptions.
([PR #136](https://github.com/jenkinsci/remoting/pull/136))

##### 3.2

Release date: (Nov 13, 2016) => Jenkins 2.32

* [SECURITY-360](https://wiki.jenkins-ci.org/display/SECURITY/Jenkins+Security+Advisory+2016-11-16) -
Blacklist serialization of particular classes to close the Remote code execution vulnerability.
([Commit #b7ac85ed4ae41482d9754a881df91d2eb86d047d](https://github.com/jenkinsci/remoting/commit/b7ac85ed4ae41482d9754a881df91d2eb86d047d))

##### 3.1

Release date: (Nov 10, 2016) => Jenkins 2.31

Bugfixes:

* [JENKINS-39596](https://issues.jenkins-ci.org/browse/JENKINS-39596) -
Jenkins URL in `hudson.remoting.Engine` was always `null` since `3.0`.
It was causing connection failures of Jenkins JNLP agents when using Java Web Start.
([PR #131](https://github.com/jenkinsci/remoting/pull/131))
* [JENKINS-39617](https://issues.jenkins-ci.org/browse/JENKINS-39617) -
`hudson.remoting.Engine` was failing to establish connection if one of the URLs parameter in parameters was malformed.
([PR #131](https://github.com/jenkinsci/remoting/pull/131))

Improvements:

* [JENKINS-39150](https://issues.jenkins-ci.org/browse/JENKINS-39150) -
Add logic for dumping diagnostics across all the channels.
([PR #122](https://github.com/jenkinsci/remoting/pull/122), [PR #125](https://github.com/jenkinsci/remoting/pull/125))
* [JENKINS-39543](https://issues.jenkins-ci.org/browse/JENKINS-39543) -
Improve the caller/callee correlation diagnostics in thread dumps.
([PR #119](https://github.com/jenkinsci/remoting/pull/119))
* [JENKINS-39290](https://issues.jenkins-ci.org/browse/JENKINS-39290) -
Add the `org.jenkinsci.remoting.nio.NioChannelHub.disabled` flag for disabling NIO (mostly for debugging purposes).
([PR #123](https://github.com/jenkinsci/remoting/pull/123))
* [JENKINS-38692](https://issues.jenkins-ci.org/browse/JENKINS-38692) -
Add extra logging to help diagnosing `IOHub` Thread spikes.
([PR #116](https://github.com/jenkinsci/remoting/pull/116))
* [JENKINS-39289](https://issues.jenkins-ci.org/browse/JENKINS-39289) -
 When a proxy fails, report what caused the channel to go down.
([PR #128](https://github.com/jenkinsci/remoting/pull/128))

##### 3.0

Release date: (Oct 13, 2016) => Jenkins 2.27

NOTE: This is a new major release of remoting, which is not fully compatible with <code>remoting 2.x</code>.
See (see [Remoting 3 Compatibility Notes](docs/remoting-3-compatibility.md)) for more info.

Enhancements:

* [JENKINS-36871](https://issues.jenkins-ci.org/browse/JENKINS-36871) -
New <code>JNLP4-connect</code> protocol, which improves performance and stability compared to the JNLP3 protocol.
(https://github.com/jenkinsci/remoting/pull/92)
* [JENKINS-37565](https://issues.jenkins-ci.org/browse/JENKINS-37565) -
Update the required Java version to Java 7, Java 6 is not supported anymore.
(https://github.com/jenkinsci/remoting/pull/103)

##### 2.62.2

Release date: (Oct 7, 2016) => Jenkins 2.26

This is the last release note for Remoting `2.x` in this changelog.
See [Remoting 2.x Changelog](CHANGELOG-2.x.md) for further releases.

Fixed issues:

* [JENKINS-38539](https://issues.jenkins-ci.org/browse/JENKINS-38539) -
Stability: Turn on SO_KEEPALIVE and provide CLI option to turn it off again.
(https://github.com/jenkinsci/remoting/pull/110)
* [JENKINS-37539](https://issues.jenkins-ci.org/browse/JENKINS-37539) -
Prevent <code>NullPointerException</code> in <code>Engine#connect()</code> when host or port parameters are <code>null</code> or empty.
(https://github.com/jenkinsci/remoting/pull/101)
* [CID-152201] -
Fix resource leak in <code>remoting.jnlp.Main</code>.
(https://github.com/jenkinsci/remoting/pull/102)
* [CID-152200,CID-152202] -
Resource leak in Encryption Cipher I/O streams on exceptional paths.
(https://github.com/jenkinsci/remoting/pull/104)

##### 2.62

Release date: (Aug 14, 2016) => Jenkins 2.17, 2.19.1 LTS

Fixed issues:
* [JENKINS-22853](https://issues.jenkins-ci.org/browse/JENKINS-22853) -
Be robust against the delayed EOF command when unexporting input and output streams.
(https://github.com/jenkinsci/remoting/pull/97)
* Fixed ~20 minor issues reported by FindBugs.
More fixes to be delivered in future versions.
(https://github.com/jenkinsci/remoting/pull/96)

Enhancements:
* [JENKINS-37218](https://issues.jenkins-ci.org/browse/JENKINS-37218) -
Performance: <code>ClassFilter</code> does not use Regular Expressions anymore to match <code>String.startsWith</code> patterns.
(https://github.com/jenkinsci/remoting/pull/92)
* [JENKINS-37031](https://issues.jenkins-ci.org/browse/JENKINS-37031)
<code>TcpSlaveAgentListener</code> now publishes a list of supported agent protocols to speed up connection setup.
(https://github.com/jenkinsci/remoting/pull/93)

##### 2.61

Release date: (Aug 5, 2016) => Jenkins 2.17, 2.19.1 LTS

Fixed issues:
* [JENKINS-37140](https://issues.jenkins-ci.org/browse/JENKINS-37140) -
JNLP Agent connection issue with *JNLP3-connect* protocol when the generated encrypted cookie contains a newline symbols.
(https://github.com/jenkinsci/remoting/pull/95)
* [JENKINS-36991](https://issues.jenkins-ci.org/browse/JENKINS-36991) -
Unable to load class when remote classloader gets interrupted.
(https://github.com/jenkinsci/remoting/pull/94)

Enhancements:
* [JENKINS-36947](https://issues.jenkins-ci.org/browse/JENKINS-36947) -
Improve diagnostics for Jar Cache write errors.
(https://github.com/jenkinsci/remoting/pull/91)

##### 2.60

Release date: (June 10, 2016) => Jenkins 2.9, 2.7.2

Fixed issues:
* [JENKINS-22722](https://issues.jenkins-ci.org/browse/JENKINS-22722) -
Make the channel reader tolerant against Socket timeouts.
(https://github.com/jenkinsci/remoting/pull/86)
* [JENKINS-32326](https://issues.jenkins-ci.org/browse/JENKINS-32326) -
Support no_proxy environment variable.
(https://github.com/jenkinsci/remoting/pull/84)
* [JENKINS-35190](https://issues.jenkins-ci.org/browse/JENKINS-35190)  -
Do not invoke PingFailureAnalyzer for agent=>master ping failures.
(https://github.com/jenkinsci/remoting/pull/85)
* [JENKINS-31256](https://issues.jenkins-ci.org/browse/JENKINS-31256) -
 <code>hudson.Remoting.Engine#waitForServerToBack</code> now uses credentials for connection.
(https://github.com/jenkinsci/remoting/pull/87)
* [JENKINS-35494](https://issues.jenkins-ci.org/browse/JENKINS-35494) -
Fix issues in file management in <code>hudson.remoting.Launcher</code> (main executable class).
(https://github.com/jenkinsci/remoting/pull/88)

Enhancements:
* Ensure a message is logged if remoting fails to override the default <code>ClassFilter</code>.
(https://github.com/jenkinsci/remoting/pull/80)

##### 2.59

Release date: (May 13, 2016) => Jenkins 2.4, 2.7.1

Enhancements:
* [JENKINS-34819](https://issues.jenkins-ci.org/browse/JENKINS-34819) -
Allow disabling the remoting protocols individually. Works around issues like [JENKINS-34121](https://issues.jenkins-ci.org/browse/JENKINS-34121)
(https://github.com/jenkinsci/remoting/pull/83)

##### 2.58

Release date: (May 11, 2016) => Jenkins 2.4, 2.7.1

Fixes issues:
* [JENKINS-34213](https://issues.jenkins-ci.org/browse/JENKINS-34213) -
Ensure that the unexporter cleans up whatever it can each sweep.
(https://github.com/jenkinsci/remoting/pull/81)
* [JENKINS-19445](https://issues.jenkins-ci.org/browse/JENKINS-19445) -
Force class load on UserRequest in order to prevent deadlock on windows nodes when using JNA and Subversion.
(https://github.com/jenkinsci/remoting/pull/82)

Enhancements:
* [JENKINS-34808](https://issues.jenkins-ci.org/browse/JENKINS-34808) -
Allow user to adjust socket timeout in the channel reader.
(https://github.com/jenkinsci/remoting/pull/68)
