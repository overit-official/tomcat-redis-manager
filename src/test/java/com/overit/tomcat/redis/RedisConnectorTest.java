package com.overit.tomcat.redis;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.BinaryJedis;
import redis.clients.jedis.Transaction;

import java.util.Set;

public class RedisConnectorTest {

    @Before
    public void setUp() throws Exception {
        RedisConnector.instance().del("*", "string");
    }

    @After
    public void tearDown() throws Exception {
        RedisConnector.dispose();
    }

    @Test
    public void instance() {
        RedisConnector instance = RedisConnector.instance();
        Assert.assertNotNull(instance);
        RedisConnector instance2 = RedisConnector.instance();
        Assert.assertSame(instance, instance2);
    }

    @Test
    public void dispose() {
        RedisConnector instance = RedisConnector.instance();
        RedisConnector.dispose();
        Assert.assertThrows(Exception.class, () -> instance.execute(BinaryJedis::ping));
    }

    @Test
    public void execute() {
        String pong = RedisConnector.instance().execute(BinaryJedis::ping);
        Assert.assertEquals("PONG", pong);
    }

    @Test
    public void keys() {
        RedisConnector redis = RedisConnector.instance();
        Assert.assertEquals(0, redis.keys("*", "string").size());

        redis.execute(j -> {
            Transaction t = j.multi();
            t.set("key1", "val1");
            t.set("key2", "val2");
            t.set("key3", "val3");
            t.exec();
            return null;
        });

        Assert.assertEquals(3, redis.keys("*", "string").size());
    }

    @Test
    public void del() {
        RedisConnector redis = RedisConnector.instance();
        redis.execute(j -> j.set("foo", "bar"));
        Set<String> keys = redis.keys("foo", "string");
        Assert.assertEquals(1, keys.size());
        Assert.assertTrue(keys.contains("foo"));

        redis.del("foo", "string");
        keys = redis.keys("foo", "string");
        Assert.assertEquals(0, keys.size());

    }
}