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
    parallel(branches)
}

node("docker && highmem") {
    // TODO: this VM is not a single-shot one, we need to wipe it on our own
  dir(env.BUILD_NUMBER) {

    stage("Integration Tests: Checkout") {
        infra.checkout()
    }

    def mvnSettingsFile = "${pwd tmp: true}/settings-azure.xml"
    def mvnSettingsFileFound = infra.retrieveMavenSettingsFile(mvnSettingsFile)
    def outputWAR
    def metadataPath

    dir("src/test/it") {
        // TODO: convert to a library method
        def outputWARpattern = "target/custom-war-packager-maven-plugin/output/target/jenkins-remoting-it-1.0-remoting-it-SNAPSHOT.war"
        stage("Build Custom WAR") {
            List<String> mavenOptions = [
                '--batch-mode', '--errors',
                'clean', 'install',
                "-Dcustom-war-packager.batchMode=true",
                "-Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn"
            ]

            if (mvnSettingsFileFound) {
                mavenOptions << "-s"
                mavenOptions << "${mvnSettingsFile}"
                mavenOptions << "-Dcustom-war-packager.mvnSettingsFile=${mvnSettingsFile}"
            }

            infra.runMaven(mavenOptions)
            archiveArtifacts artifacts: outputWARpattern

            // Pass variables for the next steps
            outputWAR = pwd() + "/" + outputWARpattern
            metadataPath = pwd() + "/essentials.yml"
        }
    }

    def fileUri = "file://" + outputWAR
    stage("Run ATH") {
        dir("ath") {
            runATH jenkins: fileUri, metadataFile: metadataPath
        }
    }

    stage("Run PCT") {
        //TODO: Remove Slf4jMavenTransferListener option once runPCT() invokes it by default
        runPCT jenkins: fileUri, metadataFile: metadataPath,
               pctUrl: "docker://jenkins/pct:pr74",
               javaOptions: ["-Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn"]
    }
  }
}
