package in.monitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PageOpenChecker {

  private static final String OPEN = "Open";
  private static final String CHECK_FAILED = "Check failed";
  private static final int CONNECT_TIMEOUT_SECONDS = 10;
  private static final int MAX_TIME_SECONDS = 30;
  private static final Pattern STATUS_CODE_AT_END =
      Pattern.compile("(\\d{3})\\s*$");

  private PageOpenChecker() {}

  public static String check(String url) {
    Process process = null;

    try {
      String outputFile = System.getProperty("os.name")
          .toLowerCase().contains("win") ? "NUL" : "/dev/null";
      String curl = System.getProperty("os.name")
          .toLowerCase().contains("win") ? "curl.exe" : "curl";

      process = new ProcessBuilder(
          curl,
          "--location",
          "--silent",
          "--show-error",
          "--output", outputFile,
          "--write-out", "%{http_code}",
          "--connect-timeout", String.valueOf(CONNECT_TIMEOUT_SECONDS),
          "--max-time", String.valueOf(MAX_TIME_SECONDS),
          url)
          .redirectErrorStream(true)
          .start();

      if (!process.waitFor(
          Duration.ofSeconds(MAX_TIME_SECONDS + CONNECT_TIMEOUT_SECONDS)
              .toMillis(),
          TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        System.err.println("HTTP check " + url
            + " failed: curl process timed out.");
        return CHECK_FAILED;
      }

      String output = new String(
          process.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8).trim();
      Matcher matcher = STATUS_CODE_AT_END.matcher(output);
      int statusCode = matcher.find()
          ? Integer.parseInt(matcher.group(1))
          : 0;

      String result = process.exitValue() == 0
          && statusCode >= 200 && statusCode < 400
          ? OPEN : CHECK_FAILED;
      System.out.printf("HTTP check %s: curlExit=%d, status=%s, result=%s%n",
          url, process.exitValue(),
          statusCode == 0 ? "unavailable" : statusCode, result);

      if (CHECK_FAILED.equals(result)) {
        System.err.println("HTTP check detail for " + url + ": "
            + (output.isBlank() ? "no HTTP response" : output));
      }
      return result;
    } catch (IOException e) {
      System.err.println("HTTP check " + url + " failed: "
          + e.getClass().getSimpleName() + ": " + safeMessage(e.getMessage()));
      return CHECK_FAILED;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("HTTP check " + url + " was interrupted.");
      return CHECK_FAILED;
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static String safeMessage(String message) {
    return message == null
        ? "" : message.replaceAll("[\\r\\n]+", " ");
  }
}
