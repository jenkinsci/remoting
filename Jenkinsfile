#!/usr/bin/env groovy

/* Only keep the 10 most recent builds. */
properties([[$class: 'BuildDiscarderProperty',
                strategy: [$class: 'LogRotator', numToKeepStr: '10']]])


/* These platforms correspond to labels in ci.jenkins.io, see:
 *  https://github.com/jenkins-infra/documentation/blob/master/ci.adoc
 */
//TODO: Enable Windows once JENKINS-38696 is fixed. No sense to spend CPU cycles before that
List platforms = ['docker']
Map branches = [:]

def doRemotingBuild(String label) {
    def isDocker = label.equals("docker")
    timestamps {
        stage('Checkout') {
            checkout scm
        }

        stage('Build') {
            withEnv(isDocker ? [] : [
                "JAVA_HOME=${tool 'jdk8'}",
                "PATH+MVN=${tool 'mvn'}/bin",
                'PATH+JDK=$JAVA_HOME/bin',
            ]) {
                timeout(30) {
                    String command = 'mvn --batch-mode clean install -Dmaven.test.failure.ignore=true'
                    if (isDocker) {
                        sh "docker run --rm -v ${workspace}:/root/src onenashev/remoting-builder ${command}"
                    }
                    else if (isUnix()) {
                        sh command
                    }
                    else {
                        bat command
                    }
                }
            }
        }

        stage('Archive') {
            /* Archive the test results */
            junit '**/target/surefire-reports/TEST-*.xml'

            /* Archive the build artifacts */
            archiveArtifacts artifacts: 'target/**/*.jar'
        }
    }
}

for (int i = 0; i < platforms.size(); ++i) {
    String label = platforms[i]

    branches[label] = {
        node(label) {
            doRemotingBuild(label)
        }
    }
}

/* Execute our platforms in parallel */
parallel(branches)
