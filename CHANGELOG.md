Changelog
====

Below you can changelogs for the trunk version of remoting.
This file also provides links to Jenkins versions, 
which bundle the specified remoting version.
See [Jenkins changelog](https://jenkins.io/changelog/) for more details.

##### 3.10

Release date: Jun 26, 2017

Enhancements:

* [JENKINS-18578](https://issues.jenkins-ci.org/browse/JENKINS-18578) -
Do not use the old cache when starting agents from CLI with work directory.
  * It is a follow-up fix to the `3.8` version.
* [PR #165](https://github.com/jenkinsci/remoting/pull/165) -
Suppress `ClosedSelectorException` when it happens in `IOHub`'s Selector.keys.
  * This issue impacts Jenkins test suites, there should be no user-visible impact.
  
Fixed issues:

* [PR #169](https://github.com/jenkinsci/remoting/pull/169) - 
Prevent `NullPointerException` in ResourceImageBoth if cannot initialize JAR retrieval.
* [PR #170](https://github.com/jenkinsci/remoting/pull/170) - 
Prevent `NullPointerException` in Remote ClassLoader when not all sources can be converted to URLs.

##### 3.9

Release date: (May 19, 2017)

Fixed issues:

* [JENKINS-44290](https://issues.jenkins-ci.org/browse/JENKINS-44290) -
Prevent crash when starting Remoting agents in the default mode (regression in 3.8).

##### 3.8

Release date: (May 12, 2017)

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

* [JENKINS-37567](https://issues.jenkins-ci.org/browse/JENKINS-37567) -
Starting from the `3.8` release, [@oleg-nenashev](https://github.com/oleg-nenashev) will be releasing Remoting JARs signed with his certificate.
  * The certificate should be trusted by all Java versions by default. 
  Please create an issue to Remoting if it's not. 
* [PR #129](https://github.com/jenkinsci/remoting/pull/129) -
Allow configuring `java.util.logging` settings via a property file (`-loggingConfig` or system property). 
See the [Logging page](./docs/logging.md) for more details.
* [PR #157](https://github.com/jenkinsci/remoting/pull/157) - 
Cleanup FindBugs-reported issues in ExportTable implementation (regression in 2.40).
* [PR #153](https://github.com/jenkinsci/remoting/pull/153) -
Prevent `NullPointerException` in `hudson.remoting.Channel.Ref()` when creating a reference to a `null` channel.

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
JNLP Slave connection issue with *JNLP3-connect* protocol when the generated encrypted cookie contains a newline symbols.
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

