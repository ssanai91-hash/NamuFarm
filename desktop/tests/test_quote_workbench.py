"""Quotation workbench layout and price-entry checks with synthetic data only."""
import copy
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from unittest.mock import Mock, patch

from PySide6.QtCore import QPoint, Qt
from PySide6.QtWidgets import QDialog, QMessageBox, QStyleOptionViewItem
from namufarm_desktop.commerce import new_draft, new_manual_draft, quote_delivery_fingerprint, quote_document_html, quote_document
from namufarm_desktop.ui_theme import workspace_style
from namufarm_desktop.workspace import QuoteDialog
from qt_support import QtTestCase


class QuoteWorkbenchTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.app.setProperty('namufarmLargeFont', False)
        self.app.setStyleSheet(workspace_style())
        self.draft = new_draft()
        self.draft.update(customerName='가상 고객', customerPhone='01000000000',
                          customerEmail='sample@example.com', requestRegion='옥천군',
                          sourceRequestId='00000000-0000-4000-8000-000000000003',
                          requestReceiptNumber='NAMU-20261017-0003',
                          formalQuoteRequested=True, quoteRecipientName='가상 수신인',
                          quoteReplyChannel='both', note='다음 주 배송 부탁드립니다.',
                          items=[dict(species=n, specification='', quantity=q, unitPrice=p, unitCost=0)
                                 for n, q, p in [('헛개나무', 10, 2000), ('계수나무', 3, 2000), ('팽나무', 7, 4000)]])

    def settle(self):
        for _ in range(8):
            self.app.processEvents()

    def dialog(self, status='신규', size=(1280, 900), manager=None):
        dialog = QuoteDialog(self.draft, request_status=status, manager=manager)
        dialog.resize(*size)
        dialog.show()
        self.settle()
        return dialog

    def test_selected_layout_has_no_seedling_icons_and_a_separate_total_rule(self):
        dialog = self.dialog()
        header = dialog.items.horizontalHeader()
        self.assertEqual([4, 0, 1, 3, 2, 5], [header.logicalIndex(i) for i in range(6)])
        self.assertEqual('54,000원', dialog.total.text())
        self.assertEqual(0, dialog.items.verticalScrollBar().maximum())
        self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
        self.assertLessEqual(dialog.customer_scroll.width(), 330)
        self.assertGreater(dialog.workbench_scroll.width(), 2 * dialog.customer_scroll.width())
        rule_y = dialog.workbench_total_rule.mapTo(dialog, QPoint()).y()
        self.assertGreater(rule_y, dialog.items.mapTo(dialog, QPoint()).y() + dialog.items.height())
        self.assertLess(rule_y + dialog.workbench_total_rule.height(),
                        dialog.workbench_summary.mapTo(dialog, QPoint()).y())
        self.assertEqual(3, dialog.workbench_total_rule.height())
        self.assertEqual(3, dialog.items.rowCount())  # Reserve space, not fake business items.
        self.assertGreaterEqual(dialog.items.height(), dialog.items.horizontalHeader().height() + 4 * 40)
        for row in range(3):
            self.assertEqual(str(row + 1), dialog.items.item(row, 4).text())
            self.assertFalse(dialog.items.item(row, 4).flags() & Qt.ItemFlag.ItemIsEditable)
            self.assertTrue(dialog.items.item(row, 0).icon().isNull())
            editor = dialog.unit_price_editor(row)
            self.assertEqual(editor.minus.height(), editor.input.height())
            self.assertEqual(editor.plus.height(), editor.input.height())

    def test_price_changes_keep_customer_metadata_and_original_draft(self):
        original = copy.deepcopy(self.draft)
        dialog = self.dialog()
        editor = dialog.unit_price_editor(0)
        editor.plus.click()
        self.assertEqual('64,000원', dialog.total.text())
        editor.input.setText('1250')
        self.assertEqual('46,500원', dialog.total.text())
        result = dialog.collect()
        self.assertEqual(1250, result['items'][0]['unitPrice'])
        self.assertEqual(original, self.draft)
        for key in ('customerName', 'customerPhone', 'customerEmail', 'quoteRecipientName', 'quoteReplyChannel', 'note'):
            self.assertEqual(original[key], result[key])
        self.assertFalse(dialog.note.isReadOnly())
        self.assertEqual('가상 수신인', dialog.customer_values['견적서 수신인'].text())
        self.assertEqual('문자 + 이메일', dialog.customer_values['수신 방법'].text())
        self.assertEqual(6000, result['shippingFee'])
        self.assertFalse(result['isPrepaid'])

    def test_customer_header_icons_alignment_and_bold_receipt_match_selected_design(self):
        dialog = self.dialog()
        heading = dialog.workbench_customer_heading
        banner = dialog.workbench_formal_banner
        caption = dialog.customer_captions['공식견적서']
        self.assertEqual(caption.geometry().center().y(), banner.geometry().center().y())
        self.assertGreater(banner.y(), heading.geometry().bottom())
        self.assertGreater(banner.x(), caption.geometry().right())
        self.assertEqual(banner.x(), dialog.customer_values['견적번호'].x())
        self.assertLessEqual(banner.geometry().right(), dialog.customer_scroll.viewport().width())
        value_columns = {value.x() for value in dialog.customer_values.values()}
        self.assertEqual(1, len(value_columns))
        self.assertTrue(dialog.customer_values['견적번호'].font().bold())
        self.assertTrue(dialog.findChild(type(dialog.customer_captions['고객명']), 'workbenchReference').font().bold())
        names = [icon.property('iconName') for icon in dialog.workbench_icons]
        self.assertIn('person', names)
        self.assertIn('leaf', names)
        self.assertIn('calculator', names)
        self.assertIn('document', names)
        self.assertIn('shipping', names)
        self.assertTrue(all(not icon.pixmap().isNull() for icon in dialog.workbench_icons))
        self.assertTrue(dialog.workbench_document_area.isAncestorOf(dialog.generate_quote_button))
        self.assertTrue(dialog.workbench_document_area.isAncestorOf(dialog.quote_file_label))
        self.assertLess(dialog.generate_quote_button.mapTo(dialog, QPoint()).x(), dialog.customer_scroll.width())
        self.assertEqual('견적완료', dialog.confirm_button.text())

    def test_long_customer_values_wrap_without_changing_their_copyable_text(self):
        self.app.setProperty('namufarmLargeFont', True)
        self.app.setStyleSheet(workspace_style(True))
        dialog = self.dialog(size=(1100, 680))
        for key in ('이메일', '견적번호'):
            value = dialog.customer_values[key]
            self.assertGreater(value.height(), value.fontMetrics().height())
            self.assertEqual(self.draft['customerEmail' if key == '이메일' else 'requestReceiptNumber'], value.text())
            cursor = value.textCursor()
            cursor.select(cursor.SelectionType.Document)
            self.assertEqual(value.text(), cursor.selectedText())
            self.assertLessEqual(value.geometry().right(), dialog.customer_scroll.viewport().width())

    def test_small_large_font_window_keeps_actions_and_price_controls_visible(self):
        self.app.setProperty('namufarmLargeFont', True)
        self.app.setStyleSheet(workspace_style(True))
        self.draft['items'] *= 7
        dialog = self.dialog(size=(1000, 620))
        self.assertGreater(dialog.items.verticalScrollBar().maximum(), 0)
        self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
        dialog.workbench_scroll.ensureWidgetVisible(dialog.confirm_button)
        self.settle()
        for action in (dialog.close_button, dialog.confirm_button):
            self.assertGreaterEqual(action.height(), 44)
            self.assertLess(action.mapTo(dialog, QPoint()).y() + action.height(), dialog.height())
        editor = dialog.unit_price_editor(0)
        self.assertLessEqual(editor.plus.geometry().right(), dialog.items.columnWidth(3))
        self.assertGreaterEqual(editor.input.width(), 100)

    def test_finalize_still_requires_confirmation_and_existing_status_gate(self):
        dialog = self.dialog()
        dialog.delivery_state = {'emailSentAt': 1, 'emailQuoteFingerprint': quote_delivery_fingerprint(dialog.collect())}
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.No):
            dialog.confirm_button.click()
        self.assertEqual('', dialog.mode)
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.Yes):
            dialog.confirm_button.click()
        self.assertEqual('finalize', dialog.mode)
        self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())
        completed = self.dialog(status='견적확정')
        self.assertFalse(completed.confirm_button.isEnabled())

    def test_manual_quote_keeps_its_existing_editor_and_calculation(self):
        self.draft['sourceRequestId'] = None
        dialog = self.dialog()
        self.assertEqual('quoteEditor', dialog.objectName())
        self.assertEqual(7, dialog.items.columnCount())
        self.assertEqual('합계 54,000원', dialog.total.text())
        dialog.items.item(0, 2).setText('11')
        self.assertEqual('합계 56,000원', dialog.total.text())

    def test_integrated_shipping_pdf_and_price_edits_use_one_current_quote(self):
        manager = Mock()
        manager.quote_delivery_state.return_value = {}
        dialog = self.dialog(size=(1280, 988), manager=manager)
        self.assertEqual(0, dialog.workbench_scroll.verticalScrollBar().maximum())
        dialog.shipping_prepaid.setChecked(True)
        self.assertEqual('60,000원', dialog.total.text())
        dialog.generate_quote_button.click()
        path = dialog.generated_quote_path
        self.addCleanup(dialog.cleanup_generated_quote)
        self.assertTrue(path.is_file())
        self.assertTrue(path.read_bytes().startswith(b'%PDF'))
        self.assertEqual(path.name, dialog.quote_file_label.text())
        self.assertEqual(6000, dialog.draft['shippingFee'])
        self.assertTrue(dialog.draft['isPrepaid'])
        manager.confirm_quote.assert_not_called()
        manager.save_draft.assert_not_called()
        dialog.set_unit_price(0, 2500)
        self.assertEqual('65,000원', dialog.total.text())
        self.assertFalse(path.exists())
        self.assertFalse(dialog.preview_quote_button.isEnabled())
        dialog.generate_quote_button.click()
        path = dialog.generated_quote_path
        dialog.shipping_fee.setText('7000')
        self.assertFalse(path.exists())
        self.assertEqual('66,000원', dialog.total.text())
        dialog.shipping_cod.setChecked(True)
        self.assertEqual('59,000원', dialog.total.text())

    def test_integrated_channel_dialogs_receive_current_inputs_and_pdf_attachment(self):
        manager = Mock()
        manager.quote_delivery_state.return_value = {}
        dialog = self.dialog(manager=manager)
        self.addCleanup(dialog.cleanup_generated_quote)
        dialog.shipping_prepaid.setChecked(True)
        dialog.shipping_fee.setText('7000')
        sms = Mock(delivery_state={'smsState': 'pending'})
        sms.exec.return_value = QDialog.DialogCode.Rejected
        with patch('namufarm_desktop.workspace.QuoteSmsDialog', return_value=sms) as factory:
            dialog.sms_button.click()
        sent = factory.call_args.args[0]
        self.assertEqual(7000, sent['shippingFee'])
        self.assertTrue(sent['isPrepaid'])
        self.assertEqual('결과확인', dialog.sms_status_badge.text())
        dialog.generate_quote_button.click()
        attachment = dialog.generated_quote_path
        mail = Mock(delivery_state={'emailSentAt': 1, 'emailQuoteFingerprint': quote_delivery_fingerprint(dialog.collect())})
        mail.exec.return_value = QDialog.DialogCode.Accepted
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.Yes), \
             patch('namufarm_desktop.workspace.QuoteEmailDialog', return_value=mail) as factory:
            dialog.email_button.click()
        self.assertEqual(attachment, factory.call_args.kwargs['attachment_path'])
        self.assertEqual(7000, factory.call_args.args[0]['shippingFee'])
        self.assertEqual('', dialog.mode)
        manager.confirm_quote.assert_not_called()
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.Yes):
            dialog.confirm_button.click()
        self.assertEqual('finalize', dialog.mode)
        self.assertEqual(7000, dialog.draft['shippingFee'])
        self.assertTrue(dialog.draft['isPrepaid'])
        self.assertFalse(attachment.exists())

    def test_completion_blocks_unsent_pending_failed_and_old_delivery(self):
        dialog = self.dialog()
        fingerprint = quote_delivery_fingerprint(dialog.collect())
        for state in ({}, {'smsState': 'pending', 'smsMessageId': 'fixture', 'smsQuoteFingerprint': fingerprint},
                      {'smsState': 'failed', 'smsQuoteFingerprint': fingerprint},
                      {'smsState': 'success'}, {'emailSentAt': 1},
                      {'emailSentAt': 1, 'emailQuoteFingerprint': 'older-quote'}):
            with self.subTest(state=state):
                dialog.delivery_state = state
                with patch('namufarm_desktop.workspace.QMessageBox.warning') as warning, \
                     patch('namufarm_desktop.workspace.QMessageBox.question') as question:
                    dialog.confirm_button.click()
                warning.assert_called_once()
                self.assertIn('먼저 보내', warning.call_args.args[2])
                question.assert_not_called()
                self.assertEqual('', dialog.mode)
                self.assertTrue(dialog.isVisible())

    def test_either_successful_channel_allows_completion_but_edits_require_resending(self):
        for channel in ('sms', 'email'):
            with self.subTest(channel=channel):
                dialog = self.dialog()
                fingerprint = quote_delivery_fingerprint(dialog.collect())
                state = {channel + 'QuoteFingerprint': fingerprint}
                state.update({'smsState': 'success'} if channel == 'sms' else {'emailSentAt': 1})
                dialog.delivery_state = state
                dialog.update_delivery_badges()
                badge = dialog.sms_status_badge if channel == 'sms' else dialog.email_status_badge
                self.assertEqual('전송완료', badge.text())
                dialog.set_unit_price(0, 2500)
                self.assertEqual('재전송', badge.text())
                with patch('namufarm_desktop.workspace.QMessageBox.warning') as warning:
                    dialog.confirm_button.click()
                warning.assert_called_once()
                dialog.shipping_prepaid.setChecked(True)
                dialog.shipping_fee.setText('7000')
                state[channel + 'QuoteFingerprint'] = quote_delivery_fingerprint(dialog.collect())
                dialog.update_delivery_badges()
                with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.Yes):
                    dialog.confirm_button.click()
                self.assertEqual('finalize', dialog.mode)
                self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())

    def test_inline_status_and_completion_share_a_bold_compact_row_with_close_only_in_footer(self):
        dialog = self.dialog(size=(1280, 988))
        summary_bottom = dialog.workbench_summary.mapTo(dialog, QPoint()).y() + dialog.workbench_summary.height()
        rule_y = dialog.workbench_delivery_rule.mapTo(dialog, QPoint()).y()
        self.assertGreater(rule_y, summary_bottom)
        self.assertGreater(dialog.sms_button.mapTo(dialog, QPoint()).y(), rule_y + 30)
        self.assertTrue(dialog.sms_button.isAncestorOf(dialog.sms_status_badge))
        self.assertTrue(dialog.email_button.isAncestorOf(dialog.email_status_badge))
        actions = (dialog.sms_button, dialog.email_button, dialog.confirm_button)
        self.assertEqual(1, len({a.mapTo(dialog, QPoint()).y() for a in actions}))
        self.assertEqual(1, len({a.height() for a in actions}))
        self.assertGreaterEqual(dialog.sms_button.height(), 56)
        self.assertLessEqual(dialog.sms_button.height(), 60)
        for action in (dialog.sms_button, dialog.email_button):
            self.assertTrue(action.title_label.font().bold())
            self.assertGreaterEqual(action.title_label.font().pixelSize(), 20)
        self.assertLess(dialog.sms_button.x(), dialog.email_button.x())
        self.assertLess(dialog.email_button.x(), dialog.confirm_button.x())
        self.assertFalse(dialog.workbench_footer.isAncestorOf(dialog.confirm_button))
        self.assertTrue(dialog.workbench_footer.isAncestorOf(dialog.close_button))
        self.assertEqual(0, dialog.workbench_scroll.horizontalScrollBar().maximum())
        self.assertEqual(0, dialog.workbench_scroll.verticalScrollBar().maximum())

    def test_request_and_manual_quotes_show_eight_rows_with_original_text_on_a_desktop(self):
        for manual in (False, True):
            with self.subTest(manual=manual):
                draft = new_manual_draft() if manual else copy.deepcopy(self.draft)
                draft['items'] = [dict(species='황금사철나무', specification='', quantity=2,
                                       unitPrice=2000, unitCost=0, remarks='') for _ in range(8)]
                dialog = QuoteDialog(draft, request_status='신규')
                dialog.resize(1460, 980)
                dialog.show()
                self.settle()
                self.assertTrue(dialog.workbench)
                self.assertEqual(8, dialog.items.rowCount())
                self.assertEqual(0, dialog.items.verticalScrollBar().maximum())
                self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
                self.assertEqual(0, dialog.workbench_scroll.verticalScrollBar().maximum())
                self.assertGreater(dialog.items.columnWidth(0), dialog.items.columnWidth(3))
                self.assertGreater(dialog.items.columnWidth(5), dialog.items.columnWidth(0))
                last = dialog.items.visualItemRect(dialog.items.item(7, 0))
                self.assertLess(last.bottom(), dialog.items.viewport().height())
                for widget in (dialog.note, dialog.generate_quote_button, dialog.preview_quote_button):
                    self.assertTrue(dialog.workbench_document_area.isAncestorOf(widget))
                self.assertEqual(dialog.note.width(), dialog.quote_file_label.width())
                self.assertEqual(dialog.note.width(), dialog.note.height())
                self.assertLess(dialog.workbench_original_area.mapTo(dialog, QPoint()).y()
                                + dialog.workbench_original_area.height(), dialog.close_button.mapTo(dialog, QPoint()).y())
                dialog.add_item({'species': '추가 묘목', 'specification': '', 'quantity': 1,
                                 'unitPrice': 1000, 'unitCost': 0})
                self.settle()
                self.assertEqual(9, dialog.items.rowCount())
                self.assertGreater(dialog.items.verticalScrollBar().maximum(), 0)
                self.assertEqual(0, dialog.workbench_scroll.verticalScrollBar().maximum())

    def test_square_note_and_separate_price_controls_survive_resizing_and_focus(self):
        for large in (False, True):
            self.app.setProperty('namufarmLargeFont', large)
            self.app.setStyleSheet(workspace_style(large))
            dialog = self.dialog(size=(1460, 980))
            for size in ((1460, 980), (1000, 620), (1280, 900)):
                with self.subTest(large=large, size=size):
                    dialog.resize(*size)
                    self.settle()
                    self.assertEqual(dialog.note.width(), dialog.note.height())
                    self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
                    editor = dialog.unit_price_editor(0)
                    for focus in (editor.input, editor.plus, editor.minus):
                        focus.setFocus()
                        self.settle()
                        self.assertGreaterEqual(editor.input.x() - editor.minus.geometry().right() - 1, 6)
                        self.assertGreaterEqual(editor.plus.x() - editor.input.geometry().right() - 1, 6)
                        self.assertLess(editor.plus.geometry().right(), editor.width())
                        self.assertEqual(editor.minus.height(), editor.input.height())
                        self.assertEqual(editor.plus.height(), editor.input.height())
                    dialog.customer_scroll.ensureWidgetVisible(dialog.note)
                    dialog.workbench_scroll.ensureWidgetVisible(dialog.request_note_view)
                    self.settle()
                    self.assertGreaterEqual(dialog.note.mapTo(dialog.customer_scroll.viewport(), QPoint()).y(), 0)
                    self.assertGreaterEqual(dialog.request_note_view.mapTo(dialog.workbench_scroll.viewport(), QPoint()).y(), 0)

    def test_original_request_is_inline_below_sms_and_never_changes_with_seller_notes(self):
        original = '고객 요청 원문입니다.\n둘째 줄과 <문자>도 그대로 표시합니다.'
        self.draft['requestNote'] = original
        dialog = self.dialog(size=(1460, 980))
        self.assertTrue(dialog.request_note_view.isReadOnly())
        self.assertEqual('고객 원문보기', dialog.request_note_view.accessibleName())
        self.assertEqual(original, dialog.request_note_view.toPlainText())
        self.assertGreater(dialog.workbench_original_area.mapTo(dialog, QPoint()).y(),
                           dialog.sms_button.mapTo(dialog, QPoint()).y() + dialog.sms_button.height())
        self.assertFalse(dialog.workbench_document_area.isAncestorOf(dialog.request_note_view))
        dialog.note.setPlainText('판매자가 작성한 견적 조건')
        dialog.set_unit_price(0, 5000)
        self.assertEqual(original, dialog.request_note_view.toPlainText())
        result = dialog.collect()
        self.assertEqual(original, result['requestNote'])
        self.assertEqual('판매자가 작성한 견적 조건', result['note'])
        manual = QuoteDialog(new_manual_draft())
        manual.show()
        self.settle()
        self.assertEqual('', manual.request_note_view.toPlainText())
        self.assertEqual('고객 요청 원문이 없습니다.', manual.request_note_view.placeholderText())
        self.assertLessEqual(manual.total.font().pixelSize(), 18)
        manual.items.item(0, 0).setText('소나무')
        self.assertEqual('0원', manual.total.text())
        self.assertGreaterEqual(manual.total.font().pixelSize(), 32)

    def test_square_note_does_not_toggle_the_sidebar_scrollbar_at_its_height_boundary(self):
        dialog = QuoteDialog(new_manual_draft())
        dialog.show()
        for height in (960, 970, 975, 980, 985, 1000, 975):
            with self.subTest(height=height):
                dialog.resize(1460, height)
                samples = []
                for _ in range(30):
                    self.app.processEvents()
                    samples.append((dialog.note.width(), dialog.note.height(),
                                    dialog.customer_scroll.verticalScrollBar().maximum()))
                self.assertEqual(1, len(set(samples[-10:])))
                self.assertEqual(dialog.note.width(), dialog.note.height())

    def test_empty_remarks_hint_is_italic_display_only_and_never_saved_or_exported(self):
        dialog = self.dialog()
        index = dialog.items.model().index(0, 5)
        delegate = dialog.items.itemDelegateForColumn(5)
        option = QStyleOptionViewItem()
        delegate.initStyleOption(option, index)
        self.assertEqual('입력가능', option.text)
        self.assertTrue(option.font.italic())
        self.assertEqual('', dialog.items.item(0, 5).text())
        self.assertEqual('', dialog.collect()['items'][0]['remarks'])
        self.assertNotIn('입력가능', quote_document_html(quote_document(dialog.collect())))
        editor = delegate.createEditor(dialog.items, option, index)
        delegate.setEditorData(editor, index)
        self.assertEqual('', editor.text())
        self.assertFalse(editor.font().italic())
        editor.setText('3년생 / 키 80cm')
        delegate.setModelData(editor, dialog.items.model(), index)
        actual = QStyleOptionViewItem()
        delegate.initStyleOption(actual, index)
        self.assertEqual('3년생 / 키 80cm', actual.text)
        self.assertFalse(actual.font.italic())
        self.assertEqual('3년생 / 키 80cm', dialog.collect()['items'][0]['remarks'])

