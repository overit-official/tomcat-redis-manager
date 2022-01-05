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
import java.util.Set;

/**
 * Concrete implementation of the <b>Store</b> interface that utilizes
 * a Redis server to store the sessions. Those that are
 * saved are still subject to being expired based on inactivity.
 *
 * @author Alessandro Modolo
 * @author Mauro Manfrin
 */
public final class RedisStore extends StoreBase {

    private static final Log log = LogFactory.getLog(RedisStore.class);


    private String prefix = "tomcat";


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
            if (log.isDebugEnabled()) {
                log.debug("Error counting sessions", e);
            }
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
            if (log.isDebugEnabled()) {
                log.debug("Error deleting sessions", e);
            }
        }
    }


    @Override
    public String[] expiredKeys() {

        try {
            return RedisConnector.instance().execute(j -> {
                Set<String> s = j.zrangeByScore(getIndexKey(), "0", Long.toString(System.currentTimeMillis()));
                return s.toArray(new String[]{});
            });

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error listing sessions");
            }
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
                Set<String> s = j.zrange(getIndexKey(), 0, -1);
                return s.toArray(new String[0]);
            });
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error listing sessions", e);
            }
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
            byte[] key = getSessionKey(id).getBytes(StandardCharsets.UTF_8);
            byte[] raw = RedisConnector.instance().execute(j -> {
                Transaction t = j.multi();
                t.get(key);
                t.del(key);
                t.zrem(getIndexKey(), id);
                List<Object> r = t.exec();

                return (byte[]) r.get(0);
            });

            try (ObjectInputStream input = getObjectInputStream(new ByteArrayInputStream(raw))) {
                StandardSession session = (StandardSession) manager.createEmptySession();
                session.readObjectData(input);
                session.setManager(manager);
                return session;
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error loading session", e);
            }
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
            if (log.isDebugEnabled()) {
                log.debug("Error removing session", e);
            }
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
            if (log.isDebugEnabled()) {
                log.debug("Error unloading session", e);
            }
            throw new IOException(e);
        }
    }


    @Override
    protected synchronized void stopInternal() throws LifecycleException {
        RedisConnector.dispose();
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


}
