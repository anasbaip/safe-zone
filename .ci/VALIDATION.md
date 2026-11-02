# Validation — 2026-10-12

Validated the updated workspace against the running Docker SonarQube server. These results describe local validation, not a completed GitHub Actions run.

| Component | Tests passed | SonarQube coverage | Gate | Unresolved issues |
|---|---:|---:|---|---:|
| product-service | 47 | 93.8% | OK | 0 |
| user-service | 39 | 93.9% | OK | 0 |
| media-service | 32 | 92.9% | OK | 0 |
| gateway | 16 | 98.0% | OK | 0 |
| discovery | 2 | 100.0% | OK | 0 |
| frontend | 138 | 91.1% | OK | 0 |

Frontend runner coverage: 93.08% statements, 83.85% branches, 86.59% functions, and 96.03% lines. All four metrics enforce an 80% minimum. The frontend production build passed.

The bootstrap provisioning tests passed (16 tests). Jenkins credentials and Execute Analysis permission were verified for all six projects. Jenkins validated the updated Jenkinsfile. actionlint 1.7.12 validated the GitHub Actions workflow.

A disposable instance started from `.ci/compose.sonar.yml`, initialized its administrator and gate successfully, and analyzed an intentionally uncovered sample. Its scanner exited nonzero and the coverage gate reported ERROR at 0.0%. The validation stack was removed afterward.

Persistent projects use Buy01 Quality Gate, including 80% overall and new-code coverage, zero overall code smells/bugs/vulnerabilities and zero new issues. No issue suppressions or false-positive transitions were used to achieve these results.

GitHub still rejects protected branches on this private repository’s current plan. The prepared branch-protection JSON cannot enforce merges until the repository has a qualifying plan or the owner explicitly authorizes public visibility. Credentials removed from production defaults still require rotation at their provider.

## OrderService infrastructure update — 2026-10-24

The earlier six-project results above are historical; they do not establish a
successful seventh-project analysis.

Changed files for this infrastructure task:

- `Jenkinsfile`
- `jenkins-lab/docker-entrypoint.sh`
- `jenkins-lab/jcasc/jenkins.yaml`
- `jenkins-lab/sonar-init/bootstrap.py`
- `jenkins-lab/sonar-init/verify-jenkins.js`
- `jenkins-lab/sonar-init/test_bootstrap.py`
- `jenkins-lab/sonar-init/test_configuration.py` (new)
- `.ci/sonar_ci.py`
- `.ci/test_sonar_ci.py` (new)
- `.github/workflows/quality.yml`
- `README.md`
- `jenkins-lab/sonar-init/README.md`
- `.ci/README.md`
- `.ci/VALIDATION.md`

Actual results:

- Bootstrap and Jenkins configuration fixture tests: **22 passed**. This includes
  creating only the missing Order token while preserving the existing six,
  idempotent re-provisioning, exact seven-component mappings, exported Order
  variables, and optional JCasC fallback when either Order file is absent.
- Shared scanner/report tests: **4 passed**. Order is scanned, reported and fails
  the run for a failed scanner, failed gate or missing JaCoCo XML. HTTP/scanner
  outcomes in these tests are controlled fixtures, not live analyses.
- `order-service`: `bash ./mvnw -B clean verify` — **75 tests passed**;
  nonempty `target/site/jacoco/jacoco.xml` generated. JaCoCo reports 90.7% line
  coverage and 74.8% branch coverage. These are not SonarQube coverage/gate results.
- actionlint 1.7.12: updated workflow passed.
- `bash -n` entrypoint, `node --check` both Sonar helper scripts, JCasC YAML parsing,
  and `git diff --check`: passed. YAML parsing is not a live Jenkins schema check.
- Docker Compose configurations validated for root deployment, canonical Jenkins
  Lab, its `sonar-lab` compatibility include, and isolated Actions SonarQube using
  non-production validation values. No containers were created or deployed and
  no volumes were removed or renamed.
- Running SonarQube `/api/system/status`: **UP**. Docker showed SonarQube and its
  PostgreSQL container; no Jenkins controller was running.

Not performed: live Order project/token provisioning, SonarScanner analysis,
Quality Gate evaluation, live JCasC/plugin application, Jenkinsfile validation by
Jenkins, a Jenkins build, GitHub Actions execution, image builds or deployment.
The local secrets directory has no `sonar-bootstrap-token`, so authorized live
provisioning and analysis could not be tested. No success is inferred from health
checks or fixture tests. Existing gate thresholds and all six existing components
remain unchanged.

Reviewed and retained: Order's Maven/JaCoCo configuration, Dockerfile, authenticated
MongoDB configuration and persistent volume, Eureka registration, Gateway route,
existing Jenkins build/package/image/archive/deploy/rollback paths, root Compose
network configuration, and `.ci/compose.sonar.yml`. The isolated Sonar server
contains no per-application service list, so adding an Order container there is
unnecessary. Existing wildcard artifacts already include Order reports. No new
Cart service, authentication mechanism, or internal Gateway secret was added.

