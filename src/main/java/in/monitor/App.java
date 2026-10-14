package in.monitor;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

public class App {

  private static final int MAX_NAVIGATION_ATTEMPTS = 3;
  private static final int PAGE_LOAD_TIMEOUT_SECONDS = 45;
  private static final int COUNTER_WAIT_SECONDS = 12;

  record Site(String name, String url, String visitorXpath) {}

  record Result(
      String name, String url, String status, String visitors,
      String httpStatus, String responseMs, String checkedAt,
      String details) {}

  record HttpCheck(String status, String responseMs,
                   boolean hasProblem) {}

  record CheckConfig(int navigationWaitSeconds,
                     int settleSeconds, int retryDelaySeconds) {}

  public static void main(String[] args) throws Exception {
    List<Site> sites = readSites(Path.of("sites.csv"));
    List<Result> results = new ArrayList<>();

    for (Site site : sites) {
      Result result;
      try {
        result = check(site);
      } catch (Exception e) {
        String diagnostic = "Unexpected website-check error: "
            + exceptionDescription(e);
        System.err.println(site.name() + " | " + site.url()
            + " | CHECK FAILED | " + diagnostic);
        result = new Result(site.name(), site.url(), "CHECK FAILED",
            "N/A", "N/A", "N/A",
            ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toString(),
            diagnostic);
      }
      results.add(result);
      System.out.println("Result: " + result.status()
          + " | Visitors: " + result.visitors());
    }

    String stamp = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

    Path report = Path.of("reports", "website-report-" + stamp + ".xlsx");
    Files.createDirectories(report.getParent());

    writeExcel(results, report);
    sendEmail(report, results,
        args.length > 0 ? args[0] : "MANUAL");

    System.out.println("Report created: " + report.toAbsolutePath());
  }

  static List<Site> readSites(Path path) throws IOException {
    List<Site> sites = new ArrayList<>();

    for (String line : Files.readAllLines(path).stream().skip(1).toList()) {
      if (line.isBlank() || line.startsWith("#")) continue;

      String[] p = line.split(
          ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);

      if (p.length >= 2) {
        String xpath = p.length > 2
            ? p[2].replaceAll("^\"|\"$", "").trim()
            : "";

        sites.add(new Site(
            p[0].trim(), p[1].trim(), xpath));
      }
    }

    return sites;
  }

  static Result check(Site site) {
    String visitors = "N/A";
    StringBuilder details = new StringBuilder();

    String checked = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        .toString();

    System.out.println("Checking website: " + site.name()
        + " | URL: " + site.url());

    HttpCheck httpCheck = checkHttp(site, details);
    System.out.println("HTTP pre-check for " + site.name()
        + ": status=" + httpCheck.status()
        + ", responseTimeMs=" + httpCheck.responseMs());

    CheckConfig config;
    try {
      config = readCheckConfig();
    } catch (IllegalArgumentException e) {
      appendDetail(details, "Configuration error: " + safe(e.getMessage()));
      return finish(site, "CHECK FAILED", visitors, httpCheck,
          checked, details, 0, "N/A", "Configuration invalid");
    }

    WebDriver driver = null;
    boolean pageLoaded = false;
    boolean browserFailure = false;
    int attempts = 0;
    long navigationMs = 0;
    Integer browserResponseStatus = null;
    String navigationFailure = "";

    for (int attempt = 1; attempt <= MAX_NAVIGATION_ATTEMPTS; attempt++) {
      attempts = attempt;
      long navigationStart = System.nanoTime();
      boolean navigationStarted = false;

      try {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
            "--headless=new",
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--window-size=1440,1000"
        );

        driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(
            Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SECONDS));

        navigationStarted = true;
        driver.get(site.url());

        WebDriverWait pageWait = new WebDriverWait(
            driver, Duration.ofSeconds(config.navigationWaitSeconds()));
        pageWait.until(d -> "complete".equals(
            ((JavascriptExecutor) d)
                .executeScript("return document.readyState")));

        String browserError = browserErrorPage(driver);
        if (browserError != null) {
          throw new WebDriverException(
              "net::ERR_CONNECTION_FAILED: " + browserError);
        }

        navigationMs = elapsedMs(navigationStart);
        browserResponseStatus = readBrowserResponseStatus(driver, details);
        if (config.settleSeconds() > 0) {
          Thread.sleep(config.settleSeconds() * 1000L);
        }

        pageLoaded = true;
        System.out.println("Selenium navigation for " + site.name()
            + ": loaded in " + navigationMs + " ms"
            + " (attempt " + attempt + "/" + MAX_NAVIGATION_ATTEMPTS
            + "), browserResponseStatus="
            + (browserResponseStatus == null
                ? "unavailable" : browserResponseStatus));
        break;
      } catch (TimeoutException e) {
        navigationMs = elapsedMs(navigationStart);
        navigationFailure = exceptionDescription(e);
        appendDetail(details, "Selenium attempt " + attempt + "/"
            + MAX_NAVIGATION_ATTEMPTS + " timed out after "
            + navigationMs + " ms: " + navigationFailure);
      } catch (WebDriverException e) {
        navigationMs = elapsedMs(navigationStart);
        navigationFailure = exceptionDescription(e);
        appendDetail(details, "Selenium attempt " + attempt + "/"
            + MAX_NAVIGATION_ATTEMPTS + " failed after "
            + navigationMs + " ms: " + navigationFailure);

        if (!navigationStarted || !isTransientNavigationFailure(e)) {
          browserFailure = true;
          break;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        navigationMs = elapsedMs(navigationStart);
        navigationFailure = "Retry/settling delay was interrupted.";
        appendDetail(details, navigationFailure);
        browserFailure = true;
        break;
      } catch (RuntimeException e) {
        navigationMs = elapsedMs(navigationStart);
        navigationFailure = exceptionDescription(e);
        appendDetail(details, "Unexpected Selenium error: "
            + navigationFailure);
        browserFailure = true;
        break;
      } finally {
        if (driver != null && !pageLoaded) {
          try {
            driver.quit();
          } catch (WebDriverException e) {
            appendDetail(details, "WebDriver cleanup warning: "
                + exceptionDescription(e));
            System.err.println("WebDriver cleanup failed for "
                + site.name() + ": " + exceptionDescription(e));
          }
          driver = null;
        }
      }

      System.out.println("Selenium attempt " + attempt + "/"
          + MAX_NAVIGATION_ATTEMPTS + " for " + site.name()
          + " failed after " + navigationMs + " ms: "
          + navigationFailure);

      if (attempt < MAX_NAVIGATION_ATTEMPTS) {
        try {
          Thread.sleep(config.retryDelaySeconds() * 1000L);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          navigationFailure = "Retry delay was interrupted.";
          appendDetail(details, navigationFailure);
          browserFailure = true;
          break;
        }
      }
    }

