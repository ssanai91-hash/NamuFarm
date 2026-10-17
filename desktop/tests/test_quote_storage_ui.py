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

    def test_summary_count_follows_quote_filter_and_sales_search(self):
        data = demo_data()
        second = copy.deepcopy(data['confirmedQuotes'][0])
        second['id'] = '00000000-0000-4000-8000-000000000099'
        second['quoteStatus'] = '견적발송완료'
        data['confirmedQuotes'].append(second)
        self.window.apply_view(data)
        self.window.navigate(self.window.PAGE_QUOTES)
        self.assertEqual('견적확정', self.window.draft_filter)
        self.assertEqual('1', self.window.page_count_number.text())
        self.window.set_draft_filter('전체')
        self.assertEqual('2', self.window.page_count_number.text())
        self.window.set_draft_filter('견적발송완료')
        self.assertEqual('1', self.window.page_count_number.text())
        self.assertEqual(1, len(self.window.draft_rows))
        self.window.navigate(self.window.PAGE_SALES)
        self.window.sale_search.setText('does-not-exist')
        self.assertEqual('0', self.window.page_count_number.text())
        self.assertEqual([], self.window.sale_rows)
        self.window.sale_search.clear()
        self.assertEqual(str(len(data['sales'])), self.window.page_count_number.text())

    def test_summary_navigation_preserves_other_page_headings(self):
        self.window.apply_view(demo_data())
        self.window.show()
        for index in (self.window.PAGE_REQUESTS, self.window.PAGE_QUOTES, self.window.PAGE_SALES):
            self.window.navigate(index)
            self.assertTrue(self.window.page_summary.isVisible())
            self.assertFalse(self.window.other_page_title.isVisible())
            self.assertEqual(self.window.PAGES[index][0], self.window.page_title.text())
        for index in (self.window.PAGE_SHIPPING, self.window.PAGE_REPORTS, self.window.PAGE_SETTINGS):
            self.window.navigate(index)
            self.assertFalse(self.window.page_summary.isVisible())
            self.assertTrue(self.window.other_page_title.isVisible())
            self.assertTrue(self.window.other_page_title.text().startswith(self.window.PAGES[index][0]))
