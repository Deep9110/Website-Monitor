package in.monitor;

import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VisitorCountChecker {

  private static final Duration COUNTER_TIMEOUT = Duration.ofSeconds(10);
  private static final Pattern LABELED_COUNT = Pattern.compile(
      "(?:(?:total\\s+)?visitors?|visitor\\s+count)\\s*:?\\s*([\\d,]+)",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern NUMBER_ONLY = Pattern.compile("^\\s*([\\d,]+)\\s*$");

  private VisitorCountChecker() {}

  public static String getCount(WebDriver driver, String xpath) {
    if (xpath == null || xpath.isBlank()) {
      return "N/A";
    }

    try {
      return new WebDriverWait(driver, COUNTER_TIMEOUT)
          .until(webDriver -> {
            for (WebElement element :
                webDriver.findElements(By.xpath(xpath))) {
              try {
                if (element.isDisplayed()) {
                  String count = extractCount(element.getText());
                  if (!"N/A".equals(count)) {
                    return count;
                  }
                }
              } catch (StaleElementReferenceException ignored) {
                // Wait for the updated counter element.
              }
            }
            return null;
          });
    } catch (WebDriverException e) {
      return "N/A";
    }
  }

  private static String extractCount(String text) {
    Matcher labeledCount = LABELED_COUNT.matcher(text.trim());
    if (labeledCount.find()) {
      return labeledCount.group(1).replace(",", "");
    }

    Matcher numberOnly = NUMBER_ONLY.matcher(text);
    return numberOnly.matches()
        ? numberOnly.group(1).replace(",", "")
        : "N/A";
  }
}
