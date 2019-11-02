Remoting 2.x Changelog
====

:exclamation: Below you can see changelogs for the **obsolete** Remoting <code>2.x</code> baseline.
This version only contains bugfixes and performance improvements.
Current mainline is Remoting <code>3.x</code>, changelogs are available [here](CHANGELOG.md).
There is no plan to release new versions of Remoting 2.x.

##### 2.62.6

Release date: Jun 26, 2017

Fixed issues:

* [JENKINS-41852](https://issues.jenkins-ci.org/browse/JENKINS-41852) -
Fix exported object pinning logic to prevent release due to the integer overflow.
([PR #148](https://github.com/jenkinsci/remoting/pull/148))

##### 2.62.5

Release date: Feb 01, 2017

Fixed issues:

* [SECURITY-383](https://wiki.jenkins-ci.org/display/SECURITY/Jenkins+Security+Advisory+2017-02-01) -
Blacklist classes vulnerable to a remote code execution involving the deserialization of various types in
`javax.imageio.*`, `java.util.ServiceLoader`, and `java.net.URLClassLoader`.

##### 2.62.4

Release date: Nov 21, 2016

Fixed issues:

* [JENKINS-25218](https://issues.jenkins-ci.org/browse/JENKINS-25218) -
Hardening of FifoBuffer operation logic. The change adds additional minor fixes to the original fix in `remoting-2.54`.
([PR #100](https://github.com/jenkinsci/remoting/pull/100))

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

##### 2.62.3

Release date: (Nov 13, 2016) => Jenkins 2.19.3 LTS

* [SECURITY-360](https://wiki.jenkins-ci.org/display/SECURITY/Jenkins+Security+Advisory+2016-11-16) -
Blacklist serialization of particular classes to close the Remote code execution vulnerability.
([Commit #b7ac85ed4ae41482d9754a881df91d2eb86d047d](https://github.com/jenkinsci/remoting/commit/b7ac85ed4ae41482d9754a881df91d2eb86d047d))

##### 2.62.2

Release date: (Oct 7, 2016) => Jenkins 2.19.3 LTS

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
