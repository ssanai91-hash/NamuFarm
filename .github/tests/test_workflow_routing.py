"""Offline checks of the actual workflow YAML; never starts an Actions build.

Only the glob subset used here is implemented (*, ** and leading !).
The tests reject newly introduced syntax rather than silently approximating it.
"""
import re
import unittest
from pathlib import Path

import yaml


class ActionsLoader(yaml.SafeLoader):
    pass


# Actions uses YAML 1.2: `on` is a key, not YAML 1.1's boolean True.
ActionsLoader.yaml_implicit_resolvers = {
    key: [(tag, rx) for tag, rx in values if tag != 'tag:yaml.org,2002:bool']
    for key, values in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
ActionsLoader.add_implicit_resolver(
    'tag:yaml.org,2002:bool', re.compile(r'^(?:true|false)$'), list('tf')
)
ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = {
    path.name: yaml.load(path.read_text(encoding='utf-8'), Loader=ActionsLoader)
    for path in (ROOT / '.github/workflows').glob('*.yml')
}
ANDROID = 'android-ci.yml'
DESKTOP = 'desktop-ci.yml'
PROTOCOL = 'protocol-ci.yml'
CONFIG = 'ci-config.yml'
VALIDATION = {ANDROID, DESKTOP, PROTOCOL, CONFIG}


def matches(path, pattern):
    if any(char in pattern for char in '[]+?\\'):
        raise AssertionError(f'Unsupported glob syntax: {pattern}')
    result = ''
    index = 0
    while index < len(pattern):
        char = pattern[index]
        if pattern[index:index + 2] == '**':
            result += '.*'
            index += 2
        else:
            result += '[^/]*' if char == '*' else re.escape(char)
            index += 1
    return re.fullmatch(result, path) is not None


def included(path, patterns):
    selected = False
    for pattern in patterns:
        negative = pattern.startswith('!')
        if matches(path, pattern[1:] if negative else pattern):
            selected = not negative
    return selected


def triggers(name, files, event='push', branch='main'):
    settings = WORKFLOWS[name]['on']
    if event not in settings:
        return False
    rule = settings[event] or {}
    if event == 'workflow_dispatch':
        return True
    if branch not in rule.get('branches', [branch]):
        return False
    return any(included(path, rule['paths']) for path in files) if 'paths' in rule else True


def routed(files, **kwargs):
    return {name for name in VALIDATION if triggers(name, files, **kwargs)}


def steps(name, job):
    return WORKFLOWS[name]['jobs'][job]['steps']


def commands(name, job):
    return '\n'.join(step.get('run', '') for step in steps(name, job))


class WorkflowRoutingTest(unittest.TestCase):
    def test_change_routing_for_push_and_pull_request(self):
        cases = [
            ('app/src/main/java/com/example/namufarm/MainActivity.kt', {ANDROID}),
            ('app/src/test/java/com/example/namufarm/data/CommerceMigrationTest.kt', {ANDROID}),
            ('app/src/test/resources/website-crypto-test.json', {ANDROID}),
            ('app/build.gradle.kts', {ANDROID}),
            ('gradle/libs.versions.toml', {ANDROID}),
            ('gradle/wrapper/gradle-wrapper.properties', {ANDROID}),
            ('gradlew', {ANDROID}), ('gradlew.bat', {ANDROID}),
            ('gradle.properties', {ANDROID}), ('settings.gradle.kts', {ANDROID}),
            ('build.gradle.kts', {ANDROID}), ('local.properties.example', {ANDROID}),
            ('scripts/prepare_android_signing.py', {ANDROID}),
            ('desktop/namufarm_desktop/api.py', {ANDROID, DESKTOP}),
            ('desktop/namufarm_desktop/crypto.py', {ANDROID, DESKTOP}),
            ('desktop/namufarm_desktop/errors.py', {ANDROID, DESKTOP}),
            ('desktop/tests/check_android_backup.py', {ANDROID, DESKTOP}),
            ('desktop/namufarm_desktop/workspace.py', {DESKTOP}),
            ('desktop/namufarm_desktop/assets/tree.png', {DESKTOP}),
            ('desktop/pyproject.toml', {DESKTOP}),
            ('desktop/tests/test_local_sync.py', {DESKTOP}),
            ('server/quote-requests/AnnualLedgers.gs', {DESKTOP, PROTOCOL}),
            ('server/quote-requests/tests/fixture.cjs', {DESKTOP, PROTOCOL}),
            ('website/components/quote-home.tsx', {PROTOCOL}),
            ('website/app/globals.css', {PROTOCOL}),
            ('website/lib/quote-receipt.ts', {PROTOCOL}),
            ('website/tests/quote-service.test.mjs', {PROTOCOL}),
            ('website/public/plants/v1/pine.webp', {PROTOCOL}),
            ('website/package.json', {PROTOCOL}),
            ('website/pnpm-lock.yaml', {PROTOCOL}),
            ('README.md', set()), ('docs/ARCHITECTURE.md', set()),
            ('desktop/README.md', set()), ('website/README.md', set()),
            ('.github/tests/test_workflow_routing.py', {CONFIG}),
            ('.github/workflows/android-ci.yml', {ANDROID, CONFIG}),
            ('.github/workflows/desktop-ci.yml', {DESKTOP, CONFIG}),
            ('.github/workflows/protocol-ci.yml', {PROTOCOL, CONFIG}),
            ('.github/workflows/build-downloads.yml', {CONFIG}),
            ('.github/workflows/desktop-exe-build.yml', {CONFIG}),
        ]
        for event in ['push', 'pull_request']:
            for path, expected in cases:
                with self.subTest(event=event, path=path):
                    self.assertEqual(routed([path], event=event), expected)

    def test_ordered_exclusions_and_root_only_globs(self):
        self.assertFalse(matches('website/nested.gradle.kts', '*.gradle.kts'))
        self.assertTrue(matches('desktop/docs/design.md', 'desktop/**.md'))
        self.assertFalse(included('desktop/README.md', WORKFLOWS[DESKTOP]['on']['push']['paths']))
        self.assertEqual(routed(['desktop/README.md', 'desktop/pyproject.toml']), {DESKTOP})

    def test_manual_checks_bypass_path_and_branch_filters(self):
        for name in VALIDATION | {'android-apk-build.yml', 'desktop-exe-build.yml', 'build-downloads.yml'}:
            with self.subTest(name=name):
                self.assertTrue(triggers(name, [], event='workflow_dispatch', branch='fix/local-first-integrity-review'))

    def test_no_duplicate_repair_branch_push_and_pr_checks(self):
        self.assertFalse(triggers(DESKTOP, ['desktop/namufarm_desktop/api.py'], branch='fix/local-first-integrity-review'))
        self.assertTrue(triggers(DESKTOP, ['desktop/namufarm_desktop/api.py'], event='pull_request'))

    def test_push_and_pr_filters_remain_identical(self):
        for name in VALIDATION:
            with self.subTest(name=name):
                rule = WORKFLOWS[name]['on']
                self.assertEqual(rule['push'], rule['pull_request'])

    def test_mixed_and_deleted_or_renamed_files_still_select_all_affected_checks(self):
        self.assertEqual(routed(['app/build.gradle.kts', 'website/app/page.tsx', 'desktop/pyproject.toml']), {ANDROID, DESKTOP, PROTOCOL})
        # GitHub includes removed paths in the event diff; a rename may match either path.
        self.assertEqual(routed(['app/src/main/old.kt', 'desktop/namufarm_desktop/new.py']), {ANDROID, DESKTOP})
        self.assertEqual(routed(['server/quote-requests/Old.gs']), {DESKTOP, PROTOCOL})

    def test_unrelated_push_does_not_cancel_an_earlier_domain(self):
        self.assertIn(ANDROID, routed(['app/src/main/changed.kt']))
        self.assertNotIn(ANDROID, routed(['website/app/page.tsx']))
        self.assertIn(DESKTOP, routed(['server/quote-requests/Code.gs']))
        self.assertNotIn(DESKTOP, routed(['website/app/page.tsx']))
        # A website push can supersede a server protocol run, so each runs BOTH suites.
        run = commands(PROTOCOL, 'protocol')
        self.assertIn('node --test server/quote-requests/tests/*.test.cjs', run)
        self.assertIn('website/tests/privacy-crypto.test.mjs', run)
        self.assertIn('website/tests/quote-service.test.mjs', run)

    def test_auto_cancellation_is_scoped_and_manual_runs_are_independent(self):
        prefixes = set()
        for name in VALIDATION:
            group = WORKFLOWS[name]['concurrency']
            self.assertIs(group['cancel-in-progress'], True)
            self.assertIn('github.event.pull_request.number', group['group'])
            self.assertIn('github.ref', group['group'])
            self.assertIn("'workflow_dispatch'", group['group'])
            self.assertIn("format('manual-{0}', github.run_id)", group['group'])
            prefixes.add(group['group'].split('${{')[0])
        self.assertEqual(len(prefixes), len(VALIDATION))

    def test_push_debounce_precedes_expensive_setup(self):
        for name, job in [(ANDROID, 'build'), (DESKTOP, 'test'), ('android-apk-build.yml', 'apk')]:
            with self.subTest(name=name):
                first = steps(name, job)[0]
                self.assertEqual(first['if'], "github.event_name == 'push'")
                self.assertEqual(first['run'], 'sleep 30')

    def test_android_full_regression_and_interoperability_are_preserved(self):
        run = commands(ANDROID, 'build')
        for required in ['testDebugUnitTest assembleDebug', 'desktop/tests/check_android_backup.py', 'pypdf', 'quote-example.pdf', 'quote-multipage.pdf']:
            self.assertIn(required, run)
        self.assertNotIn('node --test', run)

    def test_linux_and_windows_full_desktop_tests_are_preserved(self):
        for job in ['test', 'windows']:
            self.assertIn('python -m unittest discover -s desktop/tests -v', commands(DESKTOP, job))
            # Node is still required by desktop/tests/server_bridge.cjs on both OSes.
            self.assertTrue(any(step.get('uses', '').startswith('actions/setup-node@') for step in steps(DESKTOP, job)))
        self.assertEqual(WORKFLOWS[DESKTOP]['jobs']['windows']['needs'], 'test')
        self.assertNotIn('node --test server/', commands(DESKTOP, 'test'))

    def test_manual_packaging_and_marked_pushes_are_preserved(self):
        android = WORKFLOWS[ANDROID]['on']['workflow_dispatch']['inputs']
        desktop = WORKFLOWS[DESKTOP]['on']['workflow_dispatch']['inputs']
        self.assertIn('build_signed_apk', android)
        self.assertIn('upload_ui_previews', android)
        self.assertIn('build_exe', desktop)
        apk = WORKFLOWS['android-apk-build.yml']
        self.assertIn('push', apk['on'])
        self.assertEqual(apk['on']['workflow_dispatch']['inputs']['apk_type']['options'], ['debug', 'release'])
        self.assertEqual(apk['jobs']['apk']['if'], "github.event_name == 'workflow_dispatch' || contains(github.event.head_commit.message, '[build apk]')")
        self.assertNotIn('concurrency', apk)  # skipped, unmarked pushes cannot cancel a requested APK
        self.assertIs(apk['jobs']['apk']['concurrency']['cancel-in-progress'], True)
        self.assertIn('manual-{0}', apk['jobs']['apk']['concurrency']['group'])
        self.assertEqual(set(WORKFLOWS['desktop-exe-build.yml']['on']), {'workflow_dispatch'})
        self.assertEqual(set(WORKFLOWS['build-downloads.yml']['on']), {'workflow_dispatch'})

    def test_packager_is_not_installed_on_automatic_windows_tests(self):
        packager = [step for step in steps(DESKTOP, 'windows') if 'pip install' in step.get('run', '') and 'pyinstaller' in step['run'].lower()]
        self.assertEqual(len(packager), 1)
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.build_exe", packager[0]['if'])

    def test_all_workflows_parse_and_have_bounded_runtime(self):
        for name, value in WORKFLOWS.items():
            with self.subTest(name=name):
                self.assertIsInstance(value['on'], dict)
                self.assertIsInstance(value['jobs'], dict)
        for name in VALIDATION:
            for job in WORKFLOWS[name]['jobs'].values():
                self.assertGreater(job['timeout-minutes'], 0)
                self.assertLessEqual(job['timeout-minutes'], 40)


if __name__ == '__main__':
    unittest.main()
