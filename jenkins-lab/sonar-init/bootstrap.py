#!/usr/bin/env python3
"""Idempotent provisioning with redacted, structured API error diagnostics."""
import json
import fcntl
import os
from pathlib import Path
import secrets
import re
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

COMPONENTS = {'product': 'product-service', 'user': 'user-service',
              'media': 'media-service', 'order': 'order-service', 'gateway': 'gateway',
              'discovery': 'discovery', 'frontend': 'frontend'}

GATE_NAME = 'Buy01 Quality Gate'


def configure_quality_gate(api):
    """Keep Sonar way's new-code rules and reject existing vulnerabilities."""
    gates = api.call('qualitygates/list')['qualitygates']
    if not any(gate['name'] == GATE_NAME for gate in gates):
        api.call('qualitygates/copy', {'sourceName': 'Sonar way', 'name': GATE_NAME}, post=True)
    conditions = api.call('qualitygates/show', {'name': GATE_NAME})['conditions']
    required = {'new_violations': ('GT', '0'), 'new_coverage': ('LT', '80'),
                'new_duplicated_lines_density': ('GT', '3'),
                'new_security_hotspots_reviewed': ('LT', '100'),
                'vulnerabilities': ('GT', '0'), 'bugs': ('GT', '0'),
                'coverage': ('LT', '80'), 'code_smells': ('GT', '0')}
    existing = {condition['metric']: condition for condition in conditions}
    for metric, (operator, threshold) in required.items():
        current = existing.get(metric)
        if current and (current['op'], current['error']) == (operator, threshold):
            continue
        endpoint = 'qualitygates/update_condition' if current else 'qualitygates/create_condition'
        params = {'metric': metric, 'op': operator, 'error': threshold}
        params.update({'id': current['id']} if current else {'gateName': GATE_NAME})
        api.call(endpoint, params, post=True)


def configure_project(api, key, component):
    api.call('qualitygates/select', {'gateName': GATE_NAME, 'projectKey': key}, post=True)
    api.call('settings/set', {'component': key,
             'key': 'sonar.qualitygate.ignoreSmallChanges', 'value': 'false'}, post=True)
    languages = ('ts', 'js', 'web', 'css') if component == 'frontend' else ('java', 'xml')
    for language in languages:
        api.call('qualityprofiles/add_project', {'project': key,
                 'language': language, 'qualityProfile': 'Sonar way'}, post=True)

class APIError(RuntimeError):
    def __init__(self, endpoint, status, detail):
        self.status = status
        self.detail = detail
        super().__init__(f'{endpoint}: HTTP {status}; {detail}')


class API:
    def __init__(self, url, token=''):
        self.url, self.token = url.rstrip('/'), token

    def call(self, endpoint, params=None, post=False, raw=False):
        data = urllib.parse.urlencode(params or {}).encode()
        url = self.url + '/api/' + endpoint
        if not post and data:
            url += '?' + data.decode()
        request = urllib.request.Request(url, data=data if post else None)
        if post:
            request.add_header('Content-Type', 'application/x-www-form-urlencoded')
        if self.token:
            request.add_header('Authorization', 'Bearer ' + self.token)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                if raw:
                    return {}
                body = response.read()
                return json.loads(body) if body else {}
        except (urllib.error.URLError, TimeoutError) as error:
            if isinstance(error, urllib.error.HTTPError):
                # Only structured API error messages are logged, never raw bodies.
                try:
                    payload = json.loads(error.read(16384))
                    detail = '; '.join(str(item.get('msg', '')) for item in payload.get('errors', []))
                except (ValueError, TypeError, AttributeError):
                    detail = ''
                for value in [self.token] + [str(v) for k, v in (params or {}).items()
                                              if k.lower() in ('token', 'password', 'secret')]:
                    if value:
                        detail = detail.replace(value, '[REDACTED]')
                detail = re.sub(r'\b(?:squ|sqp|sqa)_[A-Za-z0-9]+\b', '[REDACTED]', detail)
                detail = ' '.join(detail.split())[:1000]
                raise APIError(endpoint, error.code, detail or 'no safe structured API error supplied') from None
            raise RuntimeError(f'{endpoint}: connection failed or timed out; check SonarQube health and rerun bootstrap; completed credentials are preserved') from None


def project_inventory(api):
    projects, page = [], 1
    while True:
        result = api.call('projects/search', {'p': page, 'ps': 100})
        projects.extend(result['components'])
        if page * 100 >= result.get('paging', {}).get('total', len(projects)):
            return projects
        page += 1


def analysis_authorized(api, key):
    try:
        if not api.call('authentication/validate').get('valid'):
            return False
        # This scanner endpoint requires Execute Analysis on this exact project.
        # A project without a previous analysis can legitimately have no cache.
        api.call('analysis_cache/get', {'project': key}, raw=True)
        return True
    except APIError as error:
        if error.status in (401, 403):
            return False
        if error.status == 404 and error.detail == 'No cache for given branch or pull request':
            return True
        raise


