import copy
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

from namufarm_desktop.demo import demo_data
from namufarm_desktop.workspace import SellerWindow
from qt_support import QtTestCase


class QuoteStorageUiTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.window = SellerWindow(Path(self.tmp.name) / 'never-open.db')
        self.window.timer.stop()

    def test_quotes_use_local_excel_rows_even_without_source_request(self):
        data = demo_data()
        data['requests'] = []
        data['drafts'] = []
        self.window.apply_view(data)
        self.assertEqual(1, self.window.draft_table.rowCount())
        self.assertEqual(data['confirmedQuotes'][0]['id'], self.window.draft_rows[0]['id'])
        self.assertEqual('견적확정', self.window.draft_statuses[0])
        self.assertEqual('로컬 엑셀', self.window.draft_table.item(0, 9).text())
        self.assertEqual(600000, self.window.timer.interval())
        self.assertFalse(self.window.vault_path.exists())

    def test_server_confirmed_rows_and_legacy_drafts_do_not_create_quotes(self):
        data = demo_data()
        data['requests'][0]['status'] = '견적확정'
        data['drafts'] = copy.deepcopy(data['confirmedQuotes'])
        data['confirmedQuotes'] = []
        self.window.apply_view(data)
        self.assertEqual([], self.window.draft_rows)
        self.assertEqual(0, self.window.draft_table.rowCount())

    def test_manual_and_automatic_refresh_keep_inbox_independent_of_sales(self):
        manager = SimpleNamespace(connect=Mock(), sync=Mock(), sync_requests=Mock())
        self.window.manager = manager
        self.window.data['paired'] = True
        self.window.auto_check.setChecked(True)
        self.window.run_job = lambda title, work, *args, **kwargs: work()
        for page, sales in ((self.window.PAGE_REQUESTS, False), (self.window.PAGE_QUOTES, False),
                            (self.window.PAGE_SALES, True), (self.window.PAGE_SHIPPING, True)):
            self.window.stack.setCurrentIndex(page)
            self.window.manual_sync()
            manager.connect.assert_called_with(include_sales=sales)
            self.window.auto_sync()
            (manager.sync if sales else manager.sync_requests).assert_called()
            manager.sync.reset_mock()
            manager.sync_requests.reset_mock()
