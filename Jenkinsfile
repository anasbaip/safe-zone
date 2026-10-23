pipeline {
    agent any
    options {
        // Prevent two deployments from running at the same time
        disableConcurrentBuilds()
    }
    triggers {
        // Triggers the build instantly when GitHub sends a webhook push event
        githubPush()
        cron('H H(1-4) * * *')
    }
    environment {
        BRANCH_NAME = "${env.GIT_BRANCH ? env.GIT_BRANCH.replace('origin/', '') : 'main'}"
    }
    stages {
        // ========================================
        // 1. Docker Environment Check
        // ========================================
        stage('Docker Check') {
            steps {
                sh 'docker version'
            }
        }

        // ========================================
        // 2. Backend Tests
        // ========================================
        stage('Product Service Tests') {
            steps {
                dir('product-service') {
                    sh './mvnw -B clean test'
                }
            }
        }

        stage('User Service Tests') {
            steps {
                dir('user-service') {
                    sh './mvnw -B clean test'
                }
            }
        }

        stage('Media Service Tests') {
            steps {
                dir('media-service') {
                    sh './mvnw -B clean test'
                }
            }
        }

        stage('Order Service Tests') {
            steps {
                dir('order-service') {
                    sh './mvnw -B clean test'
                }
            }
        }

        stage('Gateway Tests') {
            steps {
                withCredentials([
                    file(
                        credentialsId: 'buy01-env',
                        variable: 'ENV_FILE'
                    )
                ]) {
                    dir('gateway') {
                        sh '''
                            set +x
                            set -a
                            . "$ENV_FILE"
                            set +a
                            ./mvnw -B clean test
                        '''
                    }
                }
            }
        }

        stage('Discovery Tests') {
            steps {
                dir('discovery') {
                    sh './mvnw -B clean test'
                }
            }
        }

        stage('Frontend Tests') {
            steps {
                dir('frontend') {
                    sh 'npm ci'
                    sh 'CI=true npm run test:coverage'
                }
            }
        }

        // ========================================
        // 3. Package Backend + JaCoCo XML report
        // ========================================
        // Tests already ran. No clean here: preserve target/jacoco.exec.
        stage('Package Backend') {
            steps {
                dir('product-service') {
                    sh './mvnw -B verify -DskipTests'
                }
                dir('user-service') {
                    sh './mvnw -B verify -DskipTests'
                }
                dir('media-service') {
                    sh './mvnw -B verify -DskipTests'
                }
                dir('order-service') {
                    sh './mvnw -B verify -DskipTests'
                }
                withCredentials([file(credentialsId: 'buy01-env', variable: 'ENV_FILE')]) {
                    dir('gateway') {
                        sh '''
                            set +x
                            set -a
                            . "$ENV_FILE"
                            set +a
                            ./mvnw -B verify -DskipTests
                        '''
                    }
                }
                dir('discovery') {
                    sh './mvnw -B verify -DskipTests'
                }
            }
        }

        // ========================================
        // 4. Check Backend Artifacts
        // ========================================
        stage('Check Backend Artifacts') {
            steps {
                sh '''
                    echo "=== Product Service ==="
                    ls -lh product-service/target/*.jar
                    echo "=== User Service ==="
                    ls -lh user-service/target/*.jar
                    echo "=== Media Service ==="
                    ls -lh media-service/target/*.jar
                    echo "=== Order Service ==="
                    ls -lh order-service/target/*.jar
                    echo "=== Gateway ==="
                    ls -lh gateway/target/*.jar
                    echo "=== Discovery ==="
                    ls -lh discovery/target/*.jar
                '''
            }
        }

        // ========================================
        // 5. Frontend CI
        // ========================================
        stage('Package Frontend') {
            steps {
                dir('frontend') {
                    sh 'npm run build'
                    sh 'ls -la dist/frontend/browser'
                }
            }
        }

        // ========================================
        // 6. SonarQube Analysis and Quality Gates
        // ========================================
        stage('Coverage') {
            steps {
                sh '''
                    for component in product-service user-service media-service gateway discovery; do
                        test -s "$component/target/site/jacoco/jacoco.xml"
                    done
                    test -s frontend/coverage/frontend/lcov.info
                '''
            }
        }
        stage('SonarQube analyses and Quality Gates') {
            steps {
                script {
                    if (env.BRANCH_NAME != 'main') {
                        error('Community Build analysis is restricted to main')
                    }
                    def components = [
                        ['product-service', 'product', 'buy01-product-service'],
                        ['user-service', 'user', 'buy01-user-service'],
                        ['media-service', 'media', 'buy01-media-service'],
                        ['gateway', 'gateway', 'buy01-gateway'],
                        ['discovery', 'discovery', 'buy01-discovery'],
                        ['frontend', 'frontend', 'buy01-frontend']
                    ]
                    // Consume each CE task before submitting the next analysis.
                    for (component in components) {
                        def credential = "sonar-${component[1]}-token"
                        def projectKey = env.getProperty("SONAR_${component[1].toUpperCase()}_PROJECT_KEY")
                        if (!projectKey?.trim()) {
                            error("Missing exact SonarQube project key for ${component[1]}; run sonar-init and restart Jenkins")
                        }
                        dir(component[0]) {
                            withSonarQubeEnv(installationName: 'SonarQube', credentialsId: credential) {
                                withCredentials([string(credentialsId: credential, variable: 'SONAR_TOKEN')]) {
                                    withEnv(["SONAR_PROJECT_KEY=${projectKey}"]) {
                                        if (component[0] == 'frontend') {
                                            sh '''
                                                set +x
                                                test -s coverage/frontend/lcov.info
                                                sonar-scanner -Dsonar.projectKey="$SONAR_PROJECT_KEY"
                                            '''
                                        } else {
                                            sh '''
                                                set +x
                                                test -s target/site/jacoco/jacoco.xml
                                                ./mvnw -B org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211:sonar \\
                                                  -Dsonar.projectKey="$SONAR_PROJECT_KEY" \\
                                                  -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
                                            '''
                                        }
                                    }
                                }
                            }
                            timeout(time: 10, unit: 'MINUTES') {
                                try {
                                    def gate = waitForQualityGate(credentialsId: credential, abortPipeline: true)
                                    if (gate.status != 'OK') {
                                        error("Quality Gate failed for ${projectKey}: ${gate.status}")
                                    }
                                } catch (Exception failure) {
                                    withCredentials([string(credentialsId: credential, variable: 'SONAR_TOKEN')]) {
                                        withEnv(["SONAR_PROJECT_KEY=${projectKey}", 'SONAR_HOST_URL=http://sonarqube:9000']) {
                                            sh(script: 'set +x; node ../jenkins-lab/sonar-init/quality-gate.js', returnStatus: true)
                                        }
                                    }
                                    throw failure
                                }
                            }
                        }
                    }
                }
            }
        }
        // Image builds start only after all Quality Gates pass.
        stage('Build Product Image') {
            steps {
                dir('product-service') {
                    sh '''
                        docker build \\
                            -t product-service:${BUILD_NUMBER} \\
                            .
                    '''
                }
            }
        }

        stage('Build User Image') {
            steps {
                dir('user-service') {
                    sh '''
                        docker build \\
                            -t user-service:${BUILD_NUMBER} \\
                            .
                    '''
                }
            }
        }

        stage('Build Media Image') {
            steps {
                dir('media-service') {
                    sh '''
                        docker build \\
                            -t media-service:${BUILD_NUMBER} \\
                            .
                    '''
                }
            }
        }

        stage('Build Order Image') {
            steps {
                dir('order-service') {
                    sh '''
                        docker build \\
                            -t order-service:${BUILD_NUMBER} \\
                            .
                    '''
                }
            }
        }

        stage('Build Gateway Image') {
            steps {
                dir('gateway') {
                    sh '''
                        docker build \\
                            -t gateway:${BUILD_NUMBER} \\
                            .
                    '''
                }
            }
        }

        stage('Build Discovery Image') {
            steps {
                dir('discovery') {
                    sh '''
                        docker build \\
                            -t discovery:${BUILD_NUMBER} \\
                            .
                    '''
                }
            }
        }

        stage('Build Frontend Image') {
            steps {
                dir('frontend') {
                    sh '''
                        docker build \\
                            -t frontend:${BUILD_NUMBER} \\
                            .
                    '''
                }
            }
        }

        // ========================================
        // 7. Check Complete Release
        // ========================================
        stage('Check Docker Images') {
            steps {
                sh '''
                    echo "================================"
                    echo "Release #${BUILD_NUMBER}"
                    echo "================================"
                    docker image inspect product-service:${BUILD_NUMBER} > /dev/null
                    docker image inspect user-service:${BUILD_NUMBER} > /dev/null
                    docker image inspect media-service:${BUILD_NUMBER} > /dev/null
                    docker image inspect order-service:${BUILD_NUMBER} > /dev/null
                    docker image inspect gateway:${BUILD_NUMBER} > /dev/null
                    docker image inspect discovery:${BUILD_NUMBER} > /dev/null
                    docker image inspect frontend:${BUILD_NUMBER} > /dev/null
                    echo "All Docker images exist:"
                    echo "product-service:${BUILD_NUMBER}"
                    echo "user-service:${BUILD_NUMBER}"
                    echo "media-service:${BUILD_NUMBER}"
                    echo "order-service:${BUILD_NUMBER}"
                    echo "gateway:${BUILD_NUMBER}"
                    echo "discovery:${BUILD_NUMBER}"
                    echo "frontend:${BUILD_NUMBER}"
                '''
            }
        }

        // ========================================
        // 8. Prepare TLS Certificates
        // ========================================
        stage('Prepare TLS Certificates') {
            when {
                expression { env.BRANCH_NAME == 'main' && !currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause') }
            }
            steps {
                withCredentials([
                    file(
                        credentialsId: 'gateway-p12',
                        variable: 'P12_FILE'
                    ),
                    file(
                        credentialsId: 'gateway-crt',
                        variable: 'CRT_FILE'
                    ),
                    file(
                        credentialsId: 'gateway-key',
                        variable: 'KEY_FILE'
                    )
                ]) {
                    sh '''
                        set +x
                        CERT_DIR="/var/jenkins_home/deploy-secrets/buy01/certs"
                        mkdir -p "$CERT_DIR"
                        cp "$P12_FILE" \\
                           "$CERT_DIR/gateway-keystore.p12"
                        cp "$CRT_FILE" \\
                           "$CERT_DIR/gateway-keystore.crt"
                        cp "$KEY_FILE" \\
                           "$CERT_DIR/gateway-keystore.key"
                        chmod 600 \\
                            "$CERT_DIR/gateway-keystore.p12"
                        chmod 644 \\
                            "$CERT_DIR/gateway-keystore.crt"
                        chmod 600 \\
                            "$CERT_DIR/gateway-keystore.key"
                        echo "TLS certificates prepared."
                    '''
                }
            }
        }

        // ========================================
        // 9. Validate Docker Compose
        // ========================================
        stage('Validate Compose') {
            when {
                expression { env.BRANCH_NAME == 'main' && !currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause') }
            }
            steps {
                withCredentials([
                    file(
                        credentialsId: 'buy01-env',
                        variable: 'ENV_FILE'
                    )
                ]) {
                    sh '''
                        set +x
                        APP_IMAGE_TAG=${BUILD_NUMBER} \\
                        docker compose \\
                            -p buy01 \\
                            --env-file "$ENV_FILE" \\
                            config > /dev/null
                        echo "Docker Compose configuration is valid."
                    '''
                }
            }
        }

        // ========================================
        // 10. Automatic Deployment
        // ========================================
        stage('Deploy') {
            when {
                expression { env.BRANCH_NAME == 'main' && !currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause') }
            }
            steps {
                withCredentials([
                    file(
                        credentialsId: 'buy01-env',
                        variable: 'ENV_FILE'
                    )
                ]) {
                    sh '''
                        set +x
                        echo "================================"
                        echo "Deploying release #${BUILD_NUMBER}"
                        echo "================================"
                        APP_IMAGE_TAG=${BUILD_NUMBER} \\
                        docker compose \\
                            -p buy01 \\
                            --env-file "$ENV_FILE" \\
                            up -d
                        echo "Release #${BUILD_NUMBER} containers started."
                    '''
                }
            }
        }

        // ========================================
        // 11. Health Check + Automatic Rollback
        // ========================================
        stage('Post-Deployment Health Check') {
            when {
                expression { env.BRANCH_NAME == 'main' && !currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause') }
            }
            steps {
                withCredentials([
                    file(
                        credentialsId: 'buy01-env',
                        variable: 'ENV_FILE'
                    )
                ]) {
                    sh '''
                        set +x
                        MAX_ATTEMPTS=18
                        SLEEP_SECONDS=5
                        RELEASE_FILE="/var/jenkins_home/deploy-secrets/buy01/last-successful-release"
                        # ==================================
                        # Check one container
                        # ==================================
                        check_container() {
                            CONTAINER="$1"
                            echo ""
                            echo "--------------------------------"
                            echo "Checking $CONTAINER"
                            echo "--------------------------------"
                            for i in $(seq 1 $MAX_ATTEMPTS); do
                                STATE=$(docker inspect \\
                                    --format='{{.State.Status}}' \\
                                    "$CONTAINER" \\
                                    2>/dev/null || echo "missing")
                                HEALTH=$(docker inspect \\
                                    --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' \\
                                    "$CONTAINER" \\
                                    2>/dev/null || echo "missing")
                                echo "Attempt $i/$MAX_ATTEMPTS -> state=$STATE health=$HEALTH"
                                # Container crashed
                                if [ "$STATE" = "exited" ] || \\
                                   [ "$STATE" = "dead" ] || \\
                                   [ "$STATE" = "missing" ]; then
                                    echo "ERROR: $CONTAINER stopped unexpectedly."
                                    return 1
                                fi
                                # Docker healthcheck succeeded
                                if [ "$HEALTH" = "healthy" ]; then
                                    echo "$CONTAINER is healthy."
                                    return 0
                                fi
                                sleep $SLEEP_SECONDS
                            done
                            echo "ERROR: $CONTAINER did not become healthy."
                            return 1
                        }
                        # ==================================
                        # Check whole platform
                        # ==================================
                        health_check() {
                            # Databases
                            check_container product-mongodb || return 1
                            check_container media-mongodb || return 1
                            check_container user-mongodb || return 1
                            check_container order-mongodb || return 1
                            # Infrastructure
                            check_container buy01-kafka || return 1
                            check_container buy01-discovery || return 1
                            # Backend
                            check_container buy01-user-service || return 1
                            check_container buy01-product-service || return 1
                            check_container buy01-media-service || return 1
                            check_container buy01-order-service || return 1
                            check_container buy01-gateway || return 1
                            # Frontend
                            check_container buy01-frontend || return 1
                            return 0
                        }
                        # ==========================================
                        # Check NEW release
                        # ==========================================
                        echo ""
                        echo "================================"
                        echo "Checking release #${BUILD_NUMBER}"
                        echo "================================"
                        if health_check; then
                            echo ""
                            echo "================================"
                            echo "RELEASE HEALTHY"
                            echo "Release #${BUILD_NUMBER}"
                            echo "================================"
                            # Save current release as last good release
                            mkdir -p "$(dirname "$RELEASE_FILE")"
                            echo "${BUILD_NUMBER}" > "$RELEASE_FILE"
                            echo "Saved release #${BUILD_NUMBER} as last successful release."
                            exit 0
                        fi
                        # ==========================================
                        # New release failed
                        # ==========================================
                        echo ""
                        echo "================================"
                        echo "RELEASE FAILED"
                        echo "Release #${BUILD_NUMBER}"
                        echo "================================"
                        # ==========================================
                        # Check if a previous release exists
                        # ==========================================
                        if [ ! -f "$RELEASE_FILE" ]; then
                            echo "No previous successful release exists."
                            echo "Rollback cannot be performed."
                            exit 1
                        fi
                        PREVIOUS_RELEASE=$(cat "$RELEASE_FILE")
                        echo ""
                        echo "Previous successful release: #$PREVIOUS_RELEASE"
                        echo "Starting automatic rollback..."
                        # ==========================================
                        # Rollback deployment
                        # ==========================================
                        APP_IMAGE_TAG=$PREVIOUS_RELEASE \\
                        docker compose \\
                            -p buy01 \\
                            --env-file "$ENV_FILE" \\
                            up -d
                        echo ""
                        echo "Rollback deployment started."
                        echo "Checking rollback health..."
                        # ==========================================
                        # Verify rollback
                        # ==========================================
                        if health_check; then
                            echo ""
                            echo "================================"
                            echo "ROLLBACK SUCCESSFUL"
                            echo ""
                            echo "Failed release: #${BUILD_NUMBER}"
                            echo "Running release: #$PREVIOUS_RELEASE"
                            echo "================================"
                        else
                            echo ""
                            echo "================================"
                            echo "ROLLBACK FAILED"
                            echo ""
                            echo "Manual intervention required."
                            echo "================================"
                        fi
                        # Important:
                        # the current Jenkins build must remain FAILED
                        # because the new release itself failed.
                        exit 1
                    '''
                }
            }
        }
    }

    // ========================================
    // Final Pipeline Actions
    // ========================================
    post {
        success {
            echo """
            ==========================================
            PIPELINE SUCCESS
            Release #${BUILD_NUMBER}
            successfully built, deployed
            and passed health checks.
            Images:
            product-service:${BUILD_NUMBER}
            user-service:${BUILD_NUMBER}
            media-service:${BUILD_NUMBER}
            order-service:${BUILD_NUMBER}
            gateway:${BUILD_NUMBER}
            discovery:${BUILD_NUMBER}
            frontend:${BUILD_NUMBER}
            ==========================================
            """
            script {
                try {
                    mail to: 'ayoubzone01@gmail.com',
                         subject: "✅ SUCCESS: Release #${BUILD_NUMBER} Deployed",
                         body: """
                         Hello Team,
                         Release #${BUILD_NUMBER} was successfully built, tested, and deployed!
                         All microservices and database containers passed health checks.
                         Regards,
                         Jenkins CI/CD
                         """
                } catch (Exception e) {
                    echo "Warning: Email notification skipped (SMTP not configured): ${e.message}"
                }
            }
        }
        failure {
            echo """
            ==========================================
            PIPELINE FAILED
            Build #${BUILD_NUMBER}
            The release failed.
            If a previous successful release existed,
            the pipeline attempted an automatic rollback.
            Check the Jenkins logs for details.
            ==========================================
            """
            script {
                try {
                    mail to: 'ayoubzone01@gmail.com',
                         subject: "❌ FAILURE: Build #${BUILD_NUMBER}",
                         body: """
                         Attention Team,
                         Build #${BUILD_NUMBER} has FAILED.
                         Rollback may have been attempted if post-deploy health checks failed.
                         Regards,
                         Jenkins CI/CD
                         """
                } catch (Exception e) {
                    echo "Warning: Email notification skipped (SMTP not configured): ${e.message}"
                }
            }
        }
        always {
            // Archive and publish JUnit test reports for all microservices
            archiveArtifacts artifacts: '**/target/site/jacoco/**, frontend/coverage/frontend/**', allowEmptyArchive: true
            junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
            echo 'Pipeline finished.'
        }
    }
}