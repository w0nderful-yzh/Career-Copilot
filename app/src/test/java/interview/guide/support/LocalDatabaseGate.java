package interview.guide.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 本地 dev 数据库可用性门控与连接配置解析（真实 DB 集成测试共用）。
 *
 * <p>为什么需要它：集成测试连的是**共享的 dev 库**，凭据来自环境变量或项目根 `.env`；
 * 环境不可达时必须整类跳过，而不是报错。
 *
 * <p>为什么不能用方法内 {@code assumeTrue}：Spring 上下文（含 Flyway 迁移）在方法体执行前
 * 就已初始化，连不上库时整类报错而非跳过。因此判据必须在上下文创建之前求值，
 * 即类级 {@code @EnabledIf("interview.guide.support.LocalDatabaseGate#available")}。
 */
public final class LocalDatabaseGate {

  /** 探测超时（毫秒）：只判断端口是否可连，不等待业务响应 */
  private static final int PROBE_TIMEOUT_MS = 1000;

  /** .env 全量键值（对齐 bootRun 的注入行为），供上下文补齐 APP_AI_* 等非数据源配置 */
  // 注意：DATASOURCE 的解析依赖本字段，声明顺序必须在其之前
  public static final Map<String, String> DOTENV = loadDotenv();

  /** 解析后的数据库连接配置；null 表示凭据缺失，测试将跳过 */
  public static final Map<String, String> DATASOURCE = resolveDatasource();

  private LocalDatabaseGate() {
  }

  /** 凭据可解析且端口可连通才运行（类级 {@code @EnabledIf} 的入口） */
  public static boolean available() {
    if (DATASOURCE == null) {
      return false;
    }
    String host = DATASOURCE.get("host");
    int port = Integer.parseInt(DATASOURCE.get("port"));
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
      return true;
    } catch (IOException e) {
      // 端口未监听/网络不可达：视为环境未就绪，跳过而非失败
      System.out.println("[LocalDatabaseGate] 数据库不可达（" + host + ":" + port + "），相关测试类跳过："
          + e.getMessage());
      return false;
    }
  }

  public static String url() {
    return DATASOURCE != null ? DATASOURCE.get("url") : null;
  }

  public static String username() {
    return DATASOURCE != null ? DATASOURCE.get("username") : null;
  }

  public static String password() {
    return DATASOURCE != null ? DATASOURCE.get("password") : null;
  }

  /**
   * 数据库配置解析顺序：环境变量（POSTGRES_*，与 application.yml/docker-compose 同源）
   * → 项目根 .env。任一路径给出凭据即视为环境可用。
   */
  private static Map<String, String> resolveDatasource() {
    String host = envOrDotenv("POSTGRES_HOST");
    String user = envOrDotenv("POSTGRES_USER");
    String password = envOrDotenv("POSTGRES_PASSWORD");
    if (password == null) {
      return null;
    }
    String resolvedHost = host != null ? host : "localhost";
    String resolvedPort = envOrDotenvOrDefault("POSTGRES_PORT", "5432");
    Map<String, String> config = new HashMap<>();
    config.put("host", resolvedHost);
    config.put("port", resolvedPort);
    config.put("url", "jdbc:postgresql://"
        + resolvedHost + ":"
        + resolvedPort + "/"
        + envOrDotenvOrDefault("POSTGRES_DB", "interview_guide"));
    config.put("username", user != null ? user : "postgres");
    config.put("password", password);
    return config;
  }

  /** 环境变量优先；为空时尝试从项目根 .env 读取（bootRun 与测试不同 JVM，.env 不会自动加载） */
  private static String envOrDotenv(String key) {
    String value = System.getenv(key);
    if (value != null && !value.isBlank()) {
      return value;
    }
    return DOTENV != null ? DOTENV.get(key) : null;
  }

  /** 全量解析 .env 为有序 Map（跳过注释与空行），找不到文件返回 null */
  private static Map<String, String> loadDotenv() {
    Path envFile = findEnvFile();
    if (envFile == null) {
      return null;
    }
    try {
      Map<String, String> values = new HashMap<>();
      for (String line : Files.readAllLines(envFile)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String key = trimmed.substring(0, eq).trim();
        String value = trimmed.substring(eq + 1).trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'"))) {
          value = value.substring(1, value.length() - 1);
        }
        values.put(key, value);
      }
      return values;
    } catch (IOException e) {
      return null;
    }
  }

  /** 环境变量 → .env → 默认值，避免 .env 中配置的端口被忽略 */
  private static String envOrDotenvOrDefault(String key, String defaultValue) {
    String value = envOrDotenv(key);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }

  /**
   * 从当前目录向上查找仓库根的 .env（Gradle test worker 的 user.dir 是 app/ 子目录，
   * 与 bootRun 的 rootProject.file('.env') 不同源，需自行向上定位）。
   */
  private static Path findEnvFile() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 4 && dir != null; i++) {
      Path candidate = dir.resolve(".env");
      if (Files.isReadable(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
