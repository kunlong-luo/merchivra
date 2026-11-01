package org.merchivra.erp.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Only explicit opt-in moves Spring Security sessions/CSRF to shared Redis.
 * Local single-node development continues to use container HttpSession.
 * If Redis is unavailable while enabled, the session repository raises an
 * error; do not transparently downgrade to a new in-memory session.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "merchivra.redis", name = "sessions-enabled", havingValue = "true")
@EnableRedisHttpSession(redisNamespace = "merchivra:session:v1",
    maxInactiveIntervalInSeconds = 14400)
public class RedisSessionConfiguration {
}
