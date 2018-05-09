#!/usr/bin/env groovy

// TODO: restore original tests (which just fail now)

/* Only keep the 10 most recent builds. */
properties([[$class: 'BuildDiscarderProperty',
                strategy: [$class: 'LogRotator', numToKeepStr: '10']]])

/* These platforms correspond to labels in ci.jenkins.io, see:
 *  https://github.com/jenkins-infra/documentation/blob/master/ci.adoc
 */
List platforms = ['linux', 'windows']
Map branches = [:]
for (int i = 0; i < platforms.size(); ++i) {
    String label = platforms[i]
    branches[label] = {
        node(label) {
            timestamps {
                checkout scm
                infra.runMaven(["--batch-mode", "clean", "install", "-Dmaven.test.failure.ignore=true"])

                /* Archive the test results */
                try {
                  junit '**/target/surefire-reports/TEST-*.xml'
                } catch(Exception ex) {
                  echo "Ignoring JUnit step failure: ${ex.message}"
                }

                if (label == 'linux') {
                  archiveArtifacts artifacts: 'target/**/*.jar'
                  findbugs pattern: '**/target/findbugsXml.xml'
                }
            }
        }
    }
}

stage("Build Remoting") {
    /* Execute our platforms in parallel */
    //TODO uncomment parallel(branches)
}

essentialsTest(baseDir: "src/test/it")
