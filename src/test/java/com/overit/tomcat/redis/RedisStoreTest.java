package com.overit.tomcat.redis;

import com.overit.tomcat.TesterContext;
import com.overit.tomcat.TesterServletContext;
import jakarta.servlet.http.HttpSessionActivationListener;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Session;
import org.apache.catalina.session.PersistentManager;
import org.apache.catalina.session.StandardSession;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.io.NotSerializableException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RedisStoreTest {
    @Spy
    private RedisStore store;
    @Captor
    private ArgumentCaptor<Session> sessionArgumentCaptor;

    private PersistentManager manager;

    @BeforeEach
    public void setUp() throws Exception {
        TesterContext testerContext = new TesterContext();
        testerContext.setServletContext(new TesterServletContext());
        manager = new PersistentManager();
        manager.setStore(store);
        manager.setContext(testerContext);
        store.setUrl(String.format("redis://%s:%d", "localhost", 6379));

        store.save(createSession("s1"));
        store.save(createSession("s2"));
    }

    @AfterEach
    public void cleanup() throws Exception {
        RedisConnector.instance().del("*", "*");
        store.clear();
        store.stop();
    }

    @Test
    void getSize() {
        assertThat(store.getSize()).isEqualTo(2);
    }

    @Test
    void getSize_whenRedisIsBroken_shouldReturnZero() {
        // given
        try (MockedStatic<RedisConnector> ignored = mockStatic(RedisConnector.class)) {
            when(RedisConnector.instance()).thenReturn(null);

            // when
            int size = store.getSize();

            // then
            assertThat(size).isZero();
        }
    }

    @Test
    void clear() {
        store.clear();
        assertThat(store.getSize()).isZero();
    }
    @Test
    void clear_whenRedisIsBroken_shouldNotThrows() {
        // given
        try (MockedStatic<RedisConnector> ignored = mockStatic(RedisConnector.class)) {
            when(RedisConnector.instance()).thenReturn(null);

            // then
            assertThatNoException().isThrownBy(store::clear);
        }
    }

    @Test
    void keys() {
        assertThat(store.keys()).containsExactlyInAnyOrder("s1", "s2");
        store.clear();
        assertThat(store.keys()).isEmpty();
    }

    @Test
    void expiredKeys_givenAnExpiredSession_shouldReturnItsId() throws InterruptedException, IOException {
        // given
        String expected = UUID.randomUUID().toString();
        Session session = createSession(expected);
        session.getSession().setMaxInactiveInterval(1);
        store.save(session);
        TimeUnit.SECONDS.sleep(2);

        // when
        String[] expiredKeys = store.expiredKeys();

        // then
        assertThat(expiredKeys).contains(expected);
    }

    @Test
    void expiredKeys_givenNoExpiredSession_shouldReturnEmptyArray() {
        // given
        String expected = UUID.randomUUID().toString();
        Session session = createSession("expired");

        // when
        String[] expiredKeys = store.expiredKeys();

        // then
        assertThat(expiredKeys).isEmpty();
    }

    @Test
    void remove() {
        store.remove("s1");
        assertThat(store.getSize()).isEqualTo(1);
    }

    @Test
    void add() throws IOException {
        store.save(createSession("s3"));
        assertThat(store.getSize()).isEqualTo(3);
        assertThat(store.keys()).containsExactlyInAnyOrder("s1", "s2", "s3");
    }

    @Test
    void save_nonSerializableSession_shouldThrows() throws IOException {
        // given
        Session session = createSession("s");
        session.getSession().setAttribute("notserializable", new Object());

        // when()
        Assertions.assertThatExceptionOfType(NotSerializableException.class)
            .isThrownBy(() -> store.save(session));
    }

    @Test
    void load() {
        Session s2 = store.load("s2");
        assertThat(s2.getIdInternal()).isEqualTo("s2");
    }

    @Test
    void load_havingNoStoredSessionAndNoOneCanDrainIt_shouldReturnNullIn2Seconds() {
        // given
        long start = System.currentTimeMillis();

        // when
        Session session = store.load("unknown");

        // then
        long end = System.currentTimeMillis();
        assertThat(session).isNull();
        assertThat(end - start).isBetween(1000L, 1500L);
    }

    @Test
    void load_havingNoStoredSessionButSomeoneThatCanDrainIt_shouldReturnNotNull() throws Exception {
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
    void onSessionDrainRequest_whenReceiveARequestNotification_shouldCallTheMethod() throws InterruptedException {
        // when
        store.subscribeToSessionDrainRequests();
        store.sendSessionDrainingRequest("");
        TimeUnit.MILLISECONDS.sleep(100);

        // then
        verify(store).onSessionDrainRequest("");
    }

    @Test
    void onSessionDrainRequest_whenReceiveARequestNotificationOfUnknownSession_shouldNotWriteAnything() {
        // when
        store.sendSessionDrainingRequest("unknown");

        // then
        String key = store.getSessionRequestKey("unknown");
        String value = RedisConnector.instance().execute(client -> client.get(key));
        assertThat(value).isNull();
    }

    @Test
    void onSessionDrainRequest_whenReceiveARequestNotification_shouldAddResponseIntoRedisEntry() throws InterruptedException {
        // when
        store.sendSessionDrainingRequest("s1");
        TimeUnit.MILLISECONDS.sleep(100);

        // then
        String key = store.getSessionRequestKey("s1");
        String value = RedisConnector.instance().execute(client -> client.get(key));
        assertThat(value).isEqualTo("true");
    }

    @Test
    void onSessionDrainRequest_whenReceiveARequestNotification_shouldSaveTheSession() throws InterruptedException {
        // when
        store.subscribeToSessionDrainRequests();
        createSession("sd");
        store.sendSessionDrainingRequest("sd");
        TimeUnit.MILLISECONDS.sleep(500);

        // then
        byte[] session = store.loadSession("sd");
        assertThat(session).isNotEmpty();
    }

    @Test
    void onSessionDrainingRequest_whenReceiveARequestForAProcessingSession_shouldWaitTheProcessingComplete() throws IOException {
        // give
        Session session = createSession("s3");
        simulateTaskProcessingOnSession(session);

        // when
        store.onSessionDrainRequest("s3");

        // then
        verify(store, atLeastOnce()).save(sessionArgumentCaptor.capture());
        assertThat(sessionArgumentCaptor.getValue().isValid()).isFalse();
    }

    @Test
    void onSessionDrainingRequest_whenReceiveARequestForUnknownSession_shouldNotCallTheLoadMethod() {
        // given

        // when
        store.onSessionDrainRequest("unknown");

        // then
        verify(store, never()).load(any());
    }
    @Test
    void onSessionDrainingRequest_whenReceiveARequestForUnknownSession_shouldBeCalledOnce() throws InterruptedException {

        // when
        store.onSessionDrainRequest("unknown");
        TimeUnit.SECONDS.sleep(1);

        // then
        verify(store).onSessionDrainRequest(any());
    }

    @Test
    void onSessionDrainingRequest_receivedKnownSessionId_shouldPassivateAndSaveIt() throws IOException {
        // given
        Session s = createSession("ss");
        HttpSessionActivationListener listener = mock(HttpSessionActivationListener.class);
        s.getSession().setAttribute("bean", listener);

        // when
        store.onSessionDrainRequest("ss");

        // then
        verify(listener).sessionWillPassivate(any());
        verify(store).save(s);
    }
    @Test
    void onSessionDrainingRequest_receivedKnownSessionId_shouldInvalidateTheSession() {
        // given
        Session s = createSession("s");

        // when
        store.onSessionDrainRequest("s");

        // then
        assertThat(s.isValid()).isFalse();
    }

    @Test
    void setPrefix_givenAString_shouldSetIt() {
        // given
        String expected = UUID.randomUUID().toString();

        // when
        store.setPrefix(expected);

        // then
        assertThat(store.getPrefix()).isEqualTo(expected);
    }

    @Test
    void getStoreName_shouldReturnTheExceptedConstant() {
        assertThat(store.getStoreName()).isEqualTo(RedisStore.STORE_NAME);
    }

    @Test
    void setConnectionTimeout_givenAValidNumber_shouldSetTheRedisConnectorTimeout() {
        // given
        int expected = 123456789;

        // when
        store.setConnectionTimeout(expected);

        // then
        assertThat(RedisConnector.getConnectionTimeout()).isEqualTo(expected);
    }

    @Test
    void setSoTimeout_givenAValidNumber_shouldSetTheRedisConnectorTimeout() {
        // given
        int expected = 123456789;

        // when
        store.setSoTimeout(expected);

        // then
        assertThat(RedisConnector.getSoTimeout()).isEqualTo(expected);
    }

    @Test
    void stopInternal_shouldStopRedisConnectorAndSubscriber() throws LifecycleException {
        // given
        store.start();
        RedisConnector connector = mock(RedisConnector.class);
        when(store.getConnector()).thenReturn(connector);
        RedisSubscriberServiceManager subscriberServiceManager = mock(RedisSubscriberServiceManager.class);
        when(store.getSubscriberServiceManager()).thenReturn(subscriberServiceManager);

        // when
        store.stop();

        // then
        verify(connector).stop();
        verify(subscriberServiceManager).stop();
    }

    @Test
    void stopInternal_shouldUnsubscribeAllSubscriber() throws LifecycleException {
        // given
        store.start();
        RedisConnector connector = mock(RedisConnector.class);
        when(store.getConnector()).thenReturn(connector);
        RedisSubscriberService service = mock(RedisSubscriberService.class);
        RedisSubscriberServiceManager subscriberServiceManager = new RedisSubscriberServiceManager(service);
        when(store.getSubscriberServiceManager()).thenReturn(subscriberServiceManager);

        // when
        store.stop();

        // then
        verify(service).unsubscribe();
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