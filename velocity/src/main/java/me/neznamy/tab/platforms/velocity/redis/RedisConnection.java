package me.neznamy.tab.platforms.velocity.redis;

import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class RedisConnection {

    private JedisPool pool;
    private final String host, password;
    private final int port;
    private final String cluster;

    public RedisConnection(String host, int port, @Nullable String password, String cluster) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.cluster = cluster + ":";
    }

    private void connect() {
        try {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(12);

            if (password != null && !password.isEmpty()) {
                pool = new JedisPool(config, host, port, 10000, password);
            } else {
                pool = new JedisPool(config, host, port, 10000);
            }

            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }

        } catch (JedisConnectionException e){
            e.printStackTrace();
        }
    }

    public void disconnect() {
        if (pool != null)
            pool.close();
    }

    public JedisPool getPool() {
        if (pool == null) {
            connect();
        }

        return pool;
    }

    public String getCluster() {
        return cluster;
    }
}
