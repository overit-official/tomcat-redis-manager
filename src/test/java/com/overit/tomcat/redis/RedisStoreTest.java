package com.overit.tomcat.redis;

import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.session.StandardSession;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import com.overit.tomcat.TesterContext;
import com.overit.tomcat.TesterServletContext;

import java.io.IOException;

public class RedisStoreTest {
    private RedisStore store;
    private Manager manager;

    @Before
    public void setUp() throws Exception {
        TesterContext testerContext = new TesterContext();
        testerContext.setServletContext(new TesterServletContext());
        manager = new StandardManager();
        manager.setContext(testerContext);
        store = new RedisStore();
        store.setUrl(String.format("redis://%s:%d", "localhost", 6379));
        store.setManager(manager);

        store.save(createSession("s1"));
        store.save(createSession("s2"));
    }

    @After
    public void cleanup() throws Exception {
        store.clear();
        store.stop();
    }

    private Session createSession(String id) {
        StandardSession session = new StandardSession(manager);
        session.setNew(true);
        session.setValid(true);
        session.setCreationTime(System.currentTimeMillis());
        session.setMaxInactiveInterval(manager.getContext().getSessionTimeout() * 60);
        session.setAttribute("key", "val");
        session.setId(id);
        return session;
    }

    @Test
    public void getSize() {
        Assert.assertEquals(2, store.getSize());
    }

    @Test
    public void clear() {
        store.clear();
        Assert.assertEquals(0, store.getSize());
    }

    @Test
    public void keys() {
        Assert.assertArrayEquals(new String[]{"s1", "s2"}, store.keys());
        store.clear();
        Assert.assertArrayEquals(new String[]{}, store.keys());
    }

    @Test
    public void remove() {
        store.remove("s1");
        Assert.assertEquals(1, store.getSize());
    }

    @Test
    public void add() throws IOException {
        store.save(createSession("s3"));
        Assert.assertEquals(3, store.getSize());
        Assert.assertArrayEquals(new String[]{"s1", "s2", "s3"}, store.keys());
    }

    @Test
    public void load() {
        Session s2 = store.load("s2");
        Assert.assertNotNull(s2);
        Assert.assertEquals("s2", s2.getIdInternal());
    }
}