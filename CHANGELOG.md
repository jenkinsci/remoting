Changelog
====

Below you can changelogs for the trunk version of remoting.
This file also provides links to Jenkins versions, 
which bundle the specified remoting version.
See [Jenkins changelog](https://jenkins.io/changelog/) for more details.

##### 2.60

Release date: (Coming Soon)

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

Release date: (May 13, 2016) => Jenkins 2.4

Enhancements:
* [JENKINS-34819](https://issues.jenkins-ci.org/browse/JENKINS-34819) - 
Allow disabling the remoting protocols individually. Works around issues like [JENKINS-34121](https://issues.jenkins-ci.org/browse/JENKINS-34121) 
(https://github.com/jenkinsci/remoting/pull/83)

##### 2.58

Release date: (May 11, 2016) => Jenkins 2.4

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

