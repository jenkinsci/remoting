Remoting protocols
====

Remoting library provides extension points, which allow implementing custom communication protocols.
For example, Jenkins project defines its own protocols for the CLI client.

This section describes only the protocols available within the remoting library.

### JNLP1-connect

* Legacy remoting protocol
* Not recommended for use since in Modern Jenkins versions

The slave sends the master the slave name it wants to register as and the computed HMAC of the slave name.
If accepted the master will reply with a confirmation response.
Then the channel gets established.

### JNLP2-connect

This is the advanced versions of the <code>JNLP1-connect</code> protocol. 
On successful connection to the master the slave will receive a cookie from the master, which the slave stores.
 
If the slave needs to reconnect it will send the same cookie as part of the new connection request. 
The master can use the cookie to determine if the incoming request is an initial connection request 
  or a reconnection and take appropriate action.
  
The protocol supports the non-blocking I/O, which improve the performance of the communication channel.

### JNLP3-connect

* Introduced in: Remoting 2.53, [JENKINS-26580](https://issues.jenkins-ci.org/browse/JENKINS-26580)
* The protocol has known stability issues and disabled by default in Jenkins
* Not recommended for use since the <code>JNLP4-connect</code> release

This protocol aims to improve security of JNLP-based slaves. 
Both the master and the slave securely authenticate each other and then setup an encrypted channel.

The protocol does not support non-blocking IO.
For each connection a new thread is being created, and it leads to the performance degradation or
  even Denial of Service on highly loaded Jenkins masters.
There are also some reported issues regarding the Remoting 3 stability on particular systems.

### JNLP4-connect

* Introduced in: Remoting 3.0, [JENKINS-36871](https://issues.jenkins-ci.org/browse/JENKINS-36871)

This protocol uses <code>SSLEngine</code> to perform a TLS upgrade of the plaintext 
  connection before any connection secrets are exchanged. 
The subsequent connection is then secured using TLS. 

Protocol uses non-blocking I/O wherever possible which removes the performance bottleneck of the <code>JNLP3-connect</code> protocol.

### JNLP4-plaintext

* Introduced in: Remoting 3.0, [JENKINS-36871](https://issues.jenkins-ci.org/browse/JENKINS-36871)
* Use with caution

The protocol is similar to <code>JNLP4-plaintext</code>, 
  but it does not setup the TLS encryption between agent and master.
In particular cases it may expose the secret data.

On the other had, the protocol allows to get extra performance in the case when the remoting layer does not need its own encryption   
  (e.g. when the connection goes within a secured VPN).

