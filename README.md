Jenkins Remoting layer
====

[![GitHub release](https://img.shields.io/github/release/jenkinsci/remoting.svg?label=changelog)](https://github.com/jenkinsci/remoting/releases/latest)
[![Join the chat at https://gitter.im/jenkinsci/remoting](https://badges.gitter.im/jenkinsci/remoting.svg)](https://gitter.im/jenkinsci/remoting?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Jenkins remoting is an executable JAR, 
which implements communication layer in [Jenkins](https://jenkins.io) automation server. 
It's being used for controller <=> agent and controller <=> CLI communications.

In general, this library contains the bootstrap code to bridge separate JVMs into a single semi-shared space.
It includes: TCP-based communication protocols, data serialization, Java classloading.

The library is reusable outside Jenkins.

### Downloads

Jenkins Remoting libraries are supplied as a part of the Jenkins distributions,
and it is recommended to use versions from there to ensure compatibility with your Jenkins instance.
You can download the `${JENKINS_URL}/jnlpJars/agent.jar` from your Jenkins server.
If you need a specific Remoting version, it can be downloaded from the Jenkins artifact repository.
Recent artifacts are available [here](https://repo.jenkins-ci.org/webapp/#/artifacts/browse/tree/General/releases/org/jenkins-ci/main/remoting).

For usage in Docker, the Jenkins project also provides official agent images which bundle Remoting:
[jenkins/agent](https://hub.docker.com/r/jenkins/agent) and [jenkins/inbound-agent](https://hub.docker.com/r/jenkins/inbound-agent).
We recommend using and extending these images if you need to run agents in Docker.

### Documentation

Remoting documentation is under development.
More info will appear soon.
Feel free to contribute.

User documentation:

* [Changelog](CHANGELOG.md) - Remoting release notes
* [Launching inbound agents](docs/inbound-agent.md) - Mechanisms and parameters for launching inbound agents
* [Remoting Protocols](docs/protocols.md) - Overview of protocols integrated with Jenkins
* [Remoting Configuration](docs/configuration.md) - Configuring remoting agents
* [Logging](docs/logging.md) - Logging
* [Work Directory](docs/workDir.md) - Remoting work directory (new in Remoting `3.8`)
* [Jenkins Specifics](docs/jenkins-specifics.md) - Notes on using remoting in Jenkins
* [Troubleshooting](docs/troubleshooting.md) - Investigating and solving common remoting issues

Previous versions:

* [Changelog - 2.x](CHANGELOG-2.x.md) - Changelog for the Remoting `2.x` stabilization releases

Developer documentation:

* [Contributing](CONTRIBUTING.md)
* [Javadoc](http://javadoc.jenkins.io/component/remoting/)
* [Channel Termination Process](docs/close.md)

### Reporting issues

Remoting library uses the [Jenkins bugtracker](https://issues.jenkins-ci.org).
Issues should be reported there in the <code>JENKINS</code> project with the <code>remoting</code> component.

See [How to report an issue](https://wiki.jenkins-ci.org/display/JENKINS/How+to+report+an+issue) for more details about Jenkins issue reporting.

### More info

* [Remoting Architecture Overview](https://github.com/hudson/www/blob/master/docs/HudsonArch-Remoting.pdf) 
by Winston Prakash, Oracle (the information is outdated)
* [Making your plugin behave in distributed Jenkins](https://wiki.jenkins-ci.org/display/JENKINS/Making+your+plugin+behave+in+distributed+Jenkins)
* [Writing an SCM plugin. Remoting examples](https://wiki.jenkins-ci.org/display/JENKINS/Remoting)
* [Troubleshooting remoting issues](https://wiki.jenkins-ci.org/display/JENKINS/Remoting+issue)
* [Scaling Jenkins to Hundreds of Nodes](https://www.youtube.com/watch?v=9-DUVroz7yk) 
by Akshay Dayal, Google (remoting optimization, JNLP3)
