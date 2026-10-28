Run from `jenkins-lab`. Use the existing Compose project name, `sonar-lab`, to retain the existing Jenkins volumes. Never remove volumes or projects.

One-time administrator setup: sign in at http://localhost:9000, open **My Account → Security**, generate a **User Token** as a SonarQube administrator, and save its value in `jenkins-secrets/sonar-bootstrap-token`. Do not put it in Git, chat, command arguments, or shell history. To enter it without echoing or recording the value in shell history:

```bash
(
  umask 077
  read -r -s -p 'SonarQube administrator User Token: ' bootstrap_input
  printf '\n'
  printf '%s\n' "$bootstrap_input" > jenkins-secrets/sonar-bootstrap-token
  unset bootstrap_input
)
chmod 600 jenkins-secrets/sonar-bootstrap-token
```

Provision, load, and verify:

```bash
python3 -m unittest discover -s sonar-init -v
bash -n docker-entrypoint.sh
node --check sonar-init/quality-gate.js
node --check sonar-init/verify-jenkins.js
docker compose -p sonar-lab config --quiet
docker compose -p sonar-lab --profile bootstrap run --rm sonar-init
docker compose -p sonar-lab up -d --build --no-deps --force-recreate jenkins
# Wait until Jenkins is healthy before verification.
docker compose -p sonar-lab ps jenkins
docker compose -p sonar-lab exec -T jenkins node < sonar-init/verify-jenkins.js
```

Bootstrap enumerates projects with pagination, preserves existing project key spelling and history, creates missing projects, and records each exact key in `sonar-<component>-project-key`. It preserves tokens that authenticate and pass the scanner cache authorization check. Missing, empty, expired, revoked, or incorrectly scoped tokens are replaced atomically only after the newly generated project-analysis token passes the same checks. Network/API failures stop provisioning without overwriting existing credentials. Existing remote tokens are never revoked. Concurrent bootstrap processes are serialized.

API failures display only structured error messages with credentials redacted. HTTP 400 is never treated as generic success: a second inventory must find the intended project before bootstrap recovers from a concurrent creation.

Jenkins starts with seven secret-text credentials and seven exact project keys after bootstrap. If any credential/key file is missing, the entrypoint omits the Sonar configuration while allowing administrator access for recovery; analysis fails safely. A recreation is needed to load repaired files into the entrypoint environment and JCasC.

The pipeline runs frontend and backend tests before packaging, verifies JaCoCo XML and frontend LCOV reports, and performs each of the seven analyses followed immediately by its Quality Gate. Sequential pairs are required by the Jenkins Sonar plugin. Image builds and deployment follow only after all seven gates pass. Gate failure reporting uses that scanner's CE task and analysis ID to print failed metrics without overriding the original failure. Deployment and rollback logic remain intact.

The `first-pipeline` job loads `Jenkinsfile` from GitHub `main`. Local Jenkinsfile/helper changes must be made available in that SCM revision before **Build Now** uses them; this repair does not commit or push. Full pipeline testing requires real credentials and that updated SCM revision.

OrderService (including Cart and Checkout) is provisioned as `buy01-order-service`.
Its files are `sonar-order-token` and `sonar-order-project-key`; the entrypoint loads
`SONAR_ORDER_TOKEN` / `SONAR_ORDER_PROJECT_KEY`, and JCasC defines `sonar-order-token`.
There is no new secret to hand-create for Order: the existing bootstrap administrator
User Token provisions its project-scoped analysis token, just like the other six.
A valid existing project/token is preserved on rerun. Do not delete Jenkins or
SonarQube volumes when adding this seventh component.

Local tests (no Jenkins controller needed):

```sh
python3 -m unittest discover -s jenkins-lab/sonar-init -p 'test_*.py'
python3 -m unittest discover -s .ci -p 'test_*.py'
```

Entrypoint tests use private fixture files and stub the controller handoff; they
are not live JCasC schema/plugin validation. `verify-jenkins.js` checks all seven
credentials and project permissions only when explicitly run inside live Jenkins.
