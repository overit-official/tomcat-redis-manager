package com.overit.tomcat.redis;

import org.apache.catalina.Store;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

class RedisSubscriberService implements Runnable {

    private static final String SESSION_DRAINING_CHANNEL = "SESSION_DRAINING_CHANNEL";

    private final Map<Store, Consumer<String>> subscribers = new ConcurrentHashMap<>();
    private final JedisPubSub subscriber = new JedisPubSub() {
        @Override
        public void onMessage(String channel, String sessionId) {
            subscribers.forEach((store, handler) -> handler.accept(sessionId));
        }
    };

    @Override
    public void run() {
        RedisConnector.instance().subscribe(subscriber, getSubscribeChannel());
    }

    public void subscribe(Store store, Consumer<String> handler) {
        subscribers.putIfAbsent(store, handler);
    }

    public void unsubscribe() {
        if (subscriber.isSubscribed()) subscriber.unsubscribe();
        subscribers.clear();
    }

    public String getSubscribeChannel() {
        return SESSION_DRAINING_CHANNEL;
    }
}
