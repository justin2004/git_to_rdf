#!/bin/bash

docker run --rm -v `pwd`:/mnt justin2004/git_to_rdf java -Dorg.slf4j.simpleLogger.logFile=git_to_rdf.log -cp /mnt/sparql-anything-0.8.0.jar:/mnt/target/git_to_rdf-0.1.0-SNAPSHOT-standalone.jar git_to_rdf.core $*
