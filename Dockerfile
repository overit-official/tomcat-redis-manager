# use this commands to build and push a tomcat image that includes this stores inside the /lib dir
# $repo=your/repo
# $maj=<major version>
# $min=<minor version>
# $patch=<patch version>
# docker build -t $repo:latest .
# docker push $repo:latest
# docker tag $repo:latest $repo:$maj
# docker push $repo:$maj
# docker tag $repo:latest $repo:$maj.$min
# docker push $repo:$maj.$min
# docker tag $repo:latest $repo:$maj.$min.$patch
# docker push $repo:$maj.$min.$patch

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /tmp
COPY src ./src
COPY pom.xml .
RUN mvn clean package -DskipTests

FROM tomcat:10.1.16-jre17-temurin-jammy
COPY --from=build /tmp/target/tomcat-redis-manager-*-shaded.jar $CATALINA_HOME/lib
