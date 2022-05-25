package com.overit.tomcat.redis;

import org.apache.catalina.*;
import org.apache.catalina.session.PersistentManager;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.session.StoreBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.Transaction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Concrete implementation of the <b>Store</b> interface that utilizes
 * a Redis server to store the sessions. Those that are
 * saved are still subject to being expired based on inactivity.
 *
 * @author Alessandro Modolo
 * @author Mauro Manfrin
 */
public class RedisStore extends StoreBase implements LifecycleListener {

    private enum Activation {
        AUTO,
        MANUAL;

        static Activation parse(String activation) {
            switch (activation.trim().toLowerCase()) {
                case "manual": return MANUAL;
                case "auto": return AUTO;
                default: throw new IllegalArgumentException("unsupported activation mode");
            }
        }
    }
    private static final Log log = LogFactory.getLog(RedisStore.class);
    private static final int MAX_AWAITING_LOADING_TIME = 5 * 60 * 1000; // 5min
    private static final String COUNTING_SESSIONS_ERROR = "Error counting sessions";
    private static final String LISTING_SESSIONS_ERROR = "Error listing sessions";
    private static final String LOADING_SESSION_ERROR = "Error loading session";
    private static final String REMOVING_SESSION_ERROR = "Error removing session";
    private static final String DELETING_SESSIONS_ERROR = "Error deleting sessions";
    private static final String UNLOADING_SESSION_ERROR = "Error unloading session";
    static final String STORE_NAME = "redisStore";

    private String prefix = "tomcat";
    private final Set<String> drainedSessions = new HashSet<>();

    private Activation activation = Activation.AUTO;

    /**
     * Set the prefix of the keys whose contains the serialized sessions. Those to avoid possible conflicts if the
     * same redis instance is shared between multiple applications. If not specified, the default prefix value is
     * {@code tomcat}.
     *
     * @param prefix the predix string that prepend the entries whose contain the sessions.
     */
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    String getPrefix() {
        return prefix;
    }

    /**
     * Set the connection string to the Redis service used by this store
     *
     * @param url the redis connection string
     */
    public void setUrl(String url) {
        RedisConnector.setUrl(url);
    }

    /**
     * Set the timeout to establish the connection with the Redis service
     *
     * @param connectionTimeout timeout expressed in millis
     */
    public void setConnectionTimeout(int connectionTimeout) {
        RedisConnector.setConnectionTimeout(connectionTimeout);
    }

    /**
     * Set the timeout to retrieve the response from the Redis service
     *
     * @param soTimeout timeout expressed in millis
     */
    public void setSoTimeout(int soTimeout) {
        RedisConnector.setSoTimeout(soTimeout);
    }

    /**
     * Set the activation mode. Possible values are:
     * <ul>
     *     <li>{@code auto}: this store will be automatically enabled (default)</li>
     *     <li>{@code manual}: this store will be enabled only if {@code tomcat.redis.manager.enabled=true} is passed
     *     as env variable or java property</li>
     * </ul>
     * @param activation string representing the activation mode
     */
    public void setActivation(String activation) {
        this.activation = Activation.parse(activation);
    }


    /**
     * Return the name for this Store, used for logging.
     */
    @Override
    public String getStoreName() {
        return STORE_NAME;
    }

    @Override
    protected synchronized void startInternal() throws LifecycleException {
        super.startInternal();
        if (isEnabled()) {
            getManager().getContext().addLifecycleListener(this);
        } else {
            ((PersistentManager)getManager()).setMaxIdleSwap(-1);
        }
    }

