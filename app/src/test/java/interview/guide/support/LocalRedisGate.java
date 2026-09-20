package interview.guide.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 本地 dev Redis 可用性门控（真实 Redis 集成测试用）。
 *
 * <p>为什么需要它：会话缓存的**序列化往返**与 **TTL** 是 Redisson 二进制编解码的特定行为，
 * mock 掉 RedisService 就永远测不到（字段加漏了只在真实读写时才丢）。因此这类断言连真 Redis；
 * 但与真实 DB 集成测试同理，环境不可达必须**整类跳过**而不是报错，
 * 否则 CI 或没起 Redis 的机器上会红一片。
 *
 * <p>与 {@link LocalDatabaseGate} 一样，判据必须在 Spring 上下文创建之前求值，
 * 用类级 {@code @EnabledIf("interview.guide.support.LocalRedisGate#available")}。
 */
public final class LocalRedisGate {

  /** 探测超时（毫秒）：只判断端口是否可连 */
  private static final int PROBE_TIMEOUT_MS = 1000;

  private static final String HOST = resolve("REDIS_HOST", "localhost");

  private static final int PORT = parsePort(resolve("REDIS_PORT", "6379"));

  private LocalRedisGate() {
  }

  public static boolean available() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(HOST, PORT), PROBE_TIMEOUT_MS);
      return true;
    } catch (IOException e) {
      System.out.println("[LocalRedisGate] Redis 不可达（" + HOST + ":" + PORT + "），相关测试类跳过："
          + e.getMessage());
      return false;
    }
  }

  /**
   * 真实 Redis 集成测试的完整判据：**上下文启动需要数据库**（Flyway + JPA 是 @SpringBootTest 的前置），
   * 断言需要 Redis。两者都就绪才跑，否则整类跳过。
   */
  public static boolean availableForContext() {
    return LocalDatabaseGate.available() && available();
  }

  public static String host() {
    return HOST;
  }

  public static int port() {
    return PORT;
  }

  /** 环境变量优先，其次项目根 .env（测试与 bootRun 不同 JVM，.env 不会自动加载） */
  private static String resolve(String key, String defaultValue) {
    String fromEnv = System.getenv(key);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    String fromDotenv = LocalDatabaseGate.DOTENV != null ? LocalDatabaseGate.DOTENV.get(key) : null;
    return fromDotenv != null && !fromDotenv.isBlank() ? fromDotenv : defaultValue;
  }

  private static int parsePort(String raw) {
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return 6379;
    }
  }
}
