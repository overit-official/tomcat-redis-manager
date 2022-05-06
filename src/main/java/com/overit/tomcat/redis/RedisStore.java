package com.overit.tomcat.redis;

import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Session;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.session.StoreBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.Transaction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Concrete implementation of the <b>Store</b> interface that utilizes
 * a Redis server to store the sessions. Those that are
 * saved are still subject to being expired based on inactivity.
 *
 * @author Alessandro Modolo
 * @author Mauro Manfrin
 */
public class RedisStore extends StoreBase {

    private static final Log log = LogFactory.getLog(RedisStore.class);
    private static final int MAX_AWAITING_LOADING_TIME = 5 * 60 * 1000; // 5min
    private static final String COUNTING_SESSIONS_ERROR = "Error counting sessions";
    private static final String LISTING_SESSIONS_ERROR = "Error listing sessions";
    private static final String LOADING_SESSION_ERROR = "Error loading session";
    private static final String REMOVING_SESSION_ERROR = "Error removing session";
    private static final String DELETING_SESSIONS_ERROR = "Error deleting sessions";
    private static final String UNLOADING_SESSION_ERROR = "Error unloading session";

    private String prefix = "tomcat";

    public RedisStore() {
        RedisSubscriberServiceManager.getInstance().getService().subscribe(this::onSessionDrainRequest);
    }

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
     * Return the name for this Store, used for logging.
     */
    @Override
    public String getStoreName() {
        return "redisStore";
    }

    /**
     * Return the number of Sessions present in this Store.
     */
    @Override
    public int getSize() {
        try {
            long s = RedisConnector.instance().execute(j -> j.zcount(getIndexKey(), "-inf", "+inf"));
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
            RedisConnector.instance().del(getSessionKey("*"), "string");
            RedisConnector.instance().execute(j -> {
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
            return RedisConnector.instance().execute(j -> {
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
            return RedisConnector.instance().execute(j -> {
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
        try {
            RedisConnector.instance().execute(j -> {
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

        if (!isSerializable(session)) throw new NotSerializableException(session.getClass().getName());

        String id = session.getIdInternal();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ObjectOutputStream outputStream = new ObjectOutputStream(output);
            ((StandardSession) session).writeObjectData(outputStream);
            outputStream.flush();

            RedisConnector.instance().execute(j -> {
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

    @Override
    protected synchronized void stopInternal() throws LifecycleException {
        RedisConnector.dispose();
        RedisSubscriberServiceManager.getInstance().stop();
        super.stopInternal();
    }


    private String getIndexKey() {
        return prefix + ":sessions";
    }

    private String getSessionKey(String sessionId) {
        return prefix + ":session:" + sessionId;
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
        if (System.currentTimeMillis() - start > 2000) return false;
        if (firstRetry) sendSessionDrainingRequest(id);
        if (someOneAnsweredMe(id)) return true;
        TimeUnit.MILLISECONDS.sleep(100);
        return askForSessionDraining(id, start, false);
    }

    void sendSessionDrainingRequest(String id) {
        RedisConnector.instance().publish(RedisSubscriberServiceManager.getInstance().getSubscribeChannel(), id);
    }

    boolean someOneAnsweredMe(String id) {
        return RedisConnector.instance().execute(client -> {
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
        return RedisConnector.instance().execute(j -> {
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

        TimeUnit.MILLISECONDS.sleep(100);
        return awaitAndLoad(id, start);
    }

    void onSessionDrainRequest(String sessionId) {
        try {
            Session session = getManager().findSession(sessionId);
            if (session == null) return;

            String key = getSessionRequestKey(sessionId);
            RedisConnector.instance().execute(client -> {
                Transaction t = client.multi();
                t.set(key, "true");
                t.expire(key, 10);
                return t.exec();
            });


            while (isProcessing(session)) {
                // wait until the end of te processing
            }


            save(session);

        } catch (IOException e) {
            logDebug("error loading/saving session", e);
        }
    }

    private boolean isProcessing(Session session) {
        return Boolean.TRUE.equals(session.getSession().getAttribute("processing"));
    }

    private void logDebug(String message, Throwable e) {
        if (log.isDebugEnabled()) log.debug(message, e);
    }


}
