Remoting versions
----

Currently there are two supported baselines of Remoting.

#### Remoting 3

Remoting 3 is a new baseline introduced in Jenkins 2.27.

Major changes:

* Java 7 is a new target JVM, the new remoting version is not guaranteed to work properly on Java 9 and versions below Java 7
* New [JNLP4-connect](protocols.md) protocol, 
  which improves performance and stability compared to the [JNLP3 protocol](protocols.md)

Remoting 3 does not have full binary compatibity with Remoting <code>2</code> (see [Remoting 3 Compatibility Notes](remoting-3-compatibility.md)).

#### Remoting 2

Remoting 2 is a deprecated version, which was used in Jenkins till the <code>2.27</code> release. 
It is not being offered in new releases of Jenkins. 
This version is still being maintained, because it is being used in Jenkins LTS and several other projects.

Maintenance approach:

* The branch reached its End of Life on _May 01, 2017_.
* Changes **may** be backported and released upon request.
See the [Contributing Page](../CONTRIBUTING.md).
* New releases may include bugfixes, security fixes and performance enhancements
* There is no plans to introduce new features in Remoting 2.x

Changelogs for Remoting 2.x releases are available [here](../CHANGELOG-2.x.md).