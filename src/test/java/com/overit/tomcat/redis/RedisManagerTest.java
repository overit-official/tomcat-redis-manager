package com.overit.tomcat.redis;

import org.junit.*;
import com.overit.tomcat.TesterContainer;
import com.overit.tomcat.TesterContext;
import com.overit.tomcat.TesterServletContext;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RedisManagerTest {

    private RedisManager manager;

    @Before
    public void setUp() throws Exception {
        TesterContext testerContext = new TesterContext();
        testerContext.setParent(new TesterContainer());
        testerContext.setServletContext(new TesterServletContext());
        manager = new RedisManager();
        manager.setUrl(String.format("redis://%s:%d", "localhost", 6379));
        manager.setContext(testerContext);
    }

    @After
    public void tearDown() throws Exception {
        manager.stop();
        RedisConnector.instance().del("*", "string");
        RedisConnector.instance().del("*", "*");
    }

    @AfterClass
    public static void shutdown() throws Exception {
        RedisConnector.dispose();
    }

    @Test
    public void unload() throws Exception {
        manager.start();
        manager.createSession("s1");
        manager.stop();
        Set<String> keys = RedisConnector.instance().keys(getSessionKey("*"), "string");
        Assert.assertEquals(1, keys.size());
        Assert.assertEquals(getSessionKey("s1"), keys.toArray(new String[]{})[0]);
    }


    @Test
    public void unloadTwoSessions() throws Exception {
        manager.start();
        manager.createSession("s1");
        manager.createSession("s2");
        manager.stop();
        Set<String> keys = RedisConnector.instance().keys(getSessionKey("*"), "string");
        Assert.assertEquals(2, keys.size());
        Assert.assertTrue(keys.containsAll(Stream.of("s1", "s2").map(this::getSessionKey).collect(Collectors.toList())));
    }

    @Test
    public void load() throws Exception {
        manager.start();
        manager.createSession("s1");
        manager.stop();
        manager.start();

        Assert.assertEquals(1, manager.getActiveSessions());
        Assert.assertEquals("s1", manager.findSession("s1").getId());
    }

    @Test
    public void loadTwoSessions() throws Exception {
        manager.start();
        manager.createSession("s1");
        manager.createSession("s2");
        manager.stop();
        manager.start();

        Assert.assertEquals(2, manager.getActiveSessions());
        Assert.assertEquals("s1", manager.findSession("s1").getId());
        Assert.assertEquals("s2", manager.findSession("s2").getId());
    }


    private String getSessionKey(String sessionId) {
        return "tomcat:session:" + sessionId;
    }
}