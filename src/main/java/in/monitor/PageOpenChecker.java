package in.monitor;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public final class PageOpenChecker {

  private static final String OPEN = "Open";
  private static final String CHECK_FAILED = "Check failed";
  private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(30);

  private PageOpenChecker() {}

  public static String check(WebDriver driver, String url) {
    try {
      driver.manage().timeouts().pageLoadTimeout(PAGE_LOAD_TIMEOUT);
      driver.get(url);

      new WebDriverWait(driver, PAGE_LOAD_TIMEOUT).until(webDriver ->
          "complete".equals(((JavascriptExecutor) webDriver)
              .executeScript("return document.readyState")));

      String currentUrl = driver.getCurrentUrl();
      String pageText = String.valueOf(
          ((JavascriptExecutor) driver)
              .executeScript("return document.body"
                  + " ? document.body.innerText : '';"))
          .toLowerCase();

      if (currentUrl.startsWith("chrome-error://")
          || pageText.contains("err_connection_")
          || pageText.contains("err_name_not_resolved")
          || pageText.contains("err_timed_out")
          || pageText.contains("err_address_unreachable")
          || pageText.contains("err_internet_disconnected")
          || pageText.contains("err_ssl_")
          || pageText.contains("err_cert_")
          || pageText.contains("this site can’t be reached")
          || pageText.contains("this site can't be reached")) {
        return CHECK_FAILED;
      }
      return OPEN;
    } catch (WebDriverException | IllegalArgumentException e) {
      return CHECK_FAILED;
    }
  }
}
