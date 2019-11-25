#!/usr/bin/env groovy

properties([[$class: 'BuildDiscarderProperty',
                strategy: [$class: 'LogRotator', numToKeepStr: '10']]])


/* These platforms correspond to labels in ci.jenkins.io, see:
 *  https://github.com/jenkins-infra/documentation/blob/master/ci.adoc
 */
parallel(['maven', 'maven-windows'].collectEntries {label -> [label, {
    node(label) {
        stage('Checkout') {
            checkout scm
        }
        stage('Build') {
            timeout(30) {
                String command = "mvn -B -ntp -Dset.changelist clean install -Dmaven.test.failure.ignore ${infra.isRunningOnJenkinsInfra() ? '-s settings-azure.xml' : ''} -e"
                if (isUnix()) {
                    sh command
                } else {
                    bat command
                }
            }
        }
        stage('Archive') {
            junit '**/target/surefire-reports/TEST-*.xml'
            if (label == 'maven') {
                findbugs pattern: '**/target/findbugsXml.xml'
                infra.prepareToPublishIncrementals()
            }
        }
    }
}]})

infra.maybePublishIncrementals()
