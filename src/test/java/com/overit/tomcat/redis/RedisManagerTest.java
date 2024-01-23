package com.overit.tomcat.redis;

import com.overit.tomcat.TesterContainer;
import com.overit.tomcat.TesterContext;
import com.overit.tomcat.TesterServletContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RedisManagerTest {

    private RedisManager manager;

    @BeforeEach
    public void setUp() {
        TesterContext testerContext = new TesterContext();
        testerContext.setParent(new TesterContainer());
        testerContext.setServletContext(new TesterServletContext());
        manager = new RedisManager();
        manager.setUrl(String.format("redis://%s:%d", "localhost", 6379));
        manager.setContext(testerContext);
    }

    @AfterEach
    public void tearDown() throws Exception {
        manager.stop();
        RedisConnector.instance().del("*", "string");
        RedisConnector.instance().del("*", "*");
    }

    @AfterAll
    public static void shutdown() {
        RedisConnector.dispose();
    }

    @Test
    void unload() throws Exception {
        manager.start();
        manager.createSession("s1");
        manager.stop();
        Set<String> keys = RedisConnector.instance().keys(getSessionKey("*"), "string");
        assertEquals(1, keys.size());
        assertEquals(getSessionKey("s1"), keys.toArray(new String[]{})[0]);
    }


    @Test
    void unloadTwoSessions() throws Exception {
        manager.start();
        manager.createSession("s1");
        manager.createSession("s2");
        manager.stop();
        Set<String> keys = RedisConnector.instance().keys(getSessionKey("*"), "string");
        assertEquals(2, keys.size());
        assertTrue(keys.containsAll(Stream.of("s1", "s2").map(this::getSessionKey).toList()));
    }

    @Test
    void load() throws Exception {
        manager.start();
        manager.createSession("s1");
        manager.stop();
        manager.start();

        assertEquals(1, manager.getActiveSessions());
        assertEquals("s1", manager.findSession("s1").getId());
    }

    @Test
    void loadTwoSessions() throws Exception {
        manager.start();
        manager.createSession("s1");
        manager.createSession("s2");
        manager.stop();
        manager.start();

        assertEquals(2, manager.getActiveSessions());
        assertEquals("s1", manager.findSession("s1").getId());
        assertEquals("s2", manager.findSession("s2").getId());
    }


    private String getSessionKey(String sessionId) {
        return "tomcat:session:" + sessionId;
    }
}
