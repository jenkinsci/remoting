#!/usr/bin/env groovy

// TODO: restore original tests (which just fail now)

/* Only keep the 10 most recent builds. */
properties([[$class: 'BuildDiscarderProperty',
                strategy: [$class: 'LogRotator', numToKeepStr: '10']]])

node("docker && highmem") {
    stage("Checkout") {
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
