#!/usr/bin/env groovy

properties([[$class: 'BuildDiscarderProperty',
                strategy: [$class: 'LogRotator', numToKeepStr: '10']]])


/* These platforms correspond to labels in ci.jenkins.io, see:
 *  https://github.com/jenkins-infra/documentation/blob/master/ci.adoc
 */
parallel linux: {
    node('maven') {
        stage('Checkout') {
            checkout scm
        }
        stage('Build') {
            timeout(30) {
                infra.runMaven(['-Dset.changelist', 'clean', 'install', '-Dmaven.test.failure.ignore'])
            }
        }
        stage('Archive') {
            junit '**/target/surefire-reports/TEST-*.xml'
            findbugs pattern: '**/target/findbugsXml.xml'
            infra.prepareToPublishIncrementals()
        }
    }
}, windows: {
    node('windows') {
        stage('Checkout') {
            checkout scm
        }
        stage('Build') {
            timeout(30) {
                infra.runMaven(['clean verify -Dmaven.test.failure.ignore'])
            }
        }
        stage('Archive') {
            junit '**/target/surefire-reports/TEST-*.xml'
        }
    }
}, failFast: true

infra.maybePublishIncrementals()
