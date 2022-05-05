package com.overit.tomcat.redis;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class RedisSubscriberServiceManager {
    private static RedisSubscriberServiceManager instance;
    private final RedisSubscriberService service;
    private Future<?> task;

    public static synchronized RedisSubscriberServiceManager getInstance() {
        if (instance == null) {
            instance = new RedisSubscriberServiceManager(new RedisSubscriberService());
        }
        return instance;
    }

    public RedisSubscriberServiceManager(RedisSubscriberService service) {
        this.service = service;
        start();
    }

    void start() {
        task = Executors.newSingleThreadExecutor().submit(service);
    }

    void stop() {
        service.unsubscribe();
        task.cancel(true);
    }

    public RedisSubscriberService getService() {
        return service;
    }

    public String getSubscribeChannel() {
        return service.getSubscribeChannel();
    }
}
