Remoting 3 Compatibility Notes
=====

This page describes compatibility of the Remoting 3 library. 
It addresses binary compatibility and also functional compatibility with old remoting and Java versions.

## Establishing connection between Remoting 2.x and 3.x

Remoting 3 library still contains <code>JNLP1-connect</code>, <code>JNLP2-connect</code> 
  and <code>JNLP3-connect</code> [protocols](protocols.md). 
When any of these protocols is enabled on both sides, 
  remoting versions will be able to establish connection between each other.
This is a **default** behavior.

If <code>JNLP4-connect</code> is the only enabled protocol on one of the sides, 
  Remoting <code>2.x</code> and <code>3.x</code> versions will be unable to establish the connection between each other.
It may happen only in the case of the custom configuration via [system properties](configuration.md).

## Java compatibility

In Remoting 3 the required Java version is updated from Java 6 to Java 7.

In **Jenkins** project:

* Nothing changes, starting from <code>1.610</code> Java 6 is not supported in Jenkins, 
  on both master and agents
* In particular cases it was possible to run Jenkins agents on Java 6 with Remoting <code>2.x</code>, 
  but this configuration was not officially supported

In other projects:

* For projects using Java 7 or 8, the upgrade is safe
* For projects using Java 6, Remoting <code>3.x</code> cannot be used there without upgrade to Java 7
  * Remoting <code>2.x</code> stable version can be temporarily used instead

## Binary compatibility

Formally Remoting <code>3.x</code> is not binary compatible with Remoting <code>2.x</code>,
  but the scope of changes is limited to a single change with a limited impact.

Protocol class hierarchy change:

* `JnlpProtocol` / `JnlpProtocol1` / `JnlpProtocol2` / `JnlpProtocol3` classes were removed.
  Their functionality has been moved to the <code>JnlpProtocolHandler</code> class and its subclasses.  
* <code>JnlpServerHandshake</code> class and all its subclasses have been removed from the library
* Several dependent internal classes have been removed or modified

The only **known** usage of the classes was in the Jenkins core,
  the incompatibility is fixed in the [pull-request #2492](https://github.com/jenkinsci/jenkins/pull/2492).

Impact of the changes on **Jenkins** plugins:

* No impact if the plugins do not implement their own Remoting protocols
* There is no such plugins in **open-source** Jenkins plugins available in the main update center or hosted on GitHub
* There may be impact on other plugin implementation

Impact of the changes on other projects:

* Protocol implementations and protocol handling logic may require an update
* [Pull request #2492](https://github.com/jenkinsci/jenkins/pull/2492) can be used as a reference
