# Sample Image for building Docker on Jenkins.io
###
# Custom agent build:
# Image name: onenashev/demo-jenkins-maven-builder
###
FROM maven:3.5.0-jdk-8
MAINTAINER Oleg Nenashev <o.v.nenashev@gmail.com>

LABEL Description="This is an image for building Remoting in Docker" Vendor="Oleg Nenashev" Version="0.1"

#TODO: Consider moving image to the "jenkins" user instead of root
VOLUME /root/.m2
VOLUME /root/src
WORKDIR /root/src
