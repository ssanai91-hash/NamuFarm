import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from unittest.mock import patch

from PySide6.QtWidgets import QDialog, QDialogButtonBox, QLineEdit
from namufarm_desktop.workspace import SellerWindow
from namufarm_desktop.nursery_ui import SpeciesPurposeEditor
from qt_support import QtTestCase
import test_nursery as nursery_tests


class NurseryUiTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.fixture = nursery_tests.NurseryTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.window = SellerWindow(self.fixture.vault.path)
        self.window.timer.stop()
        self.window.manager = self.fixture.manager
        self.window.refresh_nursery()

    def test_navigation_uses_local_cache_and_never_calls_server(self):
        self.fixture.api.offline = True
        self.fixture.parents()
        for index in (self.window.PAGE_FARMS, self.window.PAGE_SPECIES):
            self.window.navigate(index)
            self.assertEqual(1, self.window.stack.currentWidget().table.rowCount())
        self.assertEqual([], self.fixture.api.calls)
        self.assertEqual(['농원관리', '묘목관리', '연결 · 설정'], [b.text() for b in self.window.nav[-3:]])

    def test_add_dialog_commits_to_pc_and_updates_both_pages(self):
        def save(dialog):
            dialog.findChildren(QLineEdit)[0].setText('새 농원')
            dialog.findChild(QDialogButtonBox).button(QDialogButtonBox.StandardButton.Save).click()
            return dialog.result()
        with patch.object(QDialog, 'exec', save):
            self.window.farms_page.edit()
        self.assertEqual('새 농원', self.fixture.store.read()['farms'][0]['farmName'])
        self.assertEqual('새 농원', self.window.species_page.farm_picker.itemText(1))
        self.assertEqual([], self.fixture.api.calls)

    def test_stock_add_requires_explicit_farm_and_species_and_initial_resets_selection(self):
        self.fixture.parents()
        self.window.refresh_nursery()
        page = self.window.species_page
        self.assertFalse(page.stock_add.isEnabled())
        page.farm_picker.setCurrentIndex(1)
        self.assertFalse(page.stock_add.isEnabled())
        page.species_picker.setCurrentIndex(1)
        self.assertTrue(page.stock_add.isEnabled())
        page.table.selectRow(0)
        page.choose_initial('ㄷ')
        self.assertEqual(0, page.table.rowCount())
        self.assertFalse(page.edit_button.isEnabled())

    def test_f5_routes_to_management_and_auto_sync_does_not_fetch_customer_data(self):
        self.window.navigate(self.window.PAGE_FARMS)
        with patch.object(self.window.farms_page, 'sync') as sync:
            self.window.manual_sync()
            sync.assert_called_once_with()
        self.window.auto_sync()
        self.assertEqual([], self.fixture.api.calls)

    def test_initial_labels_fit_small_large_font_window(self):
        self.window.large_font_check.setChecked(True)
        self.window.resize(1000, 620)
        self.window.navigate(self.window.PAGE_SPECIES)
        self.window.show()
        for _ in range(8):
            self.app.processEvents()
        page = self.window.species_page
        self.assertEqual(8, page.initial_columns)
        for title, button in page.initial_buttons.items():
            self.assertGreaterEqual(button.width(), button.fontMetrics().horizontalAdvance(title) + 20)

    def test_canceling_suggested_category_does_not_write_rows_or_pending(self):
        self.fixture.parents()
        self.window.refresh_nursery()
        before = self.fixture.store.read()
        row = before['species'][0]
        def cancel(dialog):
            editor = dialog.findChild(SpeciesPurposeEditor)
            self.assertEqual('꽃', editor.value())
            self.assertEqual(before, self.fixture.store.read())
            dialog.reject()
            return dialog.result()
        with patch.object(QDialog, 'exec', cancel):
            self.window.species_page.edit(row)
        self.assertEqual(before, self.fixture.store.read())
        self.assertEqual([], self.fixture.api.calls)

    def test_category_save_retains_farm_memo_active_and_legacy_value(self):
        key = self.fixture.store.save('species', {'speciesName': '능소화', 'category': '생울타리',
            'farmName': '기존 농원사용명', 'memo': '보존할 메모', 'isActive': 1})
        self.window.refresh_nursery()
        row = next(r for r in self.fixture.store.read()['species'] if r['_key'] == key)
        def save(dialog):
            dialog.findChild(SpeciesPurposeEditor).buttons['꽃'].click()
            dialog.findChildren(QLineEdit)[0].setText('능소화나무')
            dialog.findChild(QDialogButtonBox).button(QDialogButtonBox.StandardButton.Save).click()
            return dialog.result()
        with patch.object(QDialog, 'exec', save):
            self.window.species_page.edit(row)
        saved = next(r for r in self.fixture.store.read()['species'] if r['_key'] == key)
        self.assertEqual(('능소화나무', '꽃, 생울타리', '기존 농원사용명', '보존할 메모', 1),
                         tuple(saved[k] for k in ('speciesName', 'category', 'farmName', 'memo', 'isActive')))
        self.assertEqual([], self.fixture.api.calls)

    def test_overlong_category_disables_save_and_legacy_clear_reenables_it(self):
        key = self.fixture.store.save('species', {'speciesName': '능소화', 'category': '가' * 100})
        self.window.refresh_nursery()
        row = next(r for r in self.fixture.store.read()['species'] if r['_key'] == key)
        def inspect(dialog):
            editor = dialog.findChild(SpeciesPurposeEditor)
            save = dialog.findChild(QDialogButtonBox).button(QDialogButtonBox.StandardButton.Save)
            editor.buttons['꽃'].click()
            self.assertFalse(save.isEnabled())
            editor.clear_legacy.click()
            self.assertTrue(save.isEnabled())
            dialog.reject()
            return dialog.result()
        with patch.object(QDialog, 'exec', inspect):
            self.window.species_page.edit(row)
