package interview.guide.modules.resume.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/**
 * Typst 字体解包器（P2-4）：classpath 打包的 Noto Sans CJK 字体在首次使用时
 * 解包到运行时临时目录，供 typst 二进制通过 {@code --font-path} 加载。
 *
 * <p>为什么需要解包：typst 是独立进程，无法读取 jar 内 classpath 资源；
 * 生产容器内没有系统中文字体，必须随 jar 打包并在运行时落到文件系统。
 *
 * <p>生命周期：懒加载单次解包；进程退出时清理临时目录。
 */
@Slf4j
@Component
public class TypstFontExtractor {

  private static final String FONT_RESOURCE_DIR = "/typst/fonts/";

  private final String fontResources;
  private final Object lock = new Object();
  private volatile Path extractedDir;

  public TypstFontExtractor(
      @Value("${app.resume.typst-font-resources:/typst/fonts/}") String fontResources) {
    this.fontResources = fontResources;
  }

  /**
   * 获取解包后的字体目录；无打包字体时返回 null（typst 回退系统字体，开发机场景）。
   */
  public Path ensureExtracted() {
    Path dir = extractedDir;
    if (dir != null) {
      return dir;
    }
    synchronized (lock) {
      if (extractedDir != null) {
        return extractedDir;
      }
      try {
        extractedDir = extractAll();
        return extractedDir;
      } catch (IOException e) {
        // 解包失败不阻断编译（开发机通常有系统字体）；生产容器内会编译失败并给出明确错误
        log.error("Typst 字体解包失败，编译将回退系统字体: {}", e.getMessage(), e);
        return null;
      }
    }
  }

  private Path extractAll() throws IOException {
    Path target = Files.createTempDirectory("typst-fonts-");
    for (String name : bundledFontNames()) {
      String resource = fontResources + name;
      try (InputStream in = getClass().getResourceAsStream(resource)) {
        if (in == null) {
          log.warn("打包字体资源缺失: {}", resource);
          continue;
        }
        Files.copy(in, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    try (Stream<Path> entries = Files.list(target)) {
      long count = entries.count();
      if (count == 0) {
        log.info("classpath 无打包字体，跳过解包（回退系统字体）");
        cleanupDirectory(target);
        return null;
      }
      log.info("Typst 字体已解包: {} 个文件 -> {}", count, target);
    }
    return target;
  }

  /** 打包字体清单（新增字体时同步维护） */
  private String[] bundledFontNames() {
    return new String[] {"NotoSansCJKsc-Regular.otf"};
  }

  @PreDestroy
  public void cleanup() {
    Path dir = extractedDir;
    if (dir == null) {
      return;
    }
    try {
      cleanupDirectory(dir);
      log.info("Typst 字体临时目录已清理: {}", dir);
    } catch (IOException e) {
      log.warn("Typst 字体临时目录清理失败: {}", dir, e);
    }
  }

  private void cleanupDirectory(Path dir) throws IOException {
    try (Stream<Path> entries = Files.walk(dir)) {
      entries.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // 清理失败不影响主流程
        }
      });
    }
  }
}
