Changelog
====

Below you can changelogs for the trunk version of remoting.
This file also provides links to Jenkins versions, 
which bundle the specified remoting version.
See [Jenkins changelog](https://jenkins.io/changelog/) for more details.

##### 3.4.1

Release date: Feb 01, 2017 => Jenkins 2.44, 2.32.2 LTS

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
* Improve diagnostics for Jar Cache write errors.
(https://github.com/jenkinsci/remoting/pull/91)

##### 2.60

Release date: (June 10, 2016) => Jenkins 2.9, 2.7.2

Fixed issues:
* [JENKINS-22722](https://issues.jenkins-ci.org/browse/JENKINS-22722) - 
Make the channel reader tolerant against Socket timeouts. 
(https://github.com/jenkinsci/remoting/pull/80)
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

