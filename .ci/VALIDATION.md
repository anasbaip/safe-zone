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
