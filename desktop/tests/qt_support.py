"""Qt fixture teardown for tests that pump events without QApplication.exec()."""
import unittest

from PySide6.QtCore import QCoreApplication, QEvent, QThread
from PySide6.QtWidgets import QApplication


class QtTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        super().setUp()
        # unittest runs cleanups even when an assertion or tearDown fails.
        self.addCleanup(self.dispose_qt_objects)

    def dispose_qt_objects(self):
        for window in QApplication.topLevelWidgets():
            for thread in window.findChildren(QThread):
                self.assertTrue(thread.wait(10000), "Qt worker did not finish before window teardown")
        self.app.processEvents()  # Deliver completed worker results before disposing their receivers.
        for window in QApplication.topLevelWidgets():
            window.deleteLater()
        # processEvents() alone does not deliver DeferredDelete without an event loop.
        # This includes orphaned old roots from SellerWindow's layout replacement.
        QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        self.assertFalse(QApplication.topLevelWidgets(), "Qt windows leaked into the next test")
