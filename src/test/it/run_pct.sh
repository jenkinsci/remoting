#!/usr/bin/env bash
docker run --rm -v maven-repo:/root/.m2 -v $(pwd)/out:/pct/out \
    -v $(pwd)/target/custom-war-packager-maven-plugin/output/target/jenkins-war-1.0-remoting-it-SNAPSHOT.war:/pct/jenkins.war:ro \
    -e ARTIFACT_ID=copyartifact \
    -e INSTALL_BUNDLED_SNAPSHOTS=true \
    jenkins/pct
