"""Logical laptop geometry and high-DPI asset regression checks; synthetic data only."""
from pathlib import Path
import tempfile

from PySide6.QtCore import QSize
from PySide6.QtWidgets import QApplication, QBoxLayout
from namufarm_desktop.workspace import SellerWindow, QuoteDialog, QuoteDeliveryDialog
from namufarm_desktop.demo import demo_data, show_demo
from namufarm_desktop.ui_icons import ui_icon
from namufarm_desktop.ui_theme import workspace_style
from qt_support import QtTestCase


class LaptopLayoutTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.app.setStyle('Fusion')
        self.app.setProperty('namufarmLargeFont', False)
        self.app.setProperty('namufarmCompactLayout', False)
        self.app.setStyleSheet(workspace_style())
        self.addCleanup(self.reset_theme)

    def reset_theme(self):
        self.app.setProperty('namufarmLargeFont', False)
        self.app.setProperty('namufarmCompactLayout', False)
        self.app.setStyleSheet(workspace_style())

    def settle(self):
        for _ in range(10):
            self.app.processEvents()

    def window(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        window = SellerWindow(Path(tmp.name) / 'demo.db')
        show_demo(window)
        window.apply_design_scale(False)
        window.resize(1280, 740)
        window.show()
        self.settle()
        return window

    def test_laptop_lists_and_actions_fit_without_removing_business_columns(self):
        window = self.window()
        for page, name, columns in ((0, 'request_table', (3, 4, 8, 1, 7, 5)),
                                    (1, 'draft_table', (3, 1, 7, 8, 9)),
                                    (2, 'sale_table', (3, 4, 8, 9, 1, 2))):
            window.navigate(page)
            self.settle()
            table = getattr(window, name)
            self.assertEqual(0, table.horizontalScrollBar().maximum(), name)
            self.assertTrue(all(not table.isColumnHidden(c) for c in columns), name)
            self.assertGreater(table.viewport().height(), 350)
        self.assertTrue(self.app.property('namufarmCompactLayout'))
        self.assertLess(window.sidebar.width(), 250)
        for action in (window.sales_upload_button, window.sales_download_button,
                       window.sales_export_button, window.sales_import_button,
                       window.sale_delete_button, window.customer_open_button):
            self.assertTrue(action.isVisible())
            top = action.mapTo(window, action.rect().topLeft())
            bottom = action.mapTo(window, action.rect().bottomRight())
            self.assertTrue(window.rect().contains(top), action.text())
            self.assertTrue(window.rect().contains(bottom), action.text())

    def test_resize_restores_wide_style_and_preserves_large_font_preference(self):
        window = self.window()
        original = window.data
        window.apply_design_scale(True)
        self.assertTrue(self.app.property('namufarmLargeFont'))
        window.navigate(2)
        self.settle()
        self.assertEqual(0, window.sale_table.horizontalScrollBar().maximum())
        window.resize(1180, 740)
        self.settle()
        self.assertEqual(QBoxLayout.Direction.TopToBottom, window.sales_toolbar.direction())
        window.resize(1880, 1000)
        self.settle()
        self.assertFalse(self.app.property('namufarmCompactLayout'))
        self.assertTrue(self.app.property('namufarmLargeFont'))
        self.assertEqual(286, window.sidebar.width())
        self.assertEqual(QBoxLayout.Direction.LeftToRight, window.sales_toolbar.direction())
        self.assertIs(original, window.data)
        window.apply_design_scale(False)

    def test_quote_editors_keep_prices_specifications_and_actions_accessible(self):
        draft = demo_data()['confirmedQuotes'][0]
        draft['items'] *= 8
        for cls in (QuoteDialog, QuoteDeliveryDialog):
            dialog = cls(draft, request_status='신규') if cls is QuoteDialog else cls(draft, '견적확정')
            dialog.resize(1240, 740)
            dialog.show()
            self.settle()
            self.assertTrue(dialog.property('compactLayout'))
            self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
            self.assertEqual(0, dialog.workbench_scroll.verticalScrollBar().maximum())
            self.assertLessEqual(dialog.items.rowHeight(0), 42)
            self.assertEqual(draft['items'][0]['species'], dialog.collect()['items'][0]['species'])
            if cls is QuoteDialog:
                editor = dialog.items.cellWidget(0, 3)
                for control in (editor.minus, editor.input, editor.plus):
                    self.assertTrue(editor.rect().contains(control.geometry()))
                self.assertTrue(dialog.save_working_button.isVisible())
                self.assertTrue(dialog.confirm_button.isVisible())
            dialog.hide()

    def test_icon_buffers_use_requested_dpi_without_clipping_tree_or_line_icons(self):
        for name in ('requests', 'park', 'namufarm_tree'):
            for ratio in (1.0, 1.5, 2.0):
                pixmap = ui_icon(name).pixmap(QSize(24, 24), ratio)
                self.assertEqual(QSize(round(24 * ratio), round(24 * ratio)), pixmap.size())
                self.assertAlmostEqual(ratio, pixmap.devicePixelRatio())
                image = pixmap.toImage()
                painted = [(x, y) for y in range(image.height()) for x in range(image.width())
                           if image.pixelColor(x, y).alpha() > 128]
                self.assertTrue(painted)
                # All source paths leave clear space at the right/bottom edges.
                self.assertLess(max(x for x, _ in painted), image.width() - 1)
                self.assertLess(max(y for _, y in painted), image.height() - 1)


class UnifiedChromeTest(QtTestCase):
    def test_header_status_and_error_objects_preserve_existing_controls(self):
        with tempfile.TemporaryDirectory() as folder:
            window = SellerWindow(Path(folder) / 'unused.db')
            window.timer.stop()
            self.assertEqual(68, window.page_header.height())
            self.assertEqual(34, window.server_log_frame.height())
            self.assertTrue(window.page_summary.isHidden())
            self.assertIsNotNone(window.sync_button)
            window.message.setText('합성 오류 안내')
            window.set_status_state('error', '실패')
            self.assertFalse(window.error_banner.isHidden())
            self.assertIn('합성 오류', window.error_text.text())
            window.set_status_state('success', '완료')
            self.assertTrue(window.error_banner.isHidden())
            window.close()
