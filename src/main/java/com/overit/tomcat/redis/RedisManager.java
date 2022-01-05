package com.overit.tomcat.redis;


import org.apache.catalina.*;
import org.apache.catalina.security.SecurityUtil;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.CustomObjectInputStream;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import redis.clients.jedis.params.SetParams;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Implementation of the <b>Manager</b> interface that provides
 * simple session persistence into a Redis server across restarts of this component (such as
 * when the entire server is shut down and restarted, or when a particular
 * web application is reloaded).
 * <p>
 * <b>IMPLEMENTATION NOTE</b>:  Correct behavior of session storing and
 * reloading depends upon external calls to the <code>start()</code> and
 * <code>stop()</code> methods of this class at the correct times.
 *
 * @author Alessandro Modolo
 * @author Mauro Manfrin
 */
public class RedisManager extends ManagerBase {

    private final Log log = LogFactory.getLog(RedisManager.class);

    private class PrivilegedDoLoad implements PrivilegedExceptionAction<Void> {

        PrivilegedDoLoad() {
            // NOOP
        }

        @Override
        public Void run() throws Exception {
            doLoad();
            return null;
        }
    }

    private class PrivilegedDoUnload implements PrivilegedExceptionAction<Void> {

        PrivilegedDoUnload() {
            // NOOP
        }

        @Override
        public Void run() throws Exception {
            doUnload();
            return null;
        }

    }


    protected String prefix = "tomcat";

    @Override
    public String getName() {
        return "redisManager";
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
     * Set the prefix of the keys whose contains the serialized sessions. Those to avoid possible conflicts if the
     * same redis instance is shared between multiple applications. If not specified, the default prefix value is
     * {@code tomcat}.
     *
     * @param prefix the predix string that prepend the entries whose contain the sessions.
     */
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }


    @Override
    public void load() throws ClassNotFoundException, IOException {
        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                AccessController.doPrivileged(new PrivilegedDoLoad());
            } catch (PrivilegedActionException ex) {
                Exception exception = ex.getException();
                if (exception instanceof ClassNotFoundException) {
                    throw (ClassNotFoundException) exception;
                } else if (exception instanceof IOException) {
                    throw (IOException) exception;
                }
                if (log.isDebugEnabled()) {
                    log.debug("Unreported exception in load() ", exception);
                }
            }
        } else {
            doLoad();
        }
    }

    private boolean isSessionValidInternal(StandardSession s) {

        try {
            Method m = s.getClass().getMethod("isValidInternal");
            m.setAccessible(true);
            return (Boolean) m.invoke(s);
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Load any currently active sessions that were previously unloaded
     * to the appropriate persistence mechanism, if any.  If persistence is not
     * supported, this method returns without doing anything.
     *
     * @throws ClassNotFoundException if a serialized class cannot be
     *                                found during the reload
     * @throws IOException            if an input/output error occurs
     */
    protected void doLoad() throws ClassNotFoundException, IOException {
        // Initialize our internal data structures
        sessions.clear();


        ClassLoader classLoader = null;

        Context c = getContext();
        Loader loader = c.getLoader();
        Log logger = c.getLogger();
        if (loader != null) {
            classLoader = loader.getClassLoader();
        }
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }


        Set<String> keys;
        try {
            keys = RedisConnector.instance().keys(getSessionKey("*"), "string");
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error listing sessions", e);
            }
            return;
        }


        // Load the previously unloaded active sessions
        synchronized (sessions) {
            byte[] empty = new byte[0];
            for (String key : keys) {
                StandardSession session = getNewSession();

                try {
                    byte[] k = (key).getBytes(StandardCharsets.UTF_8);
                    byte[] s = RedisConnector.instance().execute(j -> {
                        byte[] bb = j.getSet(k, empty);
                        j.del(k);

                        return bb;
                    });

                    ObjectInputStream ois = new CustomObjectInputStream(new ByteArrayInputStream(s), classLoader, logger, getSessionAttributeValueClassNamePattern(), getWarnOnSessionAttributeFilterFailure());
                    session.readObjectData(ois);
                    session.setManager(this);
                    sessions.put(session.getIdInternal(), session);
                    session.activate();
                    if (!isSessionValidInternal(session)) {
                        // If session is already invalid,
                        // expire session to prevent memory leak.
                        session.setValid(true);
                        session.expire();
                    }
                    sessionCounter++;
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error loading session", e);
                    }
                }
            }
        }
    }


    @Override
    public void unload() throws IOException {
        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                AccessController.doPrivileged(new PrivilegedDoUnload());
            } catch (PrivilegedActionException ex) {
                Exception exception = ex.getException();
                if (exception instanceof IOException) {
                    throw (IOException) exception;
                }
                if (log.isDebugEnabled()) {
                    log.debug("Unreported exception in unLoad()", exception);
                }
            }
        } else {
            doUnload();
        }
    }


    /**
     * Save any currently active sessions in the appropriate persistence
     * mechanism, if any.  If persistence is not supported, this method
     * returns without doing anything.
     *
     * @throws IOException if an input/output error occurs
     */
    protected void doUnload() throws IOException {
        if (sessions.isEmpty()) {
            return; // nothing to do
        }

        // Keep a note of sessions that are expired
        List<StandardSession> list = new ArrayList<>();


        synchronized (sessions) {
            // Write the number of active sessions, followed by the details

            for (Session s : sessions.values()) {
                StandardSession session = (StandardSession) s;
                list.add(session);
                session.passivate();

                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos);
                    session.writeObjectData(oos);
                    oos.flush();

                    RedisConnector.instance().execute(j -> {

                        long ttl = (session.getLastAccessedTime() + (session.getMaxInactiveInterval() * 1000L)) - System.currentTimeMillis();
                        byte[] k = (getSessionKey(session.getId())).getBytes(StandardCharsets.UTF_8);
                        j.set(k, baos.toByteArray(), SetParams.setParams().px(ttl));
                        return null;

                    });
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error unloading session", e);
                    }
                }
            }
        }


        // Expire all the sessions we just wrote
        for (StandardSession session : list) {
            try {
                session.expire(false);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
            } finally {
                session.recycle();
            }
        }
    }


    /**
     * Start this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {

        super.startInternal();

        // Load unloaded sessions, if any
        try {
            load();
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("standardManager.managerLoad"), t);
        }

        setState(LifecycleState.STARTING);
    }


    /**
     * Stop this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {

        setState(LifecycleState.STOPPING);

        // Write out sessions
        try {
            unload();
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("standardManager.managerUnload"), t);
        }

        // Expire all active sessions
        Session[] sessions = findSessions();
        for (Session session : sessions) {
            try {
                if (session.isValid()) {
                    session.expire();
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
            } finally {
                // Measure against memory leaking if references to the session
                // object are kept in a shared field somewhere
                session.recycle();
            }
        }

        // Require a new random number generator if we are restarted
        super.stopInternal();
    }


    private String getSessionKey(String sessionId) {
        return prefix + ":session:" + sessionId;
    }
}