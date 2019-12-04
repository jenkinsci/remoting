Remoting protocols
====

Remoting library provides extension points, which allow implementing custom communication protocols.
For example, Jenkins project defines its own protocols for the CLI client.

This section describes only the protocols available within the remoting library.

## Active protocols

This section lists all actively maintained protocols offered in Remoting.
There may be other actively maintained protocols in other Jenkins and 3rd-party components.

### JNLP4-connect

* Introduced in: Remoting 3.0, [JENKINS-36871](https://issues.jenkins-ci.org/browse/JENKINS-36871)

This protocol uses the <code>SSLEngine</code> provided by the Java Cryptography Architecture 
  to perform a TLS upgrade of the plaintext connection before any connection secrets are exchanged. 
The subsequent connection is then secured using TLS. 

The encryption algorithms and cyphers used by the <code>SSLEngine</code> when using Oracle JDK 1.8 
   are described in [Java Cryptography Architecture Standard Algorithm Name Documentation for JDK 8](http://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html))
If stronger algorithms are needed (for example, AES with 256-bit keys), the [JCE Unlimited Strength Jurisdiction Policy Files](http://www.oracle.com/technetwork/java/javase/downloads/index.html)
  can be obtained on Oracle website and installed in the JDK/JRE.


Protocol uses non-blocking I/O wherever possible which removes the performance bottleneck of the <code>JNLP3-connect</code> protocol.

## Plugin protocols

### Remoting Kafka Plugin

* [Remoting Kafka Plugin](https://github.com/jenkinsci/remoting-kafka-plugin) uses Kafka as fault-tolerant communication layer to support command invocation between Jenkins master and agent.
* The plugin gets rid of current direct TCP connection between master and agent.
* More info can be found in the technical [documentation](https://github.com/jenkinsci/remoting-kafka-plugin/blob/master/docs/DOCUMENTATION.md) of the plugin.

## Test Protocols

The protocols below exist for testing purposes only.
It is **not recommended** to use them in production.

### JNLP4-plaintext

* Introduced in: Remoting 3.0, [JENKINS-36871](https://issues.jenkins-ci.org/browse/JENKINS-36871)
* For performance testing **only**, not supported for other purposes
* Cannot be used in Jenkins

This protocol was developed to allow performance comparison 
  between the original NIO engine used by <code>JNLP2-connect</code> and the new NIO engine
  used by <connect>JNLP4-connect</code>.

The protocol is similar to <code>JNLP4-connect</code>, 
  but it does not setup the TLS encryption between agent and master.
As this protocol is plaintext it is not for use outside of like for like performance testing.

