"""0.5.6 removes the ornamental tree/clock while preserving navigation indices."""
import tempfile
from pathlib import Path
from namufarm_desktop.workspace import SellerWindow
from qt_support import QtTestCase

class SidebarLayoutTest(QtTestCase):
    def test_sidebar_is_compact_and_keeps_all_page_indices(self):
        with tempfile.TemporaryDirectory() as folder:
            window = SellerWindow(Path(folder) / 'unused.db')
            window.timer.stop()
            self.assertEqual(232, window.sidebar.width())
            self.assertEqual('나무팜 판매관리', window.brand_label.text())
            self.assertEqual(len(window.PAGES), len(window.nav))
            self.assertFalse(hasattr(window, 'sidebar_clock_timer'))
            self.assertFalse(hasattr(window, 'sidebar_tree'))
            self.assertTrue(hasattr(window, 'request_nav_badge'))
            self.assertTrue(hasattr(window, 'sidebar_connection'))
            self.assertFalse(window.vault_path.exists())
            window.close()
