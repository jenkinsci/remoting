Test Image for building Remoting
===

### Building Image

```shell
docker build -t onenashev/remoting-builder .
```

### Running Image

Running the build:

```shell
docker run --rm --name remoting-builder -v maven-repo:/root/.m2 -v ${REMOTING_DIR}:/root/src  onenashev/remoting-builder
```

Running particular test:

```shell
docker run --rm --name remoting-builder -v maven-repo:/root/.m2 -v /Users/nenashev/Documents/jenkins/core/remoting/:/root/src  onenashev/remoting-builder mvn clean test -Dtest=org.jenkinsci.remoting.engine.WorkDirManagerTest
```
