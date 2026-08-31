package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Typst 编译器薄组件（P2-4）：调用本机 typst 二进制把简历模板 + content JSON
 * 编译为 PDF 字节。
 *
 * <p>安全边界：
 * - 每次编译使用独立临时目录，{@code --root} 限定在该目录内，模板无法读取外部文件
 * - stderr 只进日志不透传给调用方（避免内部路径/环境细节泄漏到 API 响应）
 * - 编译超时强制销毁进程（Typst 单文件 100ms 级，超时视为异常）
 * - content JSON 以文件形式写入临时目录（模板通过 json("content.json") 读取），
 *   不经过命令行参数，避免参数注入面
 */
@Slf4j
@Component
public class TypstCompiler {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  private final String binaryPath;
  private final Duration timeout;
  private final TypstFontExtractor fontExtractor;

  public TypstCompiler(
      @Value("${app.resume.typst-binary:typst}") String binaryPath,
      @Value("${app.resume.typst-timeout-seconds:10}") long timeoutSeconds,
      TypstFontExtractor fontExtractor) {
    this.binaryPath = binaryPath;
    this.timeout = Duration.ofSeconds(timeoutSeconds);
    this.fontExtractor = fontExtractor;
  }

  /**
   * 编译模板 + content JSON 为 PDF（自动使用打包字体；无打包字体时回退系统字体）。
   */
  public byte[] compileToPdf(byte[] template, String contentJson) {
    return compileToPdf(template, contentJson, fontExtractor == null ? null
        : fontExtractor.ensureExtracted());
  }

  /**
   * 编译模板 + content JSON 为 PDF。
   *
   * @param fontDir 字体目录（可为 null，使用系统字体；生产环境指向 resources 解包目录）
   * @return PDF 字节
   */
  public byte[] compileToPdf(byte[] template, String contentJson, Path fontDir) {
    Path workDir = null;
    long startAt = System.currentTimeMillis();
    try {
      workDir = Files.createTempDirectory("typst-resume-");
      Path templatePath = workDir.resolve("resume.typ");
      Path contentPath = workDir.resolve("content.json");
      Files.write(templatePath, template);
      Files.write(contentPath, contentJson.getBytes(StandardCharsets.UTF_8));

      List<String> command = new ArrayList<>();
      command.add(binaryPath);
      command.add("compile");
      command.add("--root");
      command.add(workDir.toString());
      if (fontDir != null) {
        command.add("--font-path");
        command.add(fontDir.toString());
      }
      command.add("resume.typ");
      command.add("out.pdf");

      Process process = new ProcessBuilder(command)
          .directory(workDir.toFile())
          .redirectErrorStream(false)
          .start();

      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "简历 PDF 编译超时");
      }
      if (process.exitValue() != 0) {
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        log.error("Typst 编译失败: exit={}, stderr={}", process.exitValue(), stderr);
        throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "简历 PDF 生成失败");
      }

      byte[] pdf = Files.readAllBytes(workDir.resolve("out.pdf"));
      if (pdf.length == 0) {
        throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "生成的 PDF 为空");
      }
      log.info("Typst 编译成功: {} bytes, 耗时 {}ms", pdf.length, System.currentTimeMillis() - startAt);
      return pdf;
    } catch (BusinessException e) {
      throw e;
    } catch (IOException e) {
      log.error("Typst 编译 IO 异常（二进制不存在或临时目录不可写）: binary={}", binaryPath, e);
      throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "编译环境异常，请联系管理员");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "编译被中断");
    } finally {
      cleanupQuietly(workDir);
    }
  }

  private void cleanupQuietly(Path workDir) {
    if (workDir == null) {
      return;
    }
    try (var paths = Files.walk(workDir)) {
      paths.sorted(java.util.Comparator.reverseOrder())
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException e) {
              log.warn("临时目录清理失败: {}", path, e);
            }
          });
    } catch (IOException e) {
      log.warn("临时目录遍历失败: {}", workDir, e);
    }
  }
}