The Orders persistence implementation is still absent. Cart/Checkout tests and a
healthy Order container do not prove that a real order can be created. This task
leaves all Orders business code untouched.


## Profile analytics verification — 2026-11-02

Verified locally on `feat/profile-analytics`, based on `main` at `3662056`:

| Component | Tests passed | Maven verify / frontend build |
| --- | ---: | --- |
| Product | 85 | passed |
| User | 39 | passed |
| Media | 32 | passed |
| Order (including analytics) | 201 | passed |
| Gateway | 25 | passed |
| Discovery | 2 | passed |
| Frontend | 208 | passed |

Backend runs used Java 21 and non-production JWT/Cloudinary fixtures. Docker-backed
order and analytics integration tests ran successfully; none were skipped.
Frontend coverage passed all four 80% thresholds. Bootstrap/configuration tests
(22) and shared scanner tests (7) passed; entrypoint Bash syntax and both Sonar
helper JavaScript syntax checks passed. Scanner-test failures printed by fixtures
are intentional test inputs, not live quality-gate outcomes.

The latest main Actions run (`41425674624`) passed tests and packaging but failed
the Order SonarQube gate on `java:S1612` in `OrderFlowIntegrationTest`. This branch
replaces that lambda with `StatusChange::status`. This fixes the reported source
finding; only a new live scan can establish the resulting gate status.

Local SonarQube reports UP, but the Jenkins controller container is in Created
state and has not served its API. No Jenkins build, live Jenkinsfile validation,
or staging deployment is claimed by this local verification. The branch push
runs the GitHub Actions quality workflow; Jenkins analysis/deployment remains
restricted to main. Review the pushed commit's Actions results before merging.

The first analytics branch scan (`41451680620`) exposed four additional findings:
`java:S2143` required `java.time` instead of `java.util.Date` in the aggregation;
`Web:S6821` treated the component's literal `role="SELLER"` as an invalid ARIA role;
and two `Web:S6845` findings rejected tabindex on non-interactive table containers.
The follow-up uses UTC Instant bounds, renames the component input to `audience`,
and removes those tabindex attributes. The actual MongoDB integration tests verify
that the driver encodes Instant date bounds correctly. No rules were suppressed
and no quality-gate thresholds were lowered.

## Real browser testing and local startup — 2026-11-02

Added the host-Docker local runner, separate `buy02-local` Compose overlay, direct
HTTP builder for Eureka with an explicitly qualified load-balanced Product client,
and Puppeteer scenarios using the real HTTPS frontend/gateway/services/databases.
The browser suite creates exact-ID fixtures, runs serially, checks cleanup, and
never uploads images to Cloudinary. Local fixture writes are restricted to the
`buy02-local` Compose project.

Local results before pushing:

- Browser scenarios: **5 passed**, covering search/filtering/pagination, mixed-seller
  cart and checkout, delivery and ownership-scoped analytics, cancellation exclusion,
  inclusive UTC dates, invalid ranges and mobile viewport.
- Angular: **209 tests passed**, coverage thresholds passed, production build passed.
- Media: **37 tests passed** and Maven verify passed. Provider/network upload and
  deletion failures return a controlled 502 message; the HTTP test checks that
  provider details are absent, and the Angular test checks that a failed upload
  keeps the draft without saving a product.
- Order: **202 tests passed**, including HTTP-client builder wiring and real MongoDB
  integration tests. Local registration/cart/analytics checks also passed after
  separating the direct Eureka builder from the Product client's load-balanced one.
- Provisioning/configuration tests: **22 passed**; scanner/report tests: **7 passed**.
- Local Compose configuration, shell/JavaScript syntax, README links, and diff
  whitespace checks passed.

GitHub Actions now builds/starts the local app and runs browser tests after backend
packaging, stops that stack before SonarQube, and retains failure screenshots.
This local report does not claim the new Actions job or a staging deployment has
passed; inspect the resulting remote run before merging.


The first new browser CI run (`41465102937`) started the full stack successfully,
but the independently installed runner Chrome reported CDP `Network.getResponseBody`
errors in three assertions. CI now installs the Chrome build selected by Puppeteer
and logs its version. All five scenarios also passed locally with that exact
Chrome build (`152.0.7977.42`), in addition to the existing local system browser.

The matching browser alone did not eliminate the CI-only CDP buffer failures
(`41466637876`). Browser tests now verify rendered product ordering/dashboard
values and use read-only API requests to check persisted results. Observed mutation
status codes still must succeed, and POST/PATCH actions are never replayed. All
five revised scenarios passed locally with the matching Chrome build. This avoids
depending on DevTools retaining network response bodies while preserving the
business assertions and the real browser/application/database path.


Run `41468236364` passed all five browser scenarios on the hosted runner. Sonar
bootstrap then returned an HTTP error before any scanner ran. Fresh-instance
initialization passed on an isolated local server. CI now checks the parsed UP
status before bootstrap and reports only endpoint/status on initialization errors;
the Compose health check also matches the status field rather than any UP substring.
Ten scanner/readiness/diagnostic fixture tests pass. The local disposable server
and its database were removed without touching the persistent SonarQube stack.
