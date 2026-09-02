package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * TypstCompiler 测试。
 *
 * <p>策略（TodoList P2-4）：单测 stub 化 —— 本机没有 typst 二进制时真实编译用例
 * 自动跳过（assumeTrue 语义由 @EnabledIf 实现）；有 typst 时跑 golden 测试：
 * fixture JSON 含 * _ $ 等特殊字符 → 断言 %PDF 头 + 最小体积。
 */
@DisplayName("TypstCompiler：模板 + content JSON → PDF")
class TypstCompilerTest {

  private static final TypstCompiler compiler = new TypstCompiler(
      System.getProperty("typst.binary", "typst"), 10, null);

  private static boolean typstAvailable() {
    try {
      Process process = new ProcessBuilder("typst", "--version").start();
      boolean done = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      return done && process.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  @BeforeAll
  static void checkBinary() {
    // @EnabledIf 在方法级生效，这里兜底记录跳过原因便于排查
    if (!typstAvailable()) {
      System.out.println("[TypstCompilerTest] 本机无 typst 二进制，真实编译用例将跳过");
    }
  }

  private static byte[] loadTemplate() throws IOException {
    try (var in = TypstCompilerTest.class.getResourceAsStream("/typst/resume-classic-zh.typ")) {
      assertThat(in).as("classpath 模板 /typst/resume-classic-zh.typ").isNotNull();
      return in.readAllBytes();
    }
  }

  private static String loadFixture() throws IOException {
    try (var in = TypstCompilerTest.class.getResourceAsStream("/typst/resume-fixture.json")) {
      assertThat(in).as("classpath fixture /typst/resume-fixture.json").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @EnabledIf(value = "typstAvailable", disabledReason = "本机无 typst 二进制")
  @DisplayName("真实编译：特殊字符 fixture → %PDF 头 + 体积下限（golden）")
  void compileGolden() throws IOException {
    byte[] pdf = compiler.compileToPdf(loadTemplate(), loadFixture(), null);

    assertThat(pdf.length).isGreaterThan(4);
    assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    // 中文内容 + 字体子集，正常应远大于空文档（mini 文档 ~7KB）
    assertThat(pdf.length).isGreaterThan(20_000);
  }

  @Test
  @EnabledIf(value = "typstAvailable", disabledReason = "本机无 typst 二进制")
  @DisplayName("真实编译：空内容 JSON 仍能产出 PDF（模板兜底空数组）")
  void compileEmptyContent() throws IOException {
    String empty = """
        {"basicInfo": {"name": "测试"}, "education": [], "experience": [],
         "projects": [], "skills": [], "customSections": []}
        """;
    byte[] pdf = compiler.compileToPdf(loadTemplate(), empty, null);
    assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
  }

  @Test
  @EnabledIf(value = "typstAvailable", disabledReason = "本机无 typst 二进制")
  @DisplayName("真实编译：非法模板 → BusinessException 而非原始异常透出")
  void compileBadTemplate() {
    byte[] bad = "#set page(paper: \"a4\")\n#intentional-error".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> compiler.compileToPdf(bad, "{}", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("PDF");
  }

  @Test
  @DisplayName("不存在二进制路径 → BusinessException（不透出 IOException）")
  void missingBinary() throws IOException {
    TypstCompiler broken = new TypstCompiler("/nonexistent/typst-bin", 5, null);
    assertThatThrownBy(() -> broken.compileToPdf(loadTemplate(), "{}", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("编译环境异常");
  }

  @Test
  @DisplayName("临时目录在编译后被清理")
  void tempDirCleaned() throws IOException, InterruptedException {
    // 触发一次失败编译（二进制不存在也会走 finally 清理）
    Path before = countTypstDirs();
    TypstCompiler broken = new TypstCompiler("/nonexistent/typst-bin", 5, null);
    try {
      broken.compileToPdf("<模板>".getBytes(StandardCharsets.UTF_8), "{}", null);
    } catch (BusinessException ignored) {
      // 预期失败
    }
    assertThat(countTypstDirs()).isEqualTo(before);
  }

  private Path countTypstDirs() throws IOException {
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    try (var stream = Files.list(tmp)) {
      return stream.filter(p -> p.getFileName().toString().startsWith("typst-resume-"))
          .limit(1)
          .findFirst()
          .orElse(tmp);
    }
  }
}
