"""Exit status matters: the original crash happened after unittest printed OK."""
import os
from pathlib import Path
import subprocess
import sys
import textwrap
import unittest


class QtLifecycleTest(unittest.TestCase):
    def run_child(self, source):
        tests = Path(__file__).resolve().parent
        env = dict(os.environ, QT_QPA_PLATFORM="offscreen", PYTHONFAULTHANDLER="1",
                   PYTHONPATH=os.pathsep.join([str(tests), str(tests.parent), os.environ.get("PYTHONPATH", "")]))
        result = subprocess.run([sys.executable, "-c", textwrap.dedent(source)],
                                cwd=tests.parent.parent, env=env, capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=45)
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertNotIn("shared QObject was deleted directly", output)
        self.assertNotIn("QThread: Destroyed while thread", output)

    def test_repeated_seller_screen_and_pdf_tests_exit_cleanly(self):
        self.run_child('''
            import unittest
            from test_workspace import SellerUiTest
            suite = unittest.TestSuite()
            for _ in range(3):
                suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(SellerUiTest))
            result = unittest.TextTestRunner(verbosity=1).run(suite)
            raise SystemExit(not result.wasSuccessful())
        ''')

    def test_assertion_failure_still_disposes_widgets(self):
        self.run_child('''
            import unittest
            from PySide6.QtWidgets import QApplication, QWidget
            from shiboken6 import isValid
            from qt_support import QtTestCase
            saved = []
            class FailingScreen(QtTestCase):
                def runTest(self):
                    widget = QWidget()
                    saved.append(widget)
                    widget.show()
                    self.fail("intentional fixture failure")
            FailingScreen.setUpClass()
            result = unittest.TestResult()
            FailingScreen().run(result)
            assert len(result.failures) == 1 and not result.errors
            assert not QApplication.topLevelWidgets()
            assert not isValid(saved[0])
        ''')

    def test_worker_finishes_before_its_window_is_disposed(self):
        self.run_child('''
            import unittest
            from shiboken6 import isValid
            from namufarm_desktop.app import MainWindow
            from qt_support import QtTestCase
            saved, received = [], []
            class WorkingScreen(QtTestCase):
                def runTest(self):
                    window = MainWindow()
                    window.run_job("test", lambda: "done", received.append, False)
                    saved.extend([window, window.task])
                    # Cleanup must wait for the worker and deliver its result before deletion.
            result = unittest.TextTestRunner(verbosity=1).run(
                unittest.defaultTestLoader.loadTestsFromTestCase(WorkingScreen))
            assert result.wasSuccessful()
            assert received == ["done"]
            assert all(not isValid(obj) for obj in saved)
        ''')
