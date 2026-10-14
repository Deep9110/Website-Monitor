# Website Monitoring Starter (Java + Selenium + GitHub Actions)

## What this starter does
- Runs automatically each day at 10:00 AM IST on a GitHub-hosted runner.
- Can also be started manually using Actions -> Website Monitor -> Run workflow.
- Checks each URL independently, creates an Excel report, and emails it when SMTP secrets are configured.
- Does not require your personal PC to be switched on.
- Uses headless Chrome navigation as the availability check; the HTTP request is diagnostic only.

## Important limitations
- GitHub scheduled workflows can be delayed; this is not a guaranteed exact-time scheduler.
- Manual runs are available to repository users with suitable permissions. An email button for arbitrary recipients requires a separate authenticated web endpoint/service; do not publish a GitHub token in an email link.
- Visitor counts vary by website. Configure and test a valid `visitor_xpath` for each site. A counter selector can identify a container whose visible text includes "Total Visitors".
- `sites.csv` contains placeholders. Replace them with your 21 authorized URLs.
- The code is a starter; validate each website and its counter behavior before relying on status emails.

## Availability checks
The application records the HTTP pre-check status and response time separately from Selenium's browser navigation status and duration. A failed HTTP pre-check does not prevent Selenium from trying the site. Selenium waits for `document.readyState` to be `complete`, retries transient navigation failures up to two times, and only then classifies a failed navigation as `NOT OPEN`. A browser/configuration error is reported as `CHECK FAILED`; counter and other secondary-check problems can produce `DEGRADED`.

The following optional environment variables (or Java system properties with the same names) control wait behavior:
- `WEBSITE_WAIT_SECONDS` (default `40`; allowed `30`–`45`)
- `WEBSITE_SETTLE_SECONDS` (default `2`; allowed `0`–`10`)
- `WEBSITE_RETRY_DELAY_SECONDS` (default `2`; allowed `0`–`30`)

Page-load timeout is 45 seconds and the visitor-counter wait is 12 seconds. The report's `Response Time (ms)` column is the HTTP pre-check duration; Selenium navigation timing and browser response status are recorded in `Details`.

## Configure the 21 sites
Edit `sites.csv`:
`name,url,visitor_xpath`
Add one row per site. Use an XPath suitable for each site's visitor counter. If a site does not show a public visitor count, leave the XPath blank; report will show N/A.
Counter extraction recognizes labels such as `Total Visitors` and `Visitors`, as well as a numeric-only value returned by the configured XPath. For other labels or markup, configure the XPath to return the visitor label and count in its text or update the extraction pattern.

## Configure email
In GitHub: repository -> Settings -> Secrets and variables -> Actions -> New repository secret.
Add:
- `SMTP_HOST`
- `SMTP_PORT` (usually 587 for STARTTLS)
- `SMTP_USERNAME`
- `SMTP_PASSWORD` (use an app password where supported; never commit it)
- `MAIL_FROM`
- `MAIL_TO`

## Deploy
1. Create a GitHub repository. Keep it private if the site list/report data is sensitive.
2. Upload these files and commit them to the default branch (usually `main`).
3. Add the secrets listed above.
4. Replace `sites.csv` placeholders.
5. Open Actions -> Website Monitor -> Run workflow to test it.
6. Confirm the Excel artifact and email. Then leave the workflow enabled for daily runs.

## Run now
GitHub -> repository -> Actions -> Website Monitor -> Run workflow.
Only trusted repository users with appropriate access should be allowed to start scans.

## Time
`30 4 * * *` means 04:30 UTC = 10:00 IST. GitHub may delay scheduled workflows during busy periods. If exact-time delivery is critical, use a dedicated always-on scheduler/service.
