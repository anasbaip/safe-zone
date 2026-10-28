"""Test the shared scan/report flow with controlled API and scanner outcomes."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('sonar_ci', Path(__file__).with_name('sonar_ci.py'))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)

class SonarCITests(unittest.TestCase):
    def run_scan(self, order_gate='OK', order_exit=0, missing_order_coverage=False):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for component, service in ci.COMPONENTS.items():
                report = root / service / ('coverage/frontend/lcov.info' if component == 'frontend' else 'target/site/jacoco/jacoco.xml')
                if missing_order_coverage and component == 'order':
                    continue
                report.parent.mkdir(parents=True)
                report.write_text('fixture coverage')
            def provision_fixture(api, directory):
                for component, service in ci.COMPONENTS.items():
                    (directory / f'sonar-{component}-token').write_text('fixture-' + component)
                    (directory / f'sonar-{component}-project-key').write_text('buy01-' + service)
            def response(endpoint, params):
                if endpoint == 'qualitygates/project_status':
                    return {'projectStatus': {'status': order_gate if params['projectKey'] == 'buy01-order-service' else 'OK'}}
                if endpoint == 'measures/component':
                    return {'component': {'measures': [{'metric': 'coverage', 'value': '85'}]}}
                if endpoint == 'issues/search':
                    return {'issues': []}
                raise AssertionError(endpoint)
            api = Mock()
            api.__enter__ = Mock(return_value=api)
            api.__exit__ = Mock(return_value=False)
            api.call.side_effect = response
            def scanner(command, cwd, env):
                return Mock(returncode=order_exit if cwd.name == 'order-service' else 0)
            with patch.object(ci, 'ROOT', root), patch.object(ci, 'provision', side_effect=provision_fixture), \
                 patch.object(ci, 'TemporaryAnalysisAPI', return_value=api), \
                 patch.object(ci.subprocess, 'run', side_effect=scanner) as scans, \
                 patch.object(ci.sys, 'argv', ['sonar_ci.py']), \
                 patch.dict(os.environ, {'SONAR_TOKEN': 'fixture-admin', 'GITHUB_STEP_SUMMARY': str(root / 'step.md')}):
                if missing_order_coverage:
                    with self.assertRaisesRegex(RuntimeError, 'order-service: JaCoCo XML report is missing'):
                        ci.main()
                    self.assertFalse(any(call.kwargs['cwd'].name == 'order-service' for call in scans.call_args_list))
                    return None
                result = ci.main()
            order_call = next(call for call in scans.call_args_list if call.kwargs['cwd'].name == 'order-service')
            self.assertIn('-Dsonar.projectKey=buy01-order-service', order_call.args[0])
            self.assertIn('-Dsonar.qualitygate.wait=true', order_call.args[0])
            self.assertIn('-Dsonar.qualitygate.ignoreSmallChanges=false', order_call.args[0])
            self.assertEqual(order_call.kwargs['env']['SONAR_TOKEN'], 'fixture-order')
            self.assertEqual(len(scans.call_args_list), 7)
            self.assertTrue((root / 'reports/quality/buy01-order-service.json').is_file())
            self.assertIn('buy01-order-service', (root / 'reports/quality/summary.md').read_text())
            self.assertIn('buy01-order-service', (root / 'reports/quality/index.html').read_text())
            self.assertIn('buy01-order-service', (root / 'step.md').read_text())
            for report in (root / 'reports/quality').iterdir():
                self.assertNotIn('fixture-order', report.read_text())
            return result

    def test_order_scans_and_appears_in_all_reports(self):
        self.assertEqual(self.run_scan(), 0)

    def test_order_gate_failure_fails_run_and_preserves_reports(self):
        self.assertEqual(self.run_scan(order_gate='ERROR'), 1)

    def test_order_scanner_failure_is_not_hidden_by_green_gate(self):
        self.assertEqual(self.run_scan(order_exit=1), 1)

    def test_missing_order_coverage_cannot_be_silently_skipped(self):
        self.run_scan(missing_order_coverage=True)


class FailureDetailsTests(unittest.TestCase):
    def test_failed_condition_and_source_issue_are_actionable(self):
        result = {
            'scanner_exit_code': 1,
            'quality_gate': {'status': 'ERROR', 'conditions': [
                {'status': 'OK', 'metricKey': 'coverage'},
                {'status': 'ERROR', 'metricKey': 'code_smells',
                 'actualValue': '2', 'comparator': 'GT', 'errorThreshold': '0'}]},
            'issues': {'issues': [{'component': 'buy01-gateway:src/Filter.java',
                                  'line': 42, 'rule': 'java:S123', 'message': 'Fix this condition'}]}}
        text = '\n'.join(ci.failure_details(result))
        self.assertIn('`code_smells`: actual **2**, fails when GT **0**', text)
        self.assertIn('src/Filter.java:42', text)
        self.assertIn('java:S123', text)
        self.assertIn('Scanner exit code: 1', text)
        self.assertNotIn('`coverage`', text)

    def test_success_has_no_failure_details(self):
        self.assertEqual(ci.failure_details({
            'scanner_exit_code': 0, 'quality_gate': {'status': 'OK'},
            'issues': {'issues': [{'message': 'Existing issue'}]}}), [])

    def test_missing_analysis_is_reported(self):
        self.assertIn('- Analysis results unavailable', ci.failure_details({
            'scanner_exit_code': 1, 'error': 'Analysis results unavailable'}))
