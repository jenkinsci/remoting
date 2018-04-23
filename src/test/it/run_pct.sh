#!/usr/bin/env bash
docker run --rm -v maven-repo:/root/.m2 -v $(pwd)/out:/pct/out \
    -v $(pwd)/target/custom-war-packager-maven-plugin/output/target/jenkins-remoting-it-1.0-remoting-it-SNAPSHOT.war:/pct/jenkins.war:ro \
    -e ARTIFACT_ID=windows-slaves \
    -e INSTALL_BUNDLED_SNAPSHOTS=true \
    jenkins/pct
