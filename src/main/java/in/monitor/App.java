package in.monitor;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class App {

  private static final String OPEN = "Open";

  record Site(String name, String url, String visitorXpath) {}

  record Result(String name, String url, String status, String visitors) {}

  public static void main(String[] args) throws Exception {
    List<Site> sites = readSites(Path.of("sites.csv"));
    List<Result> results = new ArrayList<>();

    for (int index = 0; index < sites.size(); index++) {
      Site site = sites.get(index);
      System.out.printf("[%d/%d] Checking %s%n",
          index + 1, sites.size(), site.name());
      Result result = check(site);
      results.add(result);
      System.out.printf("[%d/%d] %s: %s; visitors=%s%n",
          index + 1, sites.size(), site.name(),
          result.status(), result.visitors());
    }

    String stamp = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
    Path report = Path.of("reports", "website-report-" + stamp + ".xlsx");
    Files.createDirectories(report.getParent());

    writeExcel(results, report);
    sendEmail(report, results, args.length > 0 ? args[0] : "MANUAL");
    System.out.println("Report created: " + report.toAbsolutePath());
  }

  static List<Site> readSites(Path path) throws IOException {
    List<Site> sites = new ArrayList<>();

    for (String line : Files.readAllLines(path).stream().skip(1).toList()) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }

      String[] columns = line.split(
          ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
      if (columns.length >= 2) {
        String xpath = columns.length > 2
            ? columns[2].replaceAll("^\"|\"$", "").trim()
            : "";
        String url = columns[1].trim();
        if (!url.matches("(?i)^https?://.*")) {
          url = "https://" + url;
        }
        sites.add(new Site(
            columns[0].trim(), url, xpath));
      }
    }

    return sites;
  }

  private static Result check(Site site) {
    String status = PageOpenChecker.check(site.url());
    String visitors = "N/A";

    if (OPEN.equals(status) && !site.visitorXpath().isBlank()) {
      WebDriver driver = null;
      try {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
            "--headless=new",
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--window-size=1440,1000");
        driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(
            java.time.Duration.ofSeconds(15));
        driver.get(site.url());
        visitors = VisitorCountChecker.getCount(driver, site.visitorXpath());
      } catch (RuntimeException e) {
        visitors = "N/A";
      } finally {
        if (driver != null) {
          try {
            driver.quit();
          } catch (WebDriverException ignored) {
            // Continue checking the next site.
          }
        }
      }
    }

    return new Result(site.name(), site.url(), status, visitors);
  }

  private static void writeExcel(List<Result> results, Path path)
      throws IOException {
    String[] headers = {"Site Name", "Site URL", "Status", "Visitor Count"};

    try (Workbook workbook = new XSSFWorkbook();
         OutputStream out = Files.newOutputStream(path)) {
      Sheet sheet = workbook.createSheet("Website Status");
      Row header = sheet.createRow(0);

      for (int column = 0; column < headers.length; column++) {
        header.createCell(column).setCellValue(headers[column]);
      }

      int rowIndex = 1;
      for (Result result : results) {
        Row row = sheet.createRow(rowIndex++);
        row.createCell(0).setCellValue(result.name());
        row.createCell(1).setCellValue(result.url());
        row.createCell(2).setCellValue(result.status());
        row.createCell(3).setCellValue(result.visitors());
      }

      for (int column = 0; column < headers.length; column++) {
        sheet.autoSizeColumn(column);
      }
      workbook.write(out);
    }
  }

  private static void sendEmail(Path report, List<Result> results,
                                String trigger) throws Exception {
    String host = env("SMTP_HOST");
    String port = envOr("SMTP_PORT", "587");
    String user = env("SMTP_USERNAME");
    String password = env("SMTP_PASSWORD");
    String from = envOr("MAIL_FROM", user);
    String recipients = env("MAIL_TO");

    if (host.isBlank() || user.isBlank()
        || password.isBlank() || recipients.isBlank()) {
      System.out.println("Email skipped: configure SMTP secrets.");
      return;
    }

    long openCount = results.stream()
        .filter(result -> OPEN.equals(result.status()))
        .count();
    long failedCount = results.size() - openCount;

    Properties properties = new Properties();
    properties.put("mail.smtp.auth", "true");
    properties.put("mail.smtp.starttls.enable", "true");
    properties.put("mail.smtp.host", host);
    properties.put("mail.smtp.port", port);

    Session session = Session.getInstance(properties, new Authenticator() {
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(user, password);
      }
    });

    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(from));
    message.setRecipients(
        Message.RecipientType.TO,
        InternetAddress.parse(recipients, true));
    message.setSubject(
        "Website Monitoring Report — "
            + LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ISO_LOCAL_DATE));

    MimeBodyPart body = new MimeBodyPart();
    body.setText(
        "Website scan completed.\n"
            + "Trigger: " + trigger + "\n"
            + "Total sites: " + results.size() + "\n"
            + "Open: " + openCount + "\n"
            + "Check failed: " + failedCount + "\n\n"
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

  private static String env(String name) {
    return System.getenv().getOrDefault(name, "").trim();
  }

  private static String envOr(String name, String fallback) {
    String value = env(name);
    return value.isBlank() ? fallback : value;
  }
}
