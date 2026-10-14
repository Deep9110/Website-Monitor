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
  record Site(String name, String url, String visitorXpath) {}
  record Result(String name, String url, String status, String visitors,
                String httpStatus, String responseMs, String checkedAt, String details) {}

  public static void main(String[] args) throws Exception {
    List<Site> sites = readSites(Path.of("sites.csv"));
    List<Result> results = new ArrayList<>();
    for (Site site : sites) {
      results.add(check(site));
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
      if (line.isBlank() || line.startsWith("#")) continue;
      // CSV parser supports quoted visitor XPath; site names/URLs should not contain commas.
      String[] p = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
      if (p.length >= 2) {
        String xpath = p.length > 2 ? p[2].replaceAll("^\"|\"$", "").trim() : "";
        sites.add(new Site(p[0].trim(), p[1].trim(), xpath));
      }
    }
    return sites;
  }

  static Result check(Site site) {
    String status = "CHECK FAILED", visitors = "N/A", http = "N/A", ms = "N/A", details = "";
    String checked = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toString();
    long start = System.nanoTime();
    try {
      HttpURLConnection c = (HttpURLConnection) URI.create(site.url()).toURL().openConnection();
      c.setRequestMethod("GET");
      c.setConnectTimeout(10000); c.setReadTimeout(15000);
      c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent", "WebsiteMonitor/1.0 (authorized availability check)");
      int code = c.getResponseCode();
      http = String.valueOf(code);
      c.disconnect();
      ms = String.valueOf((System.nanoTime() - start) / 1_000_000);
      if (code >= 200 && code < 400) status = "OPEN";
      else status = "NOT OPEN";
    } catch (Exception e) {
      details = "HTTP check: " + e.getClass().getSimpleName() + ": " + safe(e.getMessage());
      return new Result(site.name(), site.url(), status, visitors, http, ms, checked, details);
    }

    WebDriver driver = null;
    try {
      ChromeOptions options = new ChromeOptions();
      options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
          "--window-size=1440,1000");
      driver = new ChromeDriver(options);
      driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
      driver.get(site.url());
      if (!site.visitorXpath().isBlank()) {
        new WebDriverWait(driver, Duration.ofSeconds(12)).until(d ->
          !d.findElements(By.xpath(site.visitorXpath())).isEmpty());
        String text = driver.findElements(By.xpath(site.visitorXpath())).stream()
          .filter(WebElement::isDisplayed).map(WebElement::getText)
          .filter(t -> t != null && t.contains("Total Visitors")).findFirst().orElse("");
        Matcher m = Pattern.compile("Total Visitors\\s*:?\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) visitors = m.group(1).replace(",", "");
        else details = "Page opened, but visitor counter text was not recognized.";
      } else {
        details = "No visitor XPath configured.";
      }
    } catch (Exception e) {
      details = (details.isBlank() ? "" : details + " | ") +
        "Browser/counter check: " + e.getClass().getSimpleName() + ": " + safe(e.getMessage());
      if ("OPEN".equals(status)) status = "DEGRADED";
    } finally {
      if (driver != null) try { driver.quit(); } catch (Exception ignored) {}
    }
    return new Result(site.name(), site.url(), status, visitors, http, ms, checked, details);
  }

  static void writeExcel(List<Result> results, Path path) throws IOException {
    try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(path)) {
      Sheet s = wb.createSheet("Website Status");
      String[] headers = {"Site Name","Site URL","Open or Not Open","Visitor Count","HTTP Status",
          "Response Time (ms)","Checked At (IST)","Details"};
      Row h = s.createRow(0);
      for (int i=0;i<headers.length;i++) { h.createCell(i).setCellValue(headers[i]); }
      int r = 1;
      for (Result x : results) {
        Row row = s.createRow(r++);
        String[] vals = {x.name(),x.url(),x.status(),x.visitors(),x.httpStatus(),x.responseMs(),x.checkedAt(),x.details()};
        for (int i=0;i<vals.length;i++) row.createCell(i).setCellValue(vals[i] == null ? "" : vals[i]);
      }
      for (int i=0;i<headers.length;i++) s.autoSizeColumn(i);
      wb.write(out);
    }
  }

  static void sendEmail(Path report, List<Result> results, String trigger) throws Exception {
    String host = env("SMTP_HOST"), port = envOr("SMTP_PORT", "587");
    String user = env("SMTP_USERNAME"), pass = env("SMTP_PASSWORD");
    String from = envOr("MAIL_FROM", user), to = env("MAIL_TO");
    if (host.isBlank() || user.isBlank() || pass.isBlank() || to.isBlank()) {
      System.out.println("Email skipped: configure SMTP_HOST, SMTP_USERNAME, SMTP_PASSWORD and MAIL_TO secrets.");
      return;
    }
    long open = results.stream().filter(r -> r.status().equals("OPEN")).count();
    long down = results.stream().filter(r -> r.status().equals("NOT OPEN") || r.status().equals("CHECK FAILED")).count();
    Properties props = new Properties();
    props.put("mail.smtp.auth","true"); props.put("mail.smtp.starttls.enable","true");
    props.put("mail.smtp.host",host); props.put("mail.smtp.port",port);
    Session session = Session.getInstance(props, new Authenticator() {
      protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(user, pass); }
    });
    MimeMessage msg = new MimeMessage(session);
    msg.setFrom(new InternetAddress(from));
    msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
    msg.setSubject("Website Monitoring Report — " + LocalDate.now(ZoneId.of("Asia/Kolkata")));
    MimeBodyPart body = new MimeBodyPart();
    body.setText("Website scan completed.\nTrigger: " + trigger + "\nTotal: " + results.size()
      + "\nOpen: " + open + "\nNot open/check failed: " + down
      + "\n\nSee the attached Excel report.\n\nManual run: open the repository Actions tab and choose 'Website Monitor' > 'Run workflow'.",
      "UTF-8");
    MimeBodyPart attachment = new MimeBodyPart();
    attachment.attachFile(report.toFile());
    Multipart multipart = new MimeMultipart();
    multipart.addBodyPart(body); multipart.addBodyPart(attachment);
    msg.setContent(multipart);
    Transport.send(msg);
  }

  static String env(String key) { return System.getenv().getOrDefault(key, "").trim(); }
  static String envOr(String key, String fallback) { String v=env(key); return v.isBlank()?fallback:v; }
  static String safe(String s) { return s == null ? "" : s.replaceAll("[\\r\\n]+", " "); }
}
