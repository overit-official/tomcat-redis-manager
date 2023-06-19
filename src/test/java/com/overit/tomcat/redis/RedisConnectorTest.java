package com.overit.tomcat.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RedisConnectorTest {

    @BeforeEach
    public void setUp() throws Exception {
        RedisConnector.instance().del("*", "string");
    }

    @AfterEach
    public void tearDown() throws Exception {
        RedisConnector.dispose();
    }

    @Test
    void instance() {
        RedisConnector instance = RedisConnector.instance();
        assertNotNull(instance);
        RedisConnector instance2 = RedisConnector.instance();
        assertSame(instance, instance2);
    }

    @Test
    void stop() {
        RedisConnector instance = RedisConnector.instance();
        instance.stop();
        assertThrows(Exception.class, () -> instance.execute(Jedis::ping));
    }

    @Test
    void execute() {
        String pong = RedisConnector.instance().execute(Jedis::ping);
        assertEquals("PONG", pong);
    }

    @Test
    void keys() {
        RedisConnector redis = RedisConnector.instance();
        assertEquals(0, redis.keys("*", "string").size());

        redis.execute(j -> {
            Transaction t = j.multi();
            t.set("key1", "val1");
            t.set("key2", "val2");
            t.set("key3", "val3");
            t.exec();
            return null;
        });

        assertEquals(3, redis.keys("*", "string").size());
    }

    @Test
    void del() {
        RedisConnector redis = RedisConnector.instance();
        redis.execute(j -> j.set("foo", "bar"));
        Set<String> keys = redis.keys("foo", "string");
        assertEquals(1, keys.size());
        assertTrue(keys.contains("foo"));

        redis.del("foo", "string");
        keys = redis.keys("foo", "string");
        assertEquals(0, keys.size());

    }
}