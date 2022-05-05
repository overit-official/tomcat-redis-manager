package com.overit.tomcat.redis;

import com.overit.tomcat.TesterContext;
import com.overit.tomcat.TesterServletContext;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.session.StandardSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.io.NotSerializableException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class RedisStoreTest {
    @Spy
    private RedisStore store;
    @Captor
    private ArgumentCaptor<Session> sessionArgumentCaptor;

    private Manager manager;

    @Before
    public void setUp() throws Exception {
        TesterContext testerContext = new TesterContext();
        testerContext.setServletContext(new TesterServletContext());
        manager = new StandardManager();
        manager.setContext(testerContext);
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

    @Test
    public void getSize() {
        assertThat(store.getSize()).isEqualTo(2);
    }

    @Test
    public void clear() {
        store.clear();
        assertThat(store.getSize()).isZero();
    }

    @Test
    public void keys() {
        assertThat(store.keys()).containsExactlyInAnyOrder("s1", "s2");
        store.clear();
        assertThat(store.keys()).isEmpty();
    }

    @Test
    public void remove() {
        store.remove("s1");
        assertThat(store.getSize()).isEqualTo(1);
    }

    @Test
    public void add() throws IOException {
        store.save(createSession("s3"));
        assertThat(store.getSize()).isEqualTo(3);
        assertThat(store.keys()).containsExactlyInAnyOrder("s1", "s2", "s3");
    }

    @Test(expected = NotSerializableException.class)
    public void save_nonSerializableSession_shouldThrows() throws IOException {
        // given
        Session session = createSession("s");
        session.getSession().setAttribute("notserializable", new Object());

        // when()
        store.save(session);
    }

    @Test
    public void load() {
        Session s2 = store.load("s2");
        assertThat(s2.getIdInternal()).isEqualTo("s2");
    }

    @Test
    public void load_havingNoStoredSessionAndNoOneCanDrainIt_shouldReturnNullIn2Seconds() {
        // given
        long start = System.currentTimeMillis();

        // when
        Session session = store.load("unknown");

        // then
        long end = System.currentTimeMillis();
        assertThat(session).isNull();
        assertThat(end - start).isBetween(2000L, 2500L);
    }

    @Test
    public void load_havingNoStoredSessionButSomeoneThatCanDrainIt_shouldReturnNotNull() throws Exception {
        // give
        String sessionId = "s4";
        AtomicLong start = new AtomicLong();
        AtomicLong stop = new AtomicLong();
        when(store.askForSessionDraining(eq(sessionId), anyLong(), eq(true))).thenAnswer(updateTimeAndCallRealMethod(start));
        when(store.awaitAndLoad(eq(sessionId), anyLong())).thenAnswer(updateTimeAndCallRealMethod(stop));
        scheduleResponseExecution(sessionId);

        // when
        Session session = store.load(sessionId);

        // then
        assertThat(session.getIdInternal()).isEqualTo(sessionId);
        assertThat(stop.get() - start.get()).isLessThanOrEqualTo(2000);
    }


    @Test
    public void onSessionDrainRequest_whenReceiveARequestNotification_shouldCallTheMethod() {
        // when
        store.sendSessionDrainingRequest("");

        // then
        verify(store).onSessionDrainRequest("");
    }

    @Test
    public void onSessionDrainRequest_whenReceiveARequestNotificationOfUnknownSession_shouldNotWriteAnything() {
        // when
        store.sendSessionDrainingRequest("unknown");

        // then
        String key = store.getSessionRequestKey("unknown");
        String value = RedisConnector.instance().execute(client -> client.get(key));
        assertThat(value).isNull();
    }

    @Test
    public void onSessionDrainRequest_whenReceiveARequestNotification_shouldAddResponseIntoRedisEntry() throws InterruptedException {
        // when
        store.sendSessionDrainingRequest("s1");
        TimeUnit.MILLISECONDS.sleep(100);

        // then
        String key = store.getSessionRequestKey("s1");
        String value = RedisConnector.instance().execute(client -> client.get(key));
        assertThat(value).isEqualTo("true");
    }

    @Test
    public void onSessionDrainRequest_whenReceiveARequestNotification_shouldSaveTheSession() throws InterruptedException {
        // when
        store.sendSessionDrainingRequest("s1");
        TimeUnit.MILLISECONDS.sleep(100);

        // then
        byte[] session = store.loadSession("s1");
        assertThat(session).isNotEmpty();
    }

    @Test
    public void onSessionDrainingRequest_whenReceiveARequestForAProcessingSession_shouldWaitTheProcessingComplete() throws IOException {
        // give
        Session session = createSession("s3");
        simulateTaskProcessingOnSession(session);

        // when
        store.onSessionDrainRequest("s3");

        // then
        verify(store, atLeastOnce()).save(sessionArgumentCaptor.capture());
        assertThat(sessionArgumentCaptor.getValue().getSession().getAttribute("processing")).isEqualTo(false);
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

    private void scheduleResponseExecution(String sessionId) {
        String key = store.getSessionRequestKey(sessionId);
        RedisConnector.instance().execute(j -> j.set(key, "true"));
        Executors.newSingleThreadScheduledExecutor().schedule(
            () -> {
                try {
                    store.save(createSession("s4"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            },
            1L, TimeUnit.SECONDS
        );
    }

    private Answer<Object> updateTimeAndCallRealMethod(AtomicLong start) {
        return invocation -> {
            start.set(System.currentTimeMillis());
            return invocation.callRealMethod();
        };
    }

    private void simulateTaskProcessingOnSession(Session session) {
        session.getSession().setAttribute("processing", true);
        Executors.newSingleThreadScheduledExecutor().schedule(
            () -> session.getSession().setAttribute("processing", false),
            1L, TimeUnit.SECONDS
        );
    }
}