    if (!pageLoaded) {
      String status = browserFailure ? "CHECK FAILED" : "NOT OPEN";
      if (!navigationFailure.isBlank()) {
        appendDetail(details, "Final Selenium failure: "
            + navigationFailure);
      }
      return finish(site, status, visitors, httpCheck, checked, details,
          attempts, "N/A", navigationFailure);
    }

    String status = httpCheck.hasProblem() ? "DEGRADED" : "OPEN";
    if (browserResponseStatus != null
        && (browserResponseStatus < 200 || browserResponseStatus >= 400)) {
      status = "DEGRADED";
      appendDetail(details, "Selenium navigation returned HTTP "
          + browserResponseStatus + ".");
    }
    appendDetail(details, "Selenium browser response status: "
        + (browserResponseStatus == null
            ? "unavailable" : browserResponseStatus) + ".");

    try {
      if (site.visitorXpath().isBlank()) {
        appendDetail(details, "No visitor XPath configured; visitor count is N/A.");
      } else {
        try {
          WebDriverWait counterWait = new WebDriverWait(
              driver, Duration.ofSeconds(COUNTER_WAIT_SECONDS));
          WebElement counter = counterWait.until(d -> {
            for (WebElement element :
                d.findElements(By.xpath(site.visitorXpath()))) {
              try {
                if (element.isDisplayed() && !element.getText().isBlank()) {
                  return element;
                }
              } catch (StaleElementReferenceException ignored) {
                // The page is updating; wait for a current element.
              }
            }
            return null;
          });

          String text = counter.getText().trim();
          Matcher totalVisitors = Pattern.compile(
              "(?:(?:total\\s+)?visitors?|visitor\\s+count)"
                  + "\\s*:?\\s*([\\d,]+)",
              Pattern.CASE_INSENSITIVE).matcher(text);
          Matcher plainCount = Pattern.compile("^\\s*([\\d,]+)\\s*$")
              .matcher(text);

          if (totalVisitors.find()) {
            visitors = totalVisitors.group(1).replace(",", "");
          } else if (plainCount.matches()) {
            visitors = plainCount.group(1).replace(",", "");
          } else {
            status = "DEGRADED";
            appendDetail(details, "Visitor counter was present, but its text "
                + "did not contain a recognized visitor count: " + safe(text));
          }
        } catch (TimeoutException e) {
          status = "DEGRADED";
          appendDetail(details, "Visitor counter was not found within "
              + COUNTER_WAIT_SECONDS + " seconds; visitor count is N/A.");
        } catch (WebDriverException e) {
          status = "DEGRADED";
          appendDetail(details, "Visitor counter extraction failed: "
              + exceptionDescription(e));
        }
      }
    } finally {
      if (driver != null) {
        try {
          driver.quit();
        } catch (WebDriverException e) {
          appendDetail(details, "WebDriver cleanup warning: "
              + exceptionDescription(e));
          System.err.println("WebDriver cleanup failed for " + site.name()
              + ": " + exceptionDescription(e));
        }
      }
    }

