package com.saas.foundation.ratelimit;

import java.time.Duration;

import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.resource.ClientResources;
import io.netty.resolver.DefaultAddressResolverGroup;

@Configuration
public class RateLimitConfig {

    /**
     * Lettuce's Netty DNS resolver fails on Alpine (the Render image) and reports the
     * host as {@code <unresolved>}. The JVM resolver uses the container DNS config.
     */
    @Bean
    ClientResourcesBuilderCustomizer lettuceJvmDnsResolver() {
        return builder -> builder.addressResolverGroup(DefaultAddressResolverGroup.INSTANCE);
    }

    @Bean(destroyMethod = "shutdown")
    public RedisClient bucket4jRedisClient(RedisProperties redisProperties, ClientResources clientResources) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(redisProperties.getHost())
                .withPort(redisProperties.getPort())
                .withTimeout(redisProperties.getTimeout() != null
                        ? redisProperties.getTimeout()
                        : Duration.ofSeconds(2));

        if (redisProperties.getSsl().isEnabled()) {
            builder.withSsl(true);
        }
        String password = redisProperties.getPassword();
        String username = redisProperties.getUsername();
        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            builder.withAuthentication(username, password);
        } else if (password != null && !password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }
        builder.withDatabase(redisProperties.getDatabase());
        return RedisClient.create(clientResources, builder.build());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> bucket4jRedisConnection(RedisClient bucket4jRedisClient) {
        return bucket4jRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> bucket4jProxyManager(
            StatefulRedisConnection<String, byte[]> bucket4jRedisConnection,
            RateLimitProperties properties) {
        Duration keepAfterRefill = properties.window().multipliedBy(2);
        return Bucket4jLettuce.casBasedBuilder(bucket4jRedisConnection)
            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(keepAfterRefill))
            .build();
    }

    @Bean
    public BucketConfiguration tenantBucketConfiguration(RateLimitProperties properties) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(properties.capacity())
                .refillGreedy(properties.capacity(), properties.window())
                .build();
        return BucketConfiguration.builder()
                .addLimit(limit)
                .build();
    }
}
