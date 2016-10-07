Changelog
====

Below you can changelogs for the trunk version of remoting.
This file also provides links to Jenkins versions, 
which bundle the specified remoting version.
See [Jenkins changelog](https://jenkins.io/changelog/) for more details.

##### 2.62.1

Release date: (Oct 7, 2016) => TBD

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

