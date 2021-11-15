package com.overit.tomcat.redis;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.*;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Driver used to connect with a Redis service and run commands.
 *
 * <p>Use the static {@link #instance()} method to get a singleton instance of this driver and connect with the local
 * redis service ({@code redis://localhost:6379}). Otherwise, it is possible to use the {@link #setUrl(String)} method
 * to specify another connection url, or even you can configure the comunication timeouts using the {@link #setConnectionTimeout(int)} and
 * {@link #setSoTimeout(int)} methods.</p>
 *
 * <p><em>You have to call the setter methods before the {@link #instance()}</em>.</p>
 *
 * <p>Remember to call the {@link #dispose()} to close the connection once done</p>
 *
 * @author Mauro Manfrin
 * @author Alessandro Modolo
 */
public class RedisConnector {

    private static final Log log = LogFactory.getLog(RedisConnector.class);
    private static String url = "redis://localhost:6379";
    private static int connectionTimeout = Protocol.DEFAULT_TIMEOUT;
    private static int soTimeout = Protocol.DEFAULT_TIMEOUT;
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
     * @param url the url connection string
     */
    public static void setUrl(String url) {
        RedisConnector.url = url;
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
     * Maximum timeout that the client can wait to get the response from the Redis DB before to give up
     *
     * @param soTimeout timeout expressed in seconds
     */
    public static void setSoTimeout(int soTimeout) {
        RedisConnector.soTimeout = soTimeout;
    }

    /**
     * Retrieve an instance that will be used to communicate with the Redis server
     *
     * @return the instance used to interact with the DB
     */
    public static RedisConnector instance() {
        if (instance == null) {
            instance = new RedisConnector(url, connectionTimeout, soTimeout);
        }
        return instance;
    }

    /**
     * Close the connection with the Redis server and reset the instance
     */
    public static void dispose() {
        if (instance == null) return;
        instance.stop();
        instance = null;
    }


    private final JedisPool pool;

    private RedisConnector(String url, int connectionTimeout, int soTimeout) {
        String effectiveUrl = System.getenv("tomcat.redis.manager.url");
        if (effectiveUrl == null) effectiveUrl = System.getProperty("tomcat.redis.manager.url");
        if (effectiveUrl == null) effectiveUrl = url;

        URI uri = URI.create(effectiveUrl);

        JedisPoolConfig jpc = new JedisPoolConfig();
        jpc.setMaxTotal(10);
        jpc.setMaxIdle(3);
        jpc.setMinIdle(1);
        jpc.setLifo(true);
        jpc.setMinEvictableIdleTimeMillis(10000);
        jpc.setTestOnCreate(true);
        jpc.setTestOnBorrow(true);

        if (log.isDebugEnabled()) log.debug(String.format("connecting to %s service", uri));

        pool = new JedisPool(jpc, uri, connectionTimeout, soTimeout);

    }

    /**
     * Close the connection with the Redis server
     */
    private void stop() {
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
                ScanParams2 sp = new ScanParams2();
                sp.match(pattern);
                if (type != null) sp.type(type);

                ScanResult<String> sr = j.scan(cursor, sp);
                cursor = sr.getCursor();
                set.addAll(sr.getResult());
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

            return set;
        });

    }

    /**
     * Delete the keys that matches a given pattern and belongs to a specific type
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
                ScanParams2 sp = new ScanParams2();
                sp.match(pattern);
                if (type != null) sp.type(type);

                ScanResult<String> sr = j.scan(cursor, sp);

                cursor = sr.getCursor();
                List<String> res = sr.getResult();

                if (!res.isEmpty()) j.del(res.toArray(new String[0]));

            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

            return null;
        });


    }
}
