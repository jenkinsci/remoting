Jenkins Remoting layer
====

Jenkins remoting is an executable JAR, 
which implements communication layer in [Jenkins](https://jenkins.io) automation server. 
It's being used for master <=> agent(fka "slave") and master <=> CLI communications.

In general, this library contains the bootstrap code to bridge separate JVMs into a single semi-shared space.
It includes: TCP-based communication protocols, data serialization, Java classloading.

The library is reusable outside Jenkins.

### Remoting versions

Currently there are two supported baselines of Remoting.

#### Remoting 3

Remoting 3 is a new baseline introduced in Jenkins 2.27.

Major changes:

* Java 7 is a new target JVM, the new remoting version is not guaranteed to work properly on Java 9 and versions below Java 7
* New <code>JNLP4-connect</code> protocol, 
  which improves performance and stability compared to the JNLP3 protocol

Remoting 3 does not have full binary compatibity with Remoting <code>2</code> (see [Remoting 3 Compatibility Notes](docs/remoting-3-compatibility.md)).

#### Remoting 2

Remoting 2 is a version, which was used in Jenkins till the <code>2.27</code> release. 
It is not being offered in new releases of Jenkins. 
This version is still being maintained, because it is being used in Jenkins LTS and several other projects.

Maintenance approach:

* The version will be maintained till at least May 2017
* New releases may include bugfixes, security fixes and performance enhancements
* There is no plans to introduce new features in <code>remoting-2.x</code>

Changelogs for Remoting 2.x releases are available [here](CHANGELOG-2.x.md).

### Documentation

Remoting documentation is under development.
More info will appear soon.
Feel free to contribute.

User documentation:

* [Changelog - Mainstream](CHANGELOG.md) - Changelog for the Remoting 3 and previous releases of Remoting 2
* [Changelog - 2.x](CHANGELOG-2.x.md) - Changelog for the Remoting `2.x` stabilization releases after the Remoting 3 release
* [Remoting 3 Compatibility Notes](docs/remoting-3-compatibility.md)
* [Remoting Protocols](docs/protocols.md) - Overview of protocols integrated with Jenkins
* [Remoting Configuration](docs/configuration.md) - Configuring remoting agents
* [Logging](docs/logging.md) - Logging
* [Work Directory](docs/workDir.md) - Remoting work directory (new in Remoting `TODO`)
* [Jenkins Specifics](docs/jenkins-specifics.md) - Notes on using remoting in Jenkins
* [Troubleshooting](docs/troubleshooting.md) - Investigating and solving common remoting issues

Developer documentation:

* [Contributing](CONTRIBUTING.md)
* [Channel Termination Process](docs/close.md)

### Reporting issues

Remoting library uses the [Jenkins bugtracker](https://issues.jenkins-ci.org).
Issues should be reported there in the <code>JENKINS</code> project with the <code>remoting</code> component.

See [How to report an issue](https://wiki.jenkins-ci.org/display/JENKINS/How+to+report+an+issue) for more details about Jenkins issue reporting.

### More info

* [Remoting Architecture Overview](http://hudson-ci.org/docs/HudsonArch-Remoting.pdf) 
by Winston Prakash, Oracle (the information is outdated)
* [Making your plugin behave in distributed Jenkins](https://wiki.jenkins-ci.org/display/JENKINS/Making+your+plugin+behave+in+distributed+Jenkins)
* [Writing an SCM plugin. Remoting examples](https://wiki.jenkins-ci.org/display/JENKINS/Remoting)
* [Troubleshooting remoting issues](https://wiki.jenkins-ci.org/display/JENKINS/Remoting+issue)
* [Scaling Jenkins to Hundreds of Nodes](https://www.cloudbees.com/jenkins/juc-2015/abstracts/us-west/02-01-1600) 
by Akshay Dayal, Google (remoting optimization, JNLP3)