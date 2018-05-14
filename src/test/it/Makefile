# Just a Makefile for manual testing
.PHONY: all

ARTIFACT_ID = jenkins-remoting-it
VERSION = 2.107.3-remoting-it-SNAPSHOT
# https://github.com/jenkinsci/custom-war-packager/pull/28
CWP_VERSION = 0.1-alpha-6-20180514.155901-2

all: clean build

clean:
	rm -rf tmp

build: tmp/output/target/jenkins-war-${VERSION}.war

tmp/output/target/jenkins-war-${VERSION}.war:
	# TODO: https://github.com/jenkinsci/custom-war-packager/pull/26
	mvn io.jenkins.tools.custom-war-packager:custom-war-packager-maven-plugin:${CWP_VERSION}:build -DconfigFile=essentials.yml -Dversion=${VERSION}

run: tmp/output/target/jenkins-war-${VERSION}.war
	JENKINS_HOME=work java -jar tmp/output/target/${ARTIFACT_ID}-${VERSION}.war \
        --httpPort=8080 --prefix=/jenkins

pct: tmp/output/target/jenkins-war-${VERSION}.war
	docker run --rm -v maven-repo:/root/.m2 -v $(shell pwd)/out:/pct/out \
		-v $(shell pwd)/tmp/output/target/${ARTIFACT_ID}-${VERSION}.war:/pct/jenkins.war:ro \
		-e ARTIFACT_ID=ssh-slaves \
		-e INSTALL_BUNDLED_SNAPSHOTS=true \
		jenkins/pct