    appendDetail(details, "Selenium navigation completed in "
        + navigationMs + " ms across " + attempts + " attempt(s).");
    return finish(site, status, visitors, httpCheck, checked, details,
        attempts, browserResponseStatus == null
            ? "unavailable" : String.valueOf(browserResponseStatus),
        visitors.equals("N/A") ? "Visitor count unavailable" : "Visitor count extracted");
  }

  private static HttpCheck checkHttp(Site site, StringBuilder details) {
    long start = System.nanoTime();
    HttpURLConnection connection = null;
    String status = "N/A";
    boolean hasProblem = false;
    String diagnostic = "";

    try {
      connection = (HttpURLConnection) URI.create(site.url())
          .toURL().openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(20000);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("User-Agent", "WebsiteMonitor/1.0");

      int code = connection.getResponseCode();
      status = String.valueOf(code);
      hasProblem = code < 200 || code >= 400;
      if (hasProblem) {
        diagnostic = "HTTP pre-check returned status " + code + ".";
      }
    } catch (Exception e) {
      hasProblem = true;
      diagnostic = "HTTP pre-check failed: " + exceptionDescription(e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }

    String responseMs = String.valueOf(elapsedMs(start));
    if (!diagnostic.isBlank()) {
      appendDetail(details, diagnostic);
    }
    appendDetail(details, "HTTP pre-check response time: "
        + responseMs + " ms.");
    return new HttpCheck(status, responseMs, hasProblem);
  }

  private static CheckConfig readCheckConfig() {
    return new CheckConfig(
        intSetting("WEBSITE_WAIT_SECONDS", 40, 30, 45),
        intSetting("WEBSITE_SETTLE_SECONDS", 2, 0, 10),
        intSetting("WEBSITE_RETRY_DELAY_SECONDS", 2, 0, 30));
  }

  private static int intSetting(String name, int defaultValue,
                                int minimum, int maximum) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      value = System.getenv(name);
    }
    if (value == null || value.isBlank()) {
      return defaultValue;
    }

    final int parsed;
    try {
      parsed = Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name
          + " must be an integer from " + minimum + " to " + maximum + ".");
    }
    if (parsed < minimum || parsed > maximum) {
      throw new IllegalArgumentException(name
          + " must be from " + minimum + " to " + maximum + ".");
    }
    return parsed;
  }

  private static Integer readBrowserResponseStatus(WebDriver driver,
                                                    StringBuilder details) {
    try {
      Object value = ((JavascriptExecutor) driver).executeScript(
          "const entry = performance.getEntriesByType('navigation')[0];"
              + "return entry && typeof entry.responseStatus === 'number'"
              + " ? entry.responseStatus : null;");
      if (value instanceof Number number && number.intValue() > 0) {
        return number.intValue();
      }
    } catch (WebDriverException e) {
      appendDetail(details, "Selenium browser response status unavailable: "
          + exceptionDescription(e));
    }
    return null;
  }

  private static String browserErrorPage(WebDriver driver) {
    String currentUrl = driver.getCurrentUrl();
    String title = driver.getTitle();
    String body = String.valueOf(((JavascriptExecutor) driver)
        .executeScript("return document.body"
            + " ? document.body.innerText.slice(0, 2000) : '';"));
    String visibleText = (title + " " + body).toLowerCase(Locale.ROOT);

    if (currentUrl.startsWith("chrome-error://")
        || visibleText.contains("this site can’t be reached")
        || visibleText.contains("this site can't be reached")
        || visibleText.contains("err_connection_")
        || visibleText.contains("err_name_not_resolved")
        || visibleText.contains("err_timed_out")
        || visibleText.contains("err_address_unreachable")
        || visibleText.contains("err_network_changed")
        || visibleText.contains("err_ssl_")
        || visibleText.contains("err_cert_")
        || visibleText.contains("your connection is not private")) {
      return "Chrome displayed an error page: "
          + safe(title.isBlank() ? currentUrl : title);
    }
    return null;
  }

  private static boolean isTransientNavigationFailure(
      WebDriverException exception) {
    String message = safe(exception.getMessage()).toLowerCase(Locale.ROOT);
    return message.contains("net::err_")
        || message.contains("connection reset")
        || message.contains("connection refused")
        || message.contains("name_not_resolved")
        || message.contains("dns_probe")
        || message.contains("timed out receiving message from renderer");
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private static String exceptionDescription(Throwable exception) {
    String message = safe(exception.getMessage());
    return exception.getClass().getSimpleName()
        + (message.isBlank() ? "" : ": " + message);
  }

  private static void appendDetail(StringBuilder details, String message) {
    if (details.length() > 0) {
      details.append(' ');
    }
    details.append(message);
  }

  private static Result finish(Site site, String status, String visitors,
                               HttpCheck httpCheck, String checked,
                               StringBuilder details, int attempts,
                               String browserResponse, String visitorReason) {
    System.out.println("Final check for " + site.name()
        + " | attempts=" + attempts
        + " | browserResponse=" + browserResponse
        + " | visitors=" + visitors + " (" + visitorReason + ")"
        + " | status=" + status
        + (details.length() == 0 ? "" : " | details=" + details));
    return new Result(site.name(), site.url(), status, visitors,
        httpCheck.status(), httpCheck.responseMs(), checked,
        details.toString().trim());
  }

  static void writeExcel(List<Result> results, Path path)
      throws IOException {

    try (Workbook workbook = new XSSFWorkbook();
         OutputStream out = Files.newOutputStream(path)) {

      Sheet sheet = workbook.createSheet("Website Status");

      String[] headers = {
          "Site Name", "Site URL", "Open or Not Open",
          "Visitor Count", "HTTP Status",
          "Response Time (ms)", "Checked At (IST)", "Details"
      };

      Row header = sheet.createRow(0);

      for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
      }

      int rowIndex = 1;

      for (Result result : results) {
        Row row = sheet.createRow(rowIndex++);

        String[] values = {
            result.name(), result.url(), result.status(),
            result.visitors(), result.httpStatus(),
            result.responseMs(), result.checkedAt(),
            result.details()
        };

        for (int i = 0; i < values.length; i++) {
          row.createCell(i).setCellValue(
              values[i] == null ? "" : values[i]);
        }
      }

      for (int i = 0; i < headers.length; i++) {
        sheet.autoSizeColumn(i);
      }

      workbook.write(out);
    }
  }

  static void sendEmail(Path report, List<Result> results,
                        String trigger) throws Exception {

    String host = env("SMTP_HOST");
    String port = envOr("SMTP_PORT", "587");
    String user = env("SMTP_USERNAME");
    String pass = env("SMTP_PASSWORD");
    String from = envOr("MAIL_FROM", user);
    String to = env("MAIL_TO");

    if (host.isBlank() || user.isBlank()
        || pass.isBlank() || to.isBlank()) {
      System.out.println(
          "Email skipped: configure SMTP secrets.");
      return;
    }

    long open = results.stream()
        .filter(r -> r.status().equals("OPEN")).count();

    long down = results.stream()
        .filter(r -> r.status().equals("NOT OPEN")
            || r.status().equals("CHECK FAILED")).count();

    long degraded = results.stream()
        .filter(r -> r.status().equals("DEGRADED")).count();

    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.starttls.enable", "true");
    props.put("mail.smtp.host", host);
    props.put("mail.smtp.port", port);

    Session session = Session.getInstance(props,
        new Authenticator() {
          protected PasswordAuthentication
              getPasswordAuthentication() {
            return new PasswordAuthentication(user, pass);
          }
        });

    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(from));
    message.setRecipients(
        Message.RecipientType.TO,
        InternetAddress.parse(to, true));

    message.setSubject(
        "Website Monitoring Report — "
            + LocalDate.now(ZoneId.of("Asia/Kolkata")));

    MimeBodyPart body = new MimeBodyPart();
    body.setText(
        "Website scan completed.\n"
        + "Trigger: " + trigger + "\n"
        + "Total sites: " + results.size() + "\n"
        + "Open: " + open + "\n"
        + "Degraded: " + degraded + "\n"
        + "Not open/check failed: " + down + "\n\n"
        + "See the attached Excel report.\n",
        "UTF-8");

    MimeBodyPart attachment = new MimeBodyPart();
    attachment.attachFile(report.toFile());

    Multipart multipart = new MimeMultipart();
    multipart.addBodyPart(body);
    multipart.addBodyPart(attachment);
    message.setContent(multipart);

    Transport.send(message);
  }

  static String env(String key) {
    return System.getenv().getOrDefault(key, "").trim();
  }

  static String envOr(String key, String fallback) {
    String value = env(key);
    return value.isBlank() ? fallback : value;
  }

  static String safe(String value) {
    return value == null
        ? "" : value.replaceAll("[\\r\\n]+", " ");
  }
}
