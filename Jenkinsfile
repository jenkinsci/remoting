#!/usr/bin/env groovy

/* Only keep the 10 most recent builds. */
properties([[$class: 'BuildDiscarderProperty',
                strategy: [$class: 'LogRotator', numToKeepStr: '10']]])


node('java') {
    timestamps {
        stage('Checkout') {
            checkout scm
        }

        stage('Build') {
            withEnv([
                "JAVA_HOME=${tool 'jdk8'}",
                "PATH+MVN=${tool 'mvn'}/bin",
                'PATH+JDK=$JAVA_HOME/bin',
            ]) {
                timeout(30) {
                    sh 'mvn clean install -Dmaven.test.failure.ignore=true'
                }
            }
        }

        stage('Archive') {
            /* Archive the test results */
            junit '**/target/surefire-reports/TEST-*.xml'

            /* Archive the build artifacts */
            archiveArtifacts artifacts: 'target/*.hpi,target/*.jpi'
        }
    }
}
