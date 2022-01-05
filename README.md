
[![Docker CI](https://github.com/overit-official/tomcat-redis-manager/actions/workflows/docker-ci.yml/badge.svg)](https://github.com/overit-official/tomcat-redis-manager/actions/workflows/docker-ci.yml)
[![Maintenance](https://img.shields.io/badge/Maintained%3F-yes-green.svg)](https://github.com/overit-official/tomcat-redis-manager/graphs/commit-activity)
[![made-by-OverIT](https://img.shields.io/badge/Made%20by-OverIT-1f425f.svg)](https://overit.us)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT) 
[![GitHub release](https://img.shields.io/github/release/overit-official/tomcat-redis-manager.svg)](https://github.com/overit-official/tomcat-redis-manager/releases/)

# Redis Session Manager for Apache Tomcat

## Overview

This is a Tomcat session manager that saves sessions in Redis for an easy distribution of requests across a cluster of
Tomcat servers. Obviously, data stored in the session must be Serializable.

You can use this library as a dependency inside your project, or you can embed the shaded jar inside the `/lib` directory of the Tomcat.


> In this version only Tomcat 8.5 is supported, newer versions of tomcat have not been tested yet.

## How to build

The only things you have to do in order to build this library is:
1. clone the repo
2. build using the `mvnw clean package` command

## Usage

To enable simple session backup at shutdown/start of a context, it is possible to configure the application descriptor
as following:

```xml
<Context>
    <Manager class="com.overit.tomcat.redis.RedisManager"/>
</Context>
```

where the `Manager` tag can be configured with these additional configuration attributes:

<table>
    <tr>
        <td><code>url</code></td>
        <td>the redis database URL (i/e something like : redis://localhost:6379). This attribute is mandatory.</td>
    </tr>
    <tr>
        <td><code>connectionTimeout</code></td>
        <td>the socket connection timeout for redis connections in milliseconds</td>
    </tr>
    <tr>
        <td><code>soTimeout</code></td>
        <td>the socket communication timeout for redis connections in milliseconds</td>
    </tr>
    <tr>
        <td><code>prefix</code></td>
        <td>prefix of the keys whose contains the serialized sessions. Those to avoid possible conflicts if the same Redis instance is shared between multiple applications. If not specified, the default prefix value is Tomcat</td>
    </tr>
</table>

To enable persistence of sessions across cluster using the Store, it is possible to configure the application descriptor
as following:

```xml
<Context>
    <Manager className="org.apache.catalina.session.PersistentManager">
        <Store className="com.overit.tomcat.redis.RedisStore"/>
    </Manager>
</Context>
```

where `Manager` can be configured with the following additional parameters

<table>
    <tr>
        <td><code>maxIdleSwap</code></td>
        <td>The maximum time a session may be idle before it is eligible to be swapped to disk due to inactivity.</td>
    </tr>
    <tr>
        <td><code>minIdleSwap</code></td>
        <td>The minimum time in seconds a session must be idle before it is eligible to be swapped to disk to keep
  the active session count below maxActiveSessions. If specified, this value should be less than that specified by
  maxIdleSwap.</td>
    </tr>
    <tr>
        <td><code>processExpiresFrequency</code></td>
        <td>Frequency of the session expiration, and related manager operations. Manager operations
  will be done once for the specified amount of backgroundProcess calls (i.e., the lower the amount, the more often the
  checks will occur). The minimum value is 1, and the default value is 6.</td>
    </tr>
</table>

Other manager parameters relevant to the session management can be found in the Tomcat's [documentation](https://tomcat.apache.org/tomcat-8.5-doc/config/manager.html).

The `Store` tag can be configured as follows:
<table>
    <tr>
        <td><code>url</code></td>
        <td>the redis database URL (i/e something like : redis://localhost:6379). This attribute is mandatory.</td>
    </tr>
    <tr>
        <td><code>connectionTimeout</code></td>
        <td>the socket connection timeout for redis connections expressed in millis</td>
    </tr>
    <tr>
        <td><code>soTimeout</code></td>
        <td>the socket communication timeout for redis connections expressed in millis</td>
    </tr>
    <tr>
        <td><code>prefix</code></td>
        <td>prefix of the keys whose contains the serialized sessions. Those to avoid possible conflicts if the same
  Redis instance is shared between multiple applications. If not specified, the default prefix value is Tomcat.</td>
    </tr>
</table>

## Release a new version

The release process follows the [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html)

This project takes advantage of a *github workflow* for the releases, so each time you push a new release tag to github, the code is compiled and tested using maven, java 11 and a containerized version of redis 6 (for the integration tests), then a new Docker image is generated from the source code and multiple tags are pushed to DockerHub according to the tag which has been pushed. After that, a new Github release is flagged from the latest tag pushed.

### Release tags

To correctly trigger a new release you must follow the Semantic Versioning 2.0 and push a new tag in the form of `v<MAJOR>.<MINOR>.<PATCH>`.
From this tag a series of new tags for your Docker image are generated according to the following schema:

| git tag | generated docker tags |
|---------|-----------------------|
| v1.2.3  | 1, 1.2, 1.2.3, latest |

And a Github Release `<MAJOR>.<MINOR>.<PATCH>` is produced.

In order to help releasing new tags according to this schema, you can add these ***git aliases***:

#### git lasttag 

Lets you find the last tag in the above form that has been released. It fetches the remote tags first.

An example of usage might be this:
```bash
$ git lasttag
v1.2.3
```
To add this alias you have to copy-paste this instruction in your terminal:
```bash
git config --global alias.lasttag '!f() { git fetch -tp &>/dev/null; git tag -l v* --sort=v:refname | tail -1; }; f'
```

### git release

This alias requires `git lasttag` alias to be added among yout git aliases.
It allows you to create a new release tag (it doesn't push it to the associated remote) increasing the 
patch, minor or major version number according to the flag you provide in input.

Some usage examples might be:
```bash
$ git release --usage
Creates a new tag from the current commit.
Usage: 
  git release [--patch] # creates a new patch release 
  git release  --minor  # creates a new minor release  
  git release  --major  # creates a new major release 
  
$ git release
Latest tag found: v1.3.5
New release tag:  v1.3.6  

$ git release --patch
Latest tag found: v1.3.6
New release tag:  v1.3.7

$ git release --minor 
Latest tag found: v1.3.7
New release tag:  v1.4.0

$ git release --major
Latest tag found: v1.5.0
New release tag:  v2.0.0
```
When you create a new tag using the `git release` alias, you have to push it to the remote in order to trigger the github workflow and to release new Docker images:
```bash
$ git release
Latest tag found: v1.3.5
New release tag:  v1.3.6 

$ git push origin v1.3.6
```

To add the `git release alias` to your aliases you can copy-past in your terminal the following:
```bash
git config --global alias.release '!f() {                      
    RESET=`tput sgr0`
    GREEN=`tput setaf 2`
    CYAN=`tput setaf 6`
    YELLOW=`tput setaf 3`
    BOLD=`tput bold` 
    LASTTAG=$(git lasttag)
    [ "$LASTTAG" != "" ] || LASTTAG="v0.0.0"
    VERSION=$(echo "$LASTTAG" | cut -d 'v' -f 2)
    SPLITTED=(${VERSION//./ })                 
    for i in {0..2}                                            
    do                                                         
       SPLITTED[$i]=`echo ${SPLITTED[$i]} | cut -d '-' -f 1`   
       [ "${SPLITTED[$i]}" != "" ] || SPLITTED[$i]=0           
    done                                                       
    if [ "$1" == "--help" ] || [ "$1" == "-h" ] || [ "$1" == "--usage" ]; then 
       echo "" 
       echo "Creates a ${BOLD}${YELLOW}new tag${RESET} from the current commit."
       echo "Usage: "
       echo "  git release ${GREEN}[--patch] ${CYAN}# creates a new patch release ${RESET}"
       echo "  git release  --minor  ${CYAN}# creates a new minor release  ${RESET}"
       echo "  git release  --major  ${CYAN}# creates a new major release  ${RESET}"
       exit 0
    elif [ "$1" == "--major" ]; then                           
       SPLITTED[0]=$((SPLITTED[0]+1))   
       SPLITTED[1]=0
       SPLITTED[2]=0                       
    elif [ "$1" == "--minor" ]; then                         
       SPLITTED[1]=$((SPLITTED[1]+1))   
       SPLITTED[2]=0                       
    else                                                       
       SPLITTED[2]=$((SPLITTED[2]+1))                          
    fi                                                         
    echo "Latest tag found: ${BOLD}${YELLOW} `git lasttag`${RESET}"                                                           
    git tag v${SPLITTED[0]}.${SPLITTED[1]}.${SPLITTED[2]}      
    echo "New release tag: ${BOLD}${GREEN} `git lasttag`${RESET}"
}; f'
```
