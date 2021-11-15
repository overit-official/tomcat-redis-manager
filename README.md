[![CI](https://github.com/amodolo/tomcat-redis-manager/actions/workflows/maven-test.yml/badge.svg)](https://github.com/amodolo/tomcat-redis-manager/actions/workflows/maven-test.yml)

# Redis Session Manager for Apache Tomcat

## Overview

This is a Tomcat session manager that saves sessions in Redis for easy distribution of requests across a cluster of
Tomcat servers. Obviously, data stored in the session must be Serializable.

You can use this library as a dependency inside your project, or you can embed the shaded jar inside the `/lib` directory of the Tomcat.


> In this version only Tomcat 8.5 is supported, newer version of tomcat has not been tested yet.

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