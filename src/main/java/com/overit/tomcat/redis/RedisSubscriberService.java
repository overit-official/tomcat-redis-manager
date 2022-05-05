package com.overit.tomcat.redis;

import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

class RedisSubscriberService implements Runnable {

    private static final String SESSION_DRAINING_CHANNEL = "SESSION_DRAINING_CHANNEL";

    private final List<Consumer<String>> subscribers = new CopyOnWriteArrayList<>();
    private final JedisPubSub subscriber = new JedisPubSub() {
        @Override
        public void onMessage(String channel, String sessionId) {
            subscribers.forEach(handler -> handler.accept(sessionId));
        }
    };

    @Override
    public void run() {
        RedisConnector.instance().subscribe(subscriber, getSubscribeChannel());
    }

    public void subscribe(Consumer<String> handler) {
        subscribers.add(handler);
    }

    public void unsubscribe(Consumer<String> handler) {
        subscribers.remove(handler);
    }

    public void unsubscribe() {
        subscriber.unsubscribe();
        subscribers.clear();
    }

    public String getSubscribeChannel() {
        return SESSION_DRAINING_CHANNEL;
    }
}
