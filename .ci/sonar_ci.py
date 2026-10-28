#!/usr/bin/env python3
"""Scan all provisioned projects; save reviewable metrics even when a gate fails.

--initialize is only for a fresh, disposable CI SonarQube server. Without it,
use SONAR_TOKEN to analyze against an existing server (local validation).
"""
import argparse
import base64
import html
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'jenkins-lab/sonar-init'))
from bootstrap import API, COMPONENTS, provision


class TemporaryAnalysisAPI(API):
    """Revoke only credentials minted by this run, preserving existing tokens."""
    def __init__(self, host, token):
        super().__init__(host, token)
        self.created_tokens = []

    def call(self, endpoint, params=None, post=False, raw=False):
        result = super().call(endpoint, params, post, raw)
        if endpoint == 'user_tokens/generate':
            self.created_tokens.append(params['name'])
        return result

    def __enter__(self):
        return self

    def __exit__(self, error_type, error, traceback):
        for name in self.created_tokens:
            self.call('user_tokens/revoke', {'name': name}, post=True)
        return False


def initialize(host):
    password = 'Aa1!' + secrets.token_hex(24)
    def basic_call(endpoint, params, current_password):
        request = urllib.request.Request(host + '/api/' + endpoint,
            data=urllib.parse.urlencode(params).encode(), headers={
                'Authorization': 'Basic ' + base64.b64encode(
                    ('admin:' + current_password).encode()).decode()})
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read()
            return json.loads(body) if body else {}
    basic_call('users/change_password', {'login': 'admin', 'previousPassword': 'admin',
                                        'password': password}, 'admin')
    return basic_call('user_tokens/generate', {'name': 'ci-bootstrap'}, password)['token']


def failure_details(result):
    """Render actionable gate failures without exposing API responses or tokens."""
    gate = result.get('quality_gate', {})
    lines = []
    for condition in gate.get('conditions', []):
        if condition.get('status') == 'ERROR':
            lines.append('- `{}`: actual **{}**, fails when {} **{}**.'.format(
                condition['metricKey'], condition.get('actualValue', 'n/a'),
                condition.get('comparator', '?'), condition.get('errorThreshold', 'n/a')))
    if result['scanner_exit_code']:
        lines.append('- Scanner exit code: {}.'.format(result['scanner_exit_code']))
    if gate.get('status') != 'OK' or result['scanner_exit_code']:
        for issue in result.get('issues', {}).get('issues', []):
            location = issue.get('component', 'unknown')
            if issue.get('line') is not None:
                location += ':' + str(issue['line'])
            message = issue.get('message', '').replace('\n', ' ').replace('\r', ' ')
            lines.append('- `{}` `{}`: {}'.format(location, issue.get('rule', 'unknown'), message))
    if result.get('error'):
        lines.append('- ' + result['error'])
    if not lines and gate.get('status') != 'OK':
        lines.append('- No failed conditions returned; inspect the project JSON report and scanner log.')
    return lines


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--initialize', action='store_true')
    options = args.parse_args()
    host = os.environ.get('SONAR_HOST_URL', 'http://localhost:9000').rstrip('/')
    token = initialize(host) if options.initialize else os.environ['SONAR_TOKEN']
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print('::add-mask::' + token)
    api = TemporaryAnalysisAPI(host, token)
    report = ROOT / 'reports/quality'
    report.mkdir(parents=True, exist_ok=True)
    failed = []
    results = []
    # Credentials never enter the checkout or uploaded report directories.
    with api, tempfile.TemporaryDirectory(prefix='buy01-sonar-') as folder:
        directory = Path(folder)
        os.environ['JENKINS_WEBHOOK_URL'] = ''
        provision(api, directory)
        for component, service in COMPONENTS.items():
            key = (directory / ('sonar-' + component + '-project-key')).read_text().strip()
            analysis_token = (directory / ('sonar-' + component + '-token')).read_text().strip()
            if os.environ.get('GITHUB_ACTIONS') == 'true':
                print('::add-mask::' + analysis_token)
            environment = dict(os.environ, SONAR_TOKEN=analysis_token, SONAR_HOST_URL=host)
            parameters = ['-Dsonar.projectKey=' + key, '-Dsonar.host.url=' + host,
                '-Dsonar.qualitygate.wait=true', '-Dsonar.qualitygate.timeout=600',
                '-Dsonar.qualitygate.ignoreSmallChanges=false']
            if component == 'frontend':
                if not (ROOT / service / 'coverage/frontend/lcov.info').is_file():
                    raise RuntimeError('Frontend LCOV coverage report is missing')
                command = ['sonar-scanner', *parameters]
            else:
                if not (ROOT / service / 'target/site/jacoco/jacoco.xml').is_file():
                    raise RuntimeError(service + ': JaCoCo XML report is missing')
                command = ['bash', './mvnw', '-B',
                    'org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211:sonar',
                    '-Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml',
                    *parameters]
            outcome = subprocess.run(command, cwd=ROOT / service, env=environment)
            if outcome.returncode:
                failed.append(service)
            try:
                gate = api.call('qualitygates/project_status', {'projectKey': key})['projectStatus']
                measures = api.call('measures/component', {'component': key,
                    'metricKeys': 'coverage,duplicated_lines_density,sqale_rating,security_rating,reliability_rating,security_hotspots,violations'})['component']['measures']
                issues = api.call('issues/search', {'componentKeys': key, 'resolved': 'false', 'ps': 500})
                result = {'project': key, 'scanner_exit_code': outcome.returncode,
                          'quality_gate': gate, 'measures': measures, 'issues': issues}
                if gate['status'] != 'OK' and service not in failed:
                    failed.append(service)
            except RuntimeError:
                result = {'project': key, 'scanner_exit_code': outcome.returncode,
                          'error': 'Analysis results unavailable'}
                if service not in failed:
                    failed.append(service)
            results.append(result)
            (report / (key + '.json')).write_text(json.dumps(result, indent=2) + '\n')
    summary = ['# Code quality results', '',
               '| Project | Gate | Coverage | Duplication |', '|---|---|---|---|']
    for result in results:
        metrics = {x['metric']: x.get('value', 'n/a') for x in result.get('measures', [])}
        summary.append('| {} | {} | {}% | {}% |'.format(result['project'],
            result.get('quality_gate', {}).get('status', 'ERROR'),
            metrics.get('coverage', 'n/a'), metrics.get('duplicated_lines_density', 'n/a')))
    for result in results:
        details = failure_details(result)
        if details:
            summary.extend(['', '## ' + result['project'], '', *details])
    summary.extend(['', 'Scanner or gate failures: ' + (', '.join(failed) or 'none')])
    text = '\n'.join(summary) + '\n'
    (report / 'summary.md').write_text(text)
    (report / 'index.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><title>Buy01 code quality</title><body><h1>Code quality results</h1><pre>' + html.escape(text) + '</pre></body></html>')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
            output.write(text)
    print(text)
    return 1 if failed else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print('Quality analysis could not complete: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
