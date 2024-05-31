package me.neznamy.tab.platforms.velocity.features;

import com.velocitypowered.api.event.Subscribe;
import lombok.AllArgsConstructor;
import me.neznamy.tab.platforms.velocity.VelocityTAB;
import me.neznamy.tab.platforms.velocity.redis.PubSubMessageEvent;
import me.neznamy.tab.platforms.velocity.redis.RedisConnection;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.config.file.ConfigurationFile;
import me.neznamy.tab.shared.features.redis.RedisSupport;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * RedisBungee implementation for Velocity
 */
public class VelocityRedisSupport extends RedisSupport {

    /** Plugin reference for registering listener */
    @NotNull
    private final VelocityTAB plugin;
    private RedisConnection connection;
    private boolean closing;
    private Subscriber subscriber;
    private String CHANNEL;

    public VelocityRedisSupport(@NotNull VelocityTAB plugin) {
        this.plugin = plugin;
    }

    /**
     * Listens to messages coming from other proxies.
     *
     * @param   e
     *          Message event
     */
    @Subscribe
    public void onMessage(PubSubMessageEvent e) {
        if (!e.getChannel().equals(CHANNEL)) return;
        processMessage(e.getMessage());
    }


    @Override
    public void register() {
        TAB tab = TAB.getInstance();
        ConfigurationFile config = tab.getConfiguration().getConfig();

        connection = new RedisConnection(
                config.getString("redis.host"),
                config.getInt("redis.port"),
                config.getString("redis.password"),
                config.getString("redis.cluster"));

        CHANNEL = connection.getCluster() + TabConstants.REDIS_CHANNEL_NAME;

        plugin.getServer().getEventManager().register(plugin, this);
        subscriber = new Subscriber();
        new Thread(subscriber).start();

        /*
        plugin.getServer().getEventManager().register(plugin, this);
        RedisBungeeAPI.getRedisBungeeApi().registerPubSubChannels(TabConstants.REDIS_CHANNEL_NAME);
         */
    }

    @Override
    public void unregister() {
        closing = true;
        subscriber.unsubscribe();

        /*
        plugin.getServer().getEventManager().unregisterListener(plugin, this);
        RedisBungeeAPI.getRedisBungeeApi().unregisterPubSubChannels(TabConstants.REDIS_CHANNEL_NAME);
         */
    }

    @Override
    public void sendMessage(@NotNull String message) {
        try (Jedis jedis = connection.getPool().getResource()) {
            jedis.publish(CHANNEL, message);
        }

        /*
        RedisBungeeAPI.getRedisBungeeApi().sendChannelMessage(TabConstants.REDIS_CHANNEL_NAME, message);
         */
    }

    private class Subscriber extends JedisPubSub implements Runnable {

        @Override
        public void run() {
            boolean first = true;

            while (!closing && !Thread.interrupted() && !connection.getPool().isClosed()) {
                try (Jedis jedis = connection.getPool().getResource()) {
                    if (first) {
                        first = false;
                    } else {
                        plugin.getLogger().info("Reconnected to Redis");
                    }

                    jedis.subscribe(this, CHANNEL);
                } catch (Exception e) {
                    if (closing) {
                        return;
                    }

                    System.out.println("Lost connection to Redis" + e.getMessage());
                    try {
                        unsubscribe();
                    } catch (Exception e2) {
                        e2.printStackTrace();
                    }

                    // Sleep for 5 seconds to prevent massive spam in console
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onMessage(String channel, String message) {
            VelocityRedisSupport.this.onMessage(new PubSubMessageEvent(channel, message));
        }
    }
}