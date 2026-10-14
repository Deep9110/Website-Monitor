# Website Monitoring Starter (Java + Selenium + GitHub Actions)

## What this starter does
- Runs automatically each day at 10:00 AM IST on a GitHub-hosted runner.
- Can also be started manually using Actions -> Website Monitor -> Run workflow.
- Checks each URL independently, creates an Excel report, and emails it when SMTP secrets are configured.
- Does not require your personal PC to be switched on.
- Uses headless Chrome navigation as the availability check.

## Important limitations
- GitHub scheduled workflows can be delayed; this is not a guaranteed exact-time scheduler.
- Manual runs are available to repository users with suitable permissions. An email button for arbitrary recipients requires a separate authenticated web endpoint/service; do not publish a GitHub token in an email link.
- Visitor counts vary by website. Configure and test a valid `visitor_xpath` for each site. A counter selector can identify a container whose visible text includes "Total Visitors".
- `sites.csv` contains placeholders. Replace them with your 21 authorized URLs.
- The code is a starter; validate each website and its counter behavior before relying on status emails.

## Availability checks
`PageOpenChecker` gives Selenium up to 30 seconds to open each page and wait for the document to finish loading. It returns `Open` or `Check failed`. For an open page, `VisitorCountChecker` waits up to 10 seconds for the configured XPath; missing or unrecognized counter text is reported as `N/A` and does not change the page status. Each website is checked sequentially in its own browser session.

The Excel report contains `Site Name`, `Site URL`, `Status`, and `Visitor Count`.

## Configure the 21 sites
Edit `sites.csv`:
`name,url,visitor_xpath`
Add one row per site. Use an XPath suitable for each site's visitor counter. If a site does not show a public visitor count, leave the XPath blank; report will show N/A.
Counter extraction recognizes labels such as `Total Visitors` and `Visitors`, as well as a numeric-only value returned by the configured XPath. For other labels or markup, configure the XPath to return the visitor label and count in its text.

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
