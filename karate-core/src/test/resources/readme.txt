dev:
====
mvn versions:set versions:commit -DnewVersion=2.0.0

cve check
=========
mvn clean verify -P depcheck
(save the above report)

prod:
=====
mvn versions:set versions:commit -DnewVersion=@@@

# edit archetype karate.version
# edit README.md maven 3 places
# edit examples/gatling/build.gradle
# edit examples/*/pom.xml
# edit jbang-catalog.json

# make release using [develop]
# using github action: https://github.com/karatelabs/karate/actions/workflows/maven-release.yml
# once release passes, download artifacts zip
# make sure the release tag is against the right branch (master or develop)
# upload following to github release notes
    karate-core/target/karate-XXX.zip
    karate-core/target/karate-XXX.jar
    karate-robot/target/karate-robot-XXX.jar

(save the sonatype report)

github
======
# edit https://github.com/karatelabs/jbang-catalog
# edit https://github.com/karatelabs/karate-template
# edit https://github.com/karatelabs/karate-todo

docker (deprecated)
===================
# make sure docker is started and is running]
rm -rf ~/.m2/repository/com/intuit/karate
rm -rf karate-docker/karate-chrome/target
mvn clean install -P pre-release -DskipTests
./build-docker.sh

docker tag karate-chrome karatelabs/karate-chrome:@@@
docker tag karate-chrome karatelabs/karate-chrome:latest

docker push karatelabs/karate-chrome:@@@
docker push karatelabs/karate-chrome:latest