def atomic_write(path, value, expected=None):
    if path.is_symlink():
        raise RuntimeError(f'{path.name}: unsafe symlink; no overwrite performed')
    if (path.read_bytes() if path.exists() else None) != expected:
        raise RuntimeError(f'{path.name}: changed concurrently; rerun bootstrap')
    fd, name = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as output:
            output.write(value + '\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def provision(api, directory):
    configure_quality_gate(api)
    projects = project_inventory(api)
    for component, suffix in COMPONENTS.items():
        key = 'buy01-' + suffix
        matches = [p for p in projects if p['key'].casefold() == key.casefold()]
        if len(matches) > 1:
            raise RuntimeError(f'{key}: ambiguous project keys; resolve explicitly')
        if matches:
            key = matches[0]['key']
        else:
            try:
                api.call('projects/create', {'project': key, 'name': key}, post=True)
            except APIError as error:
                # Recover only a concurrent create; retain the real error otherwise.
                if error.status != 400:
                    raise
                matches = [p for p in project_inventory(api) if p['key'].casefold() == key.casefold()]
                if len(matches) != 1:
                    raise
                key = matches[0]['key']
            print('Ensured project ' + key)
        configure_project(api, key, component)
        path = directory / ('sonar-' + component + '-token')
        if path.is_symlink():
            raise RuntimeError(f'{path.name}: unsafe existing file; no overwrite performed')
        previous = path.read_bytes() if path.exists() else None
        token = previous.decode().strip() if previous else ''
        if token and analysis_authorized(API(api.url, token), key):
            os.chmod(path, 0o600)
            print('Preserved credential ' + path.name)
        else:
            result = api.call('user_tokens/generate', {
                'name': 'jenkins-' + component + '-' + secrets.token_hex(8),
                'type': 'PROJECT_ANALYSIS_TOKEN', 'projectKey': key,
            }, post=True)
            token = result.get('token')
            if not token:
                raise RuntimeError('Token API did not return a token; provisioning stopped')
            if not analysis_authorized(API(api.url, token), key):
                raise RuntimeError(f'{path.name}: generated token cannot execute analysis; existing file preserved')
            atomic_write(path, token, previous)
            print(('Replaced invalid credential ' if previous is not None else 'Stored credential ') + path.name)
        key_path = directory / ('sonar-' + component + '-project-key')
        atomic_write(key_path, key, key_path.read_bytes() if key_path.exists() else None)
    url = os.environ.get('JENKINS_WEBHOOK_URL', 'http://jenkins:8080/sonarqube-webhook/')
    hooks = api.call('webhooks/list')['webhooks'] if url else []
    if url and not any(hook['url'] == url for hook in hooks):
        api.call('webhooks/create', {'name': 'Buy01 Jenkins', 'url': url}, post=True)
        print('Created Jenkins webhook')
    print('Provisioning complete; recreate Jenkins to load secret files')


def main():
    os.umask(0o077)
    directory = Path(os.environ.get('SECRETS_DIR', '/run/secrets'))
    token_path = directory / 'sonar-bootstrap-token'
    if not token_path.is_file() or token_path.is_symlink():
        raise RuntimeError('Supply sonar-bootstrap-token as an administrator User Token in the local secrets directory')
    os.chmod(token_path, 0o600)
    token = token_path.read_text().strip()
    if not token:
        raise RuntimeError('sonar-bootstrap-token is empty')
    api = API(os.environ.get('SONAR_HOST_URL', 'http://sonarqube:9000'), token)
    health_api = API(api.url)
    deadline = time.monotonic() + int(os.environ.get('SONAR_READY_TIMEOUT', '600'))
    while True:
        try:
            if health_api.call('system/status').get('status') == 'UP':
                break
        except (RuntimeError, urllib.error.URLError, TimeoutError):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError('SonarQube readiness timed out; check service health and bootstrap authentication')
        time.sleep(5)
    if not api.call('authentication/validate')['valid']:
        raise RuntimeError('Bootstrap authentication rejected; generate an administrator User Token in My Account > Security and replace only sonar-bootstrap-token')
    # Serialize authorized repairs so concurrent bootstraps cannot replace a
    # credential that another invocation has just validated and installed.
    lock_fd = os.open(directory / '.sonar-bootstrap.lock',
                      os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(lock_fd, 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Another bootstrap is running; rerun after it completes') from None
        provision(api, directory)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Only controlled errors and redacted API messages may be printed.
        print('Bootstrap failed: ' + (str(error) if isinstance(error, RuntimeError) else type(error).__name__), flush=True)
        raise SystemExit(1)
