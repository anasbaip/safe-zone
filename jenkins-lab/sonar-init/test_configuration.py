"""Exercise entrypoint behavior with fixture files, without touching a Jenkins home."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import bootstrap

ROOT = Path(__file__).resolve().parents[1]

class ConfigurationTests(unittest.TestCase):
    def run_entrypoint(self, missing=None):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            secrets = root / 'secrets'
            secrets.mkdir()
            for name in ('github-username', 'github-pat', 'jenkins-admin-password', 'buy01-env',
                         'gateway-keystore.p12', 'gateway-keystore.crt', 'gateway-keystore.key'):
                (secrets / name).write_text('fixture')
            for component, service in bootstrap.COMPONENTS.items():
                (secrets / f'sonar-{component}-token').write_text('fixture-' + component)
                (secrets / f'sonar-{component}-project-key').write_text('buy01-' + service)
            if missing:
                (secrets / missing).unlink()
            entrypoint = (ROOT / 'docker-entrypoint.sh').read_text()
            output = root / 'base.yaml'
            entrypoint = entrypoint.replace('/run/secrets', str(secrets)).replace('/casc/jenkins.yaml', str(ROOT / 'jcasc/jenkins.yaml')).replace('/tmp/jenkins-base.yaml', str(output))
            # Replace only OS handoff tools in a private PATH, never start a controller.
            tools = root / 'bin'
            tools.mkdir()
            (tools / 'chown').write_text('#!/bin/sh\nexit 0\n')
            (tools / 'su').write_text('#!/usr/bin/env python3\nimport os,json\nprint(json.dumps({k:v for k,v in os.environ.items() if k.startswith("SONAR_") or k=="CASC_JENKINS_CONFIG"}))\n')
            for path in tools.iterdir():
                path.chmod(0o700)
            env = {k: v for k, v in os.environ.items() if not k.startswith('SONAR_')}
            env.update(PATH=str(tools) + os.pathsep + os.environ['PATH'], CASC_JENKINS_CONFIG=str(ROOT / 'jcasc/jenkins.yaml'))
            result = subprocess.run(['bash', '-c', entrypoint], env=env, text=True, capture_output=True, check=True)
            return json.loads(result.stdout), output.read_text() if output.exists() else None

    def test_all_seven_credentials_and_keys_are_exported(self):
        exported, fallback = self.run_entrypoint()
        self.assertIsNone(fallback)
        self.assertEqual(exported['SONAR_ORDER_PROJECT_KEY'], 'buy01-order-service')
        self.assertEqual(exported['SONAR_ORDER_TOKEN'], 'fixture-order')
        for component in bootstrap.COMPONENTS:
            self.assertIn(f'SONAR_{component.upper()}_TOKEN', exported)
            self.assertIn(f'SONAR_{component.upper()}_PROJECT_KEY', exported)

    def test_missing_order_token_or_key_keeps_optional_sonar_configuration(self):
        for missing in ('sonar-order-token', 'sonar-order-project-key'):
            with self.subTest(missing=missing):
                exported, fallback = self.run_entrypoint(missing)
                self.assertTrue(exported['CASC_JENKINS_CONFIG'].endswith('/base.yaml'))
                self.assertNotIn('${SONAR_', fallback)
                self.assertNotIn('sonar-product-token', fallback)
                self.assertIn('${JENKINS_ADMIN_PASSWORD}', fallback)
                self.assertIn('github-mr-jenk', fallback)

    def test_jcasc_pipeline_and_live_verifier_include_every_component(self):
        config = (ROOT / 'jcasc/jenkins.yaml').read_text()
        pipeline = (ROOT.parent / 'Jenkinsfile').read_text()
        verifier = (ROOT / 'sonar-init/verify-jenkins.js').read_text()
        for component, service in bootstrap.COMPONENTS.items():
            with self.subTest(component=component):
                self.assertIn(f'id: "sonar-{component}-token"', config)
                self.assertIn('${SONAR_' + component.upper() + '_TOKEN}', config)
                self.assertIn('${SONAR_' + component.upper() + '_PROJECT_KEY}', config)
                self.assertRegex(pipeline, re.escape(f"['{service}', '{component}', 'buy01-{service}']"))
                self.assertIn("'" + component + "'", verifier)
        self.assertIn('waitForQualityGate(credentialsId: credential, abortPipeline: true)', pipeline)
        self.assertIn('throw failure', pipeline)

    def test_order_build_deploy_coverage_and_archive_paths_are_preserved(self):
        pipeline = (ROOT.parent / 'Jenkinsfile').read_text()
        self.assertIn("dir('order-service')", pipeline)
        self.assertIn('media-service order-service gateway discovery;', pipeline)
        self.assertIn('docker image inspect order-service:${BUILD_NUMBER}', pipeline)
        self.assertIn('check_container buy01-order-service || return 1', pipeline)
        self.assertIn('check_container order-mongodb || return 1', pipeline)
        self.assertIn("'**/target/site/jacoco/**, frontend/coverage/frontend/**'", pipeline)
        workflow = (ROOT.parent / '.github/workflows/quality.yml').read_text()
        self.assertIn('media-service order-service gateway discovery;', workflow)
        self.assertIn('test -s "$service/target/site/jacoco/jacoco.xml"', workflow)
        self.assertIn('**/target/site/jacoco/**', workflow)
        self.assertIn('**/target/surefire-reports/*.xml', workflow)
