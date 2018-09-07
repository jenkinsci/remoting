#
# This Dockerfile builds Experimental Remoting image for jenkins4eval/remoting
# This image is similar to jenkins/slave
#
FROM maven:3.5.4-jdk-8 as builder

COPY src/ /remoting/src
COPY pom.xml /remoting/pom.xml
COPY LICENSE.md /remoting/LICENSE.md

WORKDIR /remoting/
# Tests are skipped, because some of them fail when running as root
# The directory has been initialized, but it should fail since the target dir is NOT_WRITABLE
RUN mvn clean install --batch-mode -DskipTests

FROM openjdk:8-jdk
MAINTAINER Oleg Nenashev <o.v.nenashev@gmail.com>

ARG user=jenkins
ARG group=jenkins
ARG uid=10000
ARG gid=10000

ENV HOME /home/${user}
RUN groupadd -g ${gid} ${group}
RUN useradd -c "Jenkins user" -d $HOME -u ${uid} -g ${gid} -m ${user}
LABEL Description="This is a base image, which provides the Jenkins agent executable (slave.jar)" Vendor="Jenkins project" Version="3.23"

ARG VERSION=3.23
ARG AGENT_WORKDIR=/home/${user}/agent
COPY --from=builder /remoting/target/remoting-*-SNAPSHOT.jar /usr/share/jenkins/slave.jar
RUN chmod 755 /usr/share/jenkins && chmod 644 /usr/share/jenkins/slave.jar

USER ${user}
ENV AGENT_WORKDIR=${AGENT_WORKDIR}
RUN mkdir /home/${user}/.jenkins && mkdir -p ${AGENT_WORKDIR}

VOLUME /home/${user}/.jenkins
VOLUME ${AGENT_WORKDIR}
WORKDIR /home/${user}
