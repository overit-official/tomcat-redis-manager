package com.overit.tomcat.redis;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.InvalidURIException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.util.JedisURIHelper;
import redis.clients.jedis.util.Pool;

import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Driver used to connect with a Redis service and run commands.
 *
 * <p>Use the static {@link #instance()} method to get a singleton instance of this driver and connect with the local
 * redis service ({@code redis://localhost:6379}). Otherwise, it is possible to use the {@link #setUrl(String)} method
 * to specify another connection url, or even you can configure the communication timeouts using the {@link #setConnectionTimeout(int)} and
 * {@link #setSoTimeout(int)} methods.</p>
 *
 * <p><em>You have to call the setter methods before the {@link #instance()}</em>.</p>
 *
 * <p>Remember to call the {@link #dispose()} to close the connection once done</p>
 *
 * @author Mauro Manfrin
 * @author Alessandro Modolo
 */
class RedisConnector {

    public static final String PROPERTY_URL = "tomcat.redis.manager.url";
    public static final String PROPERTY_SENTINEL_GROUP = "tomcat.redis.manager.sentinelGroup";

    private static final Log log = LogFactory.getLog(RedisConnector.class);
    private static String url = "redis://localhost:6379";
    private static int connectionTimeout = Protocol.DEFAULT_TIMEOUT;
    private static int soTimeout = Protocol.DEFAULT_TIMEOUT;
    private static String sentinelGroup = null;
    private static RedisConnector instance;

    /**
     * Set the connection URL used to encode connection info to Redis servers.
     * Supported URLs are in any of these formats:
     * <pre>{@code
     * redis://HOST[:PORT][?db=DATABASE[&password=PASSWORD]]
     * redis://HOST[:PORT][?password=PASSWORD[&db=DATABASE]]
     * redis://[:PASSWORD@]HOST[:PORT][/DATABASE]
     * redis://[:PASSWORD@]HOST[:PORT][?db=DATABASE]
     * redis://HOST[:PORT]/DATABASE[?password=PASSWORD]
     * }</pre>
     *
     * It is possible to specify many semicolon separated urls (to support Redis Sentinel configuration)
     * @param url the url connection string
     */
    public static void setUrl(String url) {
        RedisConnector.url = url;
    }

    private static String getUrl() {
        String effectiveUrls = System.getenv(PROPERTY_URL);
        if (effectiveUrls == null) effectiveUrls = System.getProperty(PROPERTY_URL);
        if (effectiveUrls == null) effectiveUrls = url;
        return effectiveUrls;
    }

    /**
     * Maximum time that the client can wait to connect with the Redis DB before to give up
     *
     * @param connectionTimeout timeout expressed in seconds
     */
    public static void setConnectionTimeout(int connectionTimeout) {
        RedisConnector.connectionTimeout = connectionTimeout;
    }

    /**
     * Get the maximum time that the client can wait to connect with the Redis DB before to give up
     * @return timeout expressed in seconds
     */
    public static int getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * Maximum timeout that the client can wait to get the response from the Redis DB before to give up
     *
     * @param soTimeout timeout expressed in seconds
     */
    public static void setSoTimeout(int soTimeout) {
        RedisConnector.soTimeout = soTimeout;
    }

    /**
     * Get the maximum time that the client can wait to get the response from the Redis DB before to give up
     * @return timeout expressed in seconds
     */
    public static int getSoTimeout() {
        return soTimeout;
    }

    /**
     * The name of the sentinel's master group. By providing this configuration, it enables the Redis Sentinel client
     * integration.
     * @param sentinelGroup the name of the sentinel master group.
     * @see <a href="https://redis.io/docs/manual/sentinel/">High availability with Redis Sentinel</a>
     */
    public static void setSentinelGroup(String sentinelGroup) {
        RedisConnector.sentinelGroup = sentinelGroup;
    }

    private static String getSentinelGroup() {
        String effectiveSentinelGroup = System.getenv(PROPERTY_SENTINEL_GROUP);
        if (effectiveSentinelGroup == null) effectiveSentinelGroup = System.getProperty(PROPERTY_SENTINEL_GROUP);
        if (effectiveSentinelGroup == null) effectiveSentinelGroup = sentinelGroup;
        return effectiveSentinelGroup;
    }
    /**
     * Retrieve an instance that will be used to communicate with the Redis server
     *
     * @return the instance used to interact with the DB
     */
    public static RedisConnector instance() {
        if (instance == null) {
            String sentinelGroup = getSentinelGroup();
            if (sentinelGroup == null || sentinelGroup.isBlank()) {
                instance = new RedisConnector(getUrl(), connectionTimeout, soTimeout);
            } else {
                instance = new RedisConnector(getUrl(), sentinelGroup, connectionTimeout, soTimeout);
            }
        }
        return instance;
    }

    /**
     * Close the connection with the Redis server and reset the instance
     */
    static void dispose() {
        if (instance == null) return;
        instance.stop();
        instance = null;
    }


    private final Pool<Jedis> pool;

    private RedisConnector(String urls, String sentinelGroup, int connectionTimeout, int soTimeout) {

        Set<URI> connectionUrls = Stream.of(urls.split(";"))
            .map(URI::create)
            .collect(Collectors.toSet());

        validateURI(connectionUrls);

        Set<String> hosts = getSentinelHosts(connectionUrls);
        int dbIndex = getSentinelDBIndex(connectionUrls);
        String user = getSentinelUser(connectionUrls);
        String password = getSentinelPassword(connectionUrls);

        if (log.isDebugEnabled()) {
            connectionUrls.forEach(u -> log.debug(String.format("connecting to %s sentinel", u)));
        }

        pool = new JedisSentinelPool(sentinelGroup, hosts, getPoolConfig(), connectionTimeout, soTimeout, user, password, dbIndex);
    }

    private RedisConnector(String url, int connectionTimeout, int soTimeout) {
        URI uri = URI.create(url);

        if (log.isDebugEnabled()) log.debug(String.format("connecting to %s service", uri));

        pool = new JedisPool(getPoolConfig(), uri, connectionTimeout, soTimeout);
    }

    private JedisPoolConfig getPoolConfig() {
        JedisPoolConfig jpc = new JedisPoolConfig();
        jpc.setMaxTotal(10);
        jpc.setMaxIdle(3);
        jpc.setMinIdle(1);
        jpc.setLifo(true);
        jpc.setMinEvictableIdleDuration(Duration.of(10, ChronoUnit.SECONDS));
        jpc.setTestOnCreate(true);
        jpc.setTestOnBorrow(true);
        return jpc;
    }

    private Set<String> getSentinelHosts(Set<URI> urls) {
        return urls.stream()
            .map(JedisURIHelper::getHostAndPort)
            .map(HostAndPort::toString)
            .collect(Collectors.toSet());
    }

    private Integer getSentinelDBIndex(Set<URI> urls) {
        return urls.stream().map(JedisURIHelper::getDBIndex).findFirst().orElse(Protocol.DEFAULT_DATABASE);
    }

    private String getSentinelUser(Set<URI> urls) {
        return urls.stream().map(JedisURIHelper::getUser).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private String getSentinelPassword(Set<URI> urls) {
        return urls.stream().map(JedisURIHelper::getPassword).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private void validateURI(Set<URI> urls) {
        urls.forEach(this::validateURI);
    }

    private void validateURI(URI url) {
        if (!JedisURIHelper.isValid(url)) {
            throw new InvalidURIException(String.format(
                "Cannot open Redis connection due invalid URI. %s", url));
        }
    }

    /**
     * Close the connection with the Redis server
     */
    protected void stop() {
        pool.close();
    }


    /**
     * Execute the given function
     *
     * @param f   the function to be executed
     * @param <T> the return Type
     * @return the execution result
     */
    public <T> T execute(Function<Jedis, T> f) {
        try (Jedis j = pool.getResource()) {
            return f.apply(j);
        }
    }

    /**
     * Broadcast a message via the specified channel. The message will be received by all agents that has been
     * {@link #subscribe(JedisPubSub, String...) subscribed} to this channel
     *
     * @param channel the channel against with send the message
     * @param message the message payload
     */
    public void publish(String channel, String message) {
        execute(client -> client.publish(channel, message));
    }

    /**
     * Subscribe to all the messages received from the specified channels.
     * <br/>
     * <em>WARNING: This method will block the current thread until the un-subscription</em>
     *
     * @param subscriber instance of the subscriber interface
     * @param channels   channels from which receive the notifications
     */
    public void subscribe(JedisPubSub subscriber, String... channels) {
        execute(client -> {
            client.subscribe(subscriber, channels);
            return null;
        });
    }

    /**
     * Extract the keys that matches a given pattern and belongs to a specific type
     *
     * @param pattern the pattern string
     * @param type    string representation of the type of the value stored at key. The different types that can be used
     *                are:
     *                <ul>
     *                 <li>string</li>
     *                 <li>list</li>
     *                 <li>set</li>
     *                 <li>zset</li>
     *                 <li>hash</li>
     *                 <li>stream</li>
     *                </ul>
     * @return the extracted keys
     */
    public Set<String> keys(String pattern, String type) {

        return execute(j -> {
            Set<String> set = new HashSet<>();
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanParams sp = new ScanParams();
                sp.match(pattern);

                ScanResult<String> sr;
                if (type != null) sr = j.scan(cursor, sp, type);
                else sr = j.scan(cursor, sp);
                cursor = sr.getCursor();
                set.addAll(sr.getResult());
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

            return set;
        });

    }

    /**
     * Delete the keys that match a given pattern and belongs to a specific type
     *
     * @param pattern the pattern string
     * @param type    string representation of the type of the value stored at key. The different types that can be used
     *                are:
     *                <ul>
     *                 <li>string</li>
     *                 <li>list</li>
     *                 <li>set</li>
     *                 <li>zset</li>
     *                 <li>hash</li>
     *                 <li>stream</li>
     *                </ul>
     */
    public void del(String pattern, String type) {
        execute(j -> {

            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanParams sp = new ScanParams();
                sp.match(pattern);

                ScanResult<String> sr;
                if (type != null) sr = j.scan(cursor, sp, type);
                else sr = j.scan(cursor, sp);

                cursor = sr.getCursor();
                List<String> res = sr.getResult();

                if (!res.isEmpty()) j.del(res.toArray(new String[0]));

            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

            return null;
        });


    }
}
