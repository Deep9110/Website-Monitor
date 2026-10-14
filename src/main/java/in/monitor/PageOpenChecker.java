package in.monitor;

import java.net.HttpURLConnection;
import java.net.URI;

public final class PageOpenChecker {

  private static final String OPEN = "Open";
  private static final String CHECK_FAILED = "Check failed";
  private static final int TIMEOUT_MS = 5000;

  private PageOpenChecker() {}

  public static String check(String url) {
    HttpURLConnection connection = null;

    try {
      connection = (HttpURLConnection) URI.create(url)
          .toURL()
          .openConnection();

      connection.setRequestMethod("GET");
      connection.setConnectTimeout(TIMEOUT_MS);
      connection.setReadTimeout(TIMEOUT_MS);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty(
          "User-Agent", "WebsiteMonitor/1.0");

      int statusCode = connection.getResponseCode();
      return statusCode == 200 ? OPEN : CHECK_FAILED;
    } catch (Exception e) {
      return CHECK_FAILED;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
