#!/bin/sh -ex
javac *.java
jar cvf ../src/test/resources/someJar.jar *.class
