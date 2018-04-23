#!/usr/bin/env bash
JENKINS_HOME=$(pwd)/work java -jar target/custom-war-packager-maven-plugin/output/target/jenkins-remoting-it-1.0-remoting-it-SNAPSHOT.war \
    --httpPort=8080 --prefix=/jenkins
