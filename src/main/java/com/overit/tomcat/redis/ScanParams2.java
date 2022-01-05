package com.overit.tomcat.redis;

import redis.clients.jedis.ScanParams;

import java.util.ArrayList;
import java.util.Collection;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * local implementation waiting for jedis to implement type scan parameter.
 *
 * @author Mauro Manfrin
 */
public class ScanParams2 extends ScanParams {

    private static final byte[] TYPE_PARAM = "TYPE".getBytes(UTF_8);

    protected byte[] type;
    
    @Override
    public Collection<byte[]> getParams() {
        
        
        Collection<byte[]> p = new ArrayList<>(super.getParams());
        if (type != null) {
            p.add(TYPE_PARAM);
            p.add(type);
        }

        return p;
    }

    public void type(String type) {
        this.type = type.getBytes(UTF_8);
    }
}