"""Clock rollover and navigation use temporary settings with no production vault."""
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import tempfile
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from PySide6.QtCore import QPoint
from PySide6.QtWidgets import QLabel
from namufarm_desktop.domain import SEOUL
from namufarm_desktop.workspace import SellerWindow
from qt_support import QtTestCase


class SidebarClockTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.window = SellerWindow(Path(self.tmp.name) / 'unused-vault.db')
        self.window.timer.stop()

    def test_clock_uses_korean_date_and_changes_day_at_midnight(self):
        for stamp, date, time in ((datetime(2026, 10, 16, 23, 59, 59, tzinfo=SEOUL), '2026.10.16 (금)', '23:59:59'),
                                  (datetime(2026, 10, 17, 0, 0, 0, tzinfo=SEOUL), '2026.10.17 (토)', '00:00:00')):
            with patch('namufarm_desktop.workspace.datetime') as clock:
                clock.now.return_value = stamp
                self.window.update_sidebar_clock()
                clock.now.assert_called_once_with(SEOUL)
            self.assertEqual(date, self.window.sidebar_date.text())
            self.assertEqual(time, self.window.sidebar_time.text())
        self.assertTrue(self.window.sidebar_clock_timer.isActive())
        self.assertEqual(1000, self.window.sidebar_clock_timer.interval())
        self.assertFalse(self.window.vault_path.exists())

    def test_wider_sidebar_keeps_eight_menus_and_footer_separate_at_minimum_height(self):
        self.window.large_font_check.setChecked(False)
        self.window.resize(1000, 620)
        self.window.show()
        self.app.processEvents()
        self.assertEqual(286, self.window.sidebar.width())
        self.assertEqual('나무팜(주)', self.window.brand_label.text())
        self.assertEqual(8, len(self.window.nav))
        self.assertFalse(self.window.sidebar.findChildren(QLabel, 'navGroup'))
        last = self.window.nav[-1]
        self.assertLess(last.mapTo(self.window.sidebar, QPoint()).y() + last.height(),
                        self.window.sidebar_clock.mapTo(self.window.sidebar, QPoint()).y())
        for i, button in enumerate(self.window.nav):
            button.click()
            self.assertEqual(i, self.window.stack.currentIndex())
            self.assertTrue(button.isChecked())

    def test_tree_and_clock_fit_large_and_small_windows(self):
        self.window.large_font_check.setChecked(False)
        self.window.resize(1600, 1000)
        self.window.show()
        for _ in range(8):
            self.app.processEvents()
        self.assertFalse(self.window.sidebar_tree.pixmap().isNull())
        self.assertGreaterEqual(self.window.sidebar_tree.pixmap().height(), 96)
        self.assertTrue(self.window.sidebar_clock_heading.isVisible())
        tree_bottom = self.window.sidebar_tree.mapTo(self.window.sidebar, QPoint()).y() + self.window.sidebar_tree.height()
        self.assertLess(tree_bottom, self.window.sidebar_clock.y())
        for large in (False, True):
            self.window.large_font_check.setChecked(large)
            self.window.resize(1000, 620)
            for _ in range(8):
                self.app.processEvents()
            self.assertFalse(self.window.sidebar_clock_heading.isVisible())
            self.assertLess(self.window.sidebar_clock.geometry().bottom(), self.window.sidebar.height())
            last = self.window.nav[-1]
            self.assertLess(last.mapTo(self.window.sidebar, QPoint()).y() + last.height(), self.window.sidebar_clock.y())