    @Override
    protected synchronized void stopInternal() throws LifecycleException {
        getConnector().stop();
        getSubscriberServiceManager().stop();
        getManager().getContext().removeLifecycleListener(this);
        super.stopInternal();
    }

    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        if (Lifecycle.AFTER_START_EVENT.equals(event.getType())) {
            // ensure to subscribe to the redis channel after the context start in order to give the opportunity,
            // to the application, to programmatically set the connector URL
            subscribeToSessionDrainRequests();
        }
    }

    /**
     * Return the number of Sessions present in this Store.
     */
    @Override
    public int getSize() {
        try {
            long s = getConnector().execute(j -> j.zcount(getIndexKey(), "-inf", "+inf"));
            return (int) s;
        } catch (Exception e) {
            logDebug(COUNTING_SESSIONS_ERROR, e);
        }
        return 0;
    }

    /**
     * Remove all the Sessions in this Store.
     */
    @Override
    public void clear() {
        try {
            getConnector().del(getSessionKey("*"), "string");
            getConnector().execute(j -> {
                j.del(getIndexKey());
                return null;
            });

        } catch (Exception e) {
            logDebug(DELETING_SESSIONS_ERROR, e);
        }
    }


    @Override
    public String[] expiredKeys() {

        try {
            return getConnector().execute(j -> {
                List<String> s = j.zrangeByScore(getIndexKey(), "0", Long.toString(System.currentTimeMillis()));
                return s.toArray(new String[]{});
            });

        } catch (Exception e) {
            logDebug(LISTING_SESSIONS_ERROR, e);
            return new String[0];
        }
    }

    /**
     * Return an array containing the session identifiers of all Sessions
     * currently saved in this Store. If there are no such Sessions, a
     * zero-length array is returned.
     */
    @Override
    public String[] keys() {
        try {
            return getConnector().execute(j -> {
                List<String> s = j.zrange(getIndexKey(), 0, -1);
                return s.toArray(new String[0]);
            });
        } catch (Exception e) {
            logDebug(LISTING_SESSIONS_ERROR, e);
            return new String[0];
        }
    }


    /**
     * Load and return the Session associated with the specified session
     * identifier from this Store, without removing it.  If there is no
     * such stored Session, return <code>null</code>.
     *
     * @param id Session identifier of the session to load
     */
    @Override
    public Session load(String id) {

        Context context = getManager().getContext();
        ClassLoader oldThreadContextCL = context.bind(Globals.IS_SECURITY_ENABLED, null);

        try {
            byte[] raw = loadSession(id);
            if (raw != null) return restoreSession(raw);

            long now = System.currentTimeMillis();
            return askForSessionDraining(id, now, true)
                ? awaitAndLoad(id, now)
                : null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logDebug(LOADING_SESSION_ERROR, e);
            return null;
        } catch (Exception e) {
            logDebug(LOADING_SESSION_ERROR, e);
            return null;
        } finally {
            context.unbind(Globals.IS_SECURITY_ENABLED, oldThreadContextCL);
        }
    }


    /**
     * Remove the Session with the specified session identifier from
     * this Store, if present.  If no such Session is present, this method
     * takes no action.
     *
     * @param id Session identifier of the Session to be removed
     */
    @Override
    public void remove(String id) {
        if (isSessionDrained(id)) return;

        try {
            getConnector().execute(j -> {
                Transaction t = j.multi();
                t.del(getSessionKey(id));
                t.zrem(getIndexKey(), id);
                t.exec();
                return null;
            });
        } catch (Exception e) {
            logDebug(REMOVING_SESSION_ERROR, e);
        }
    }


    /**
     * Save the specified Session into this Store.  Any previously saved
     * information for the associated session identifier is replaced.
     *
     * @param session Session to be saved
     * @throws NotSerializableException if the session cannot be serialized
     */
    @Override
    public void save(Session session) throws IOException {

        if (!isEnabled()) throw new NotSerializableException("store not enabled");
        if (!isSerializable(session)) throw new NotSerializableException(session.getClass().getName());

        String id = session.getIdInternal();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ObjectOutputStream outputStream = new ObjectOutputStream(output);
            ((StandardSession) session).writeObjectData(outputStream);
            outputStream.flush();

            getConnector().execute(j -> {
                long expire = (session.getLastAccessedTime() + (session.getMaxInactiveInterval() * 1000L));
                long ttl = expire - System.currentTimeMillis();
                byte[] key = getSessionKey(id).getBytes(StandardCharsets.UTF_8);

                Transaction t = j.multi();
                t.set(key, output.toByteArray());
                t.pexpire(key, ttl);
                t.zadd(getIndexKey(), expire, id);
                t.exec();

                return null;
            });
        } catch (Exception e) {
            logDebug(UNLOADING_SESSION_ERROR, e);
            throw new IOException(e);
        }
    }

    private String getIndexKey() {
        return getPrefix() + ":sessions";
    }

    private String getSessionKey(String sessionId) {
        return getPrefix() + ":session:" + sessionId;
    }

    private boolean isSerializable(Session session) {
        try {
            new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(session); // NOSONAR: this is intended to be a test to identify the non-serializable session
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    boolean askForSessionDraining(String id, long start, boolean firstRetry) throws InterruptedException {
        if (System.currentTimeMillis() - start > 1000) return false;
        if (firstRetry) sendSessionDrainingRequest(id);
        if (someOneAnsweredMe(id)) return true;
        TimeUnit.MILLISECONDS.sleep(100);
        return askForSessionDraining(id, start, false);
    }

    void sendSessionDrainingRequest(String id) {
        getConnector().publish(RedisSubscriberServiceManager.getInstance().getSubscribeChannel(), id);
    }

    boolean someOneAnsweredMe(String id) {
        return getConnector().execute(client -> {
            String key = getSessionRequestKey(id);
            Transaction t = client.multi();
            t.get(key);
            t.del(key);
            return t.exec().get(0) != null;
        });
    }

    String getSessionRequestKey(String id) {
        return getSessionKey(id) + ":request";
    }

    private StandardSession restoreSession(byte[] raw) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = getObjectInputStream(new ByteArrayInputStream(raw))) {
            StandardSession session = (StandardSession) manager.createEmptySession();
            session.readObjectData(input);
            session.setManager(manager);
            return session;
        }
    }

    byte[] loadSession(String id) {
        byte[] key = getSessionKey(id).getBytes(StandardCharsets.UTF_8);
        return getConnector().execute(j -> {
            Transaction t = j.multi();
            t.get(key);
            t.del(key);
            t.zrem(getIndexKey(), id);
            return (byte[]) t.exec().get(0);
        });
    }

    Session awaitAndLoad(String id, long start) throws Exception {
        if (System.currentTimeMillis() - start > MAX_AWAITING_LOADING_TIME) return null;

        byte[] raw = loadSession(id);
        if (raw != null) return restoreSession(raw);

        TimeUnit.MILLISECONDS.sleep(500);
        return awaitAndLoad(id, start);
    }

    void onSessionDrainRequest(String sessionId) {
        try {
            // do not call the findSession(String) otherwise it calls the load method that fire another session drain broadcast message (indirect loop)
            Session session = findSessionById(sessionId);
            if (session == null) return;

            String key = getSessionRequestKey(sessionId);
            getConnector().execute(client -> client.setex(key, 5, "true"));

            while (isProcessing(session)) {
                // wait until the end of te processing
            }

            passivateAndDrain(session);
        } catch (IOException e) {
            logDebug("error loading/saving session", e);
        }
    }

    private Session findSessionById(String sessionId) {
        Session[] sessions = getManager().findSessions();
        return Stream.of(sessions)
            .filter(s -> s.getIdInternal().equals(sessionId))
            .findAny()
            .orElse(null);
    }

    private void passivateAndDrain(Session session) throws IOException {
        if (session instanceof StandardSession) {
            StandardSession standardSession = ((StandardSession) session);
            standardSession.passivate();
            save(standardSession);
            markSessionAsDrained(standardSession);
            standardSession.invalidate();
        }
    }

    private boolean isProcessing(Session session) {
        return Boolean.TRUE.equals(session.getSession().getAttribute("processing"));
    }

    private void markSessionAsDrained(Session session) {
        drainedSessions.add(session.getIdInternal());
    }

    private boolean isSessionDrained(String id) {
        return drainedSessions.contains(id);
    }

    private void logDebug(String message, Throwable e) {
        if (log.isDebugEnabled()) log.debug(message, e);
    }

    RedisConnector getConnector() {
        return RedisConnector.instance();
    }

    RedisSubscriberServiceManager getSubscriberServiceManager() {
        return RedisSubscriberServiceManager.getInstance();
    }

    void subscribeToSessionDrainRequests() {
        getSubscriberServiceManager().subscribe(this, this::onSessionDrainRequest);
    }

    private boolean isEnabled() {
        if (activation == Activation.AUTO) return true;

        String enabled = System.getProperty("tomcat.redis.manager.enabled");
        if (enabled == null) enabled = System.getenv("tomcat.redis.manager.enabled");
        return Boolean.parseBoolean(enabled);
    }
}
