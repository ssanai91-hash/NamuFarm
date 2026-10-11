import copy
import csv
import io
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PySide6.QtWidgets import QDialog, QLabel, QMessageBox, QTableWidgetItem
from PySide6.QtGui import QTextDocument
from PySide6.QtPrintSupport import QPrinter
from namufarm_desktop.commerce import (new_draft, draft_amounts, order_from_draft, quote_document,
    quote_html, official_quote_html, shipping_html, shipping_csv, parse_tracking_csv, tracking_fields, tracking_number, TRACKING_PREFIX)
from namufarm_desktop.domain import metadata, now_ms
from namufarm_desktop.errors import UserError, NetworkError
from namufarm_desktop.workspace import (SellerWindow, QuoteDialog, QuoteDeliveryDialog, QuoteEmailDialog,
    QuoteSmsDialog, CustomerOrderDialog, draft_quote_sort_key, render_official_quote_image, write_temporary_quote_pdf)
from namufarm_desktop.demo import show_demo
from support import Bridge, manager_at
from qt_support import QtTestCase


def draft_for_test():
    draft = new_draft()
    draft.update(customerName='테스트 고객', customerPhone='01012345678', receiverName='수령인', zipNo='01234',
                 roadAddress='서울 테스트로 1', detailAddress='100호', note='문 앞에 놓아주세요.',
                 shippingFee=3500, isPrepaid=True, taxAmount=2350,
                 items=[dict(species='소나무', specification='H1.0m', quantity=2, unitPrice=10000, unitCost=5000)])
    return draft


def row_for_test():
    order = order_from_draft(draft_for_test())
    order.update(status='입금확인', paidAt=order['purchasedAt'])
    return {'metadata': metadata(order), 'private': {'order': order}}


class CommerceTest(unittest.TestCase):
    def test_totals_and_android_manual_item_wire_compatibility(self):
        draft = draft_for_test()
        self.assertEqual(dict(quantity=2, supply=23500, tax=2350, total=25850, cost=10000), draft_amounts(draft))
        order, doc = order_from_draft(draft), quote_document(draft)
        self.assertIn('|수기견적|5000', order['itemSummary'])
        self.assertEqual(order['totalPrice'], doc['supplyAmount'])
        self.assertEqual(sum(x['quantity']*x['unitPrice'] for x in doc['lines']), doc['supplyAmount'])
        draft['isPrepaid'] = False
        self.assertEqual(20000, draft_amounts(draft)['supply'])

    def test_official_quote_matches_paper_form_and_stays_render_only(self):
        draft = draft_for_test()
        draft.update(requestReceiptNumber='NAMU-20261011-0003', customerName='오진웅',
                     customerPhone='01044460304', taxAmount=0, isPrepaid=False, shippingFee=6000)
        html = official_quote_html(draft)
        self.assertIn('견 적 서', html)
        self.assertIn('NO. NAMU-20261011-0003', html)
        self.assertIn('합계금액(공급가액+세액)', html)
        self.assertIn('품 명', html)
        self.assertIn('규격', html)
        self.assertIn('공급가액', html)
        self.assertIn('세 액', html)
        self.assertIn('중요사항', html)
        self.assertIn('나무팜농원', html)
        self.assertIn('213-94-24363', html)
        self.assertIn('F:732-0981', html)
        self.assertIn('농협 328-12-035671 오진웅', html)
        self.assertIn('width:760px', html)
        self.assertIn('택배비 후불', html)
        self.assertGreaterEqual(html.count('<tr>'), 22)

    def test_invalid_amount_overflow_and_pipe_are_rejected(self):
        for key, value in [('quantity', 0), ('quantity', True), ('unitPrice', -1), ('unitPrice', 2147483647), ('species', 'a|b')]:
            draft = draft_for_test()
            draft['items'][0][key] = value
            with self.assertRaises(UserError):
                order_from_draft(draft)

    def test_tracking_preserves_human_note_and_is_not_a_delivery_confirmation(self):
        row = row_for_test()
        order = row['private']['order']
        fields = tracking_fields(order, '012-3456-7890')
        self.assertEqual('문 앞에 놓아주세요.\n' + TRACKING_PREFIX + '01234567890', fields['note'])
        order.update(fields)
        self.assertEqual('01234567890', tracking_number(order))
        order.update(tracking_fields(order, '12345678901'))
        self.assertEqual(1, order['note'].count(TRACKING_PREFIX))
        self.assertEqual('입금확인', order['status'])
        order['status'] = '판매완료'
        with self.assertRaises(UserError):
            tracking_fields(order, '12345678901')

    def test_customer_note_cannot_crash_tracking_view_or_inject_print_html(self):
        row = row_for_test()
        order = row['private']['order']
        order['note'] = TRACKING_PREFIX + '<bad>'
        self.assertEqual('', tracking_number(order))
        draft = draft_for_test()
        draft['customerName'] = '<script>alert(1)</script>'
        self.assertNotIn('<script>', quote_html(draft))
        self.assertIn('&lt;script&gt;', quote_html(draft))

    def test_export_and_import_keep_leading_zero_and_block_formulas(self):
        row = row_for_test()
        order = row['private']['order']
        order['receiverName'] = '=HYPERLINK("evil")'
        exported = list(csv.DictReader(io.StringIO(shipping_csv([row]))))[0]
        self.assertEqual('01234', exported['우편번호'])
        self.assertEqual('01012345678', exported['수하인전화'])
        self.assertTrue(exported['수하인명'].startswith("'="))
        raw = f"주문번호,송장번호\r\n{order['orderNumber']},01234567890\r\n".encode('utf-8-sig')
        self.assertEqual({order['orderNumber']: '01234567890'}, parse_tracking_csv(raw, [row]))
        order['zipNo'] = ''
        with self.assertRaises(UserError):
            shipping_csv([row])

    def test_csv_duplicate_unknown_changed_and_malformed_tracking_rejected(self):
        row = row_for_test()
        number = row['metadata']['orderNumber']
        for text in [f'주문번호,송장번호\n{number},123\n', f'주문번호,송장번호\n{number},12345678901\n{number},12345678901\n', '주문번호,송장번호\nunknown,12345678901\n', '주문번호,주문번호,송장번호\nx,x,12345678901\n']:
            with self.assertRaises(UserError):
                parse_tracking_csv(text.encode(), [row])
        row['private']['order'].update(tracking_fields(row['private']['order'], '12345678901'))
        with self.assertRaises(UserError):
            parse_tracking_csv(f'주문번호,송장번호\n{number},01234567890\n'.encode(), [row])

    def test_print_requires_registered_number_and_identifies_output(self):
        row = row_for_test()
        with self.assertRaises(UserError):
            shipping_html([row])
        row['private']['order'].update(tracking_fields(row['private']['order'], '01234567890'))
        html = shipping_html([row])
        self.assertIn('01234567890', html)
        self.assertIn('출고 확인용', html)
        self.assertIn('공식 운송장', html)


class WorkspaceServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bridge = Bridge()
        self.manager = manager_at(Path(self.tmp.name)/'pc.db', self.bridge)

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def test_draft_encrypted_survives_sync_and_expires(self):
        draft = draft_for_test()
        self.manager.save_draft(draft)
        self.assertEqual(1, len(self.manager.sync()['drafts']))
        self.assertNotIn('테스트 고객'.encode(), self.manager.vault.path.read_bytes())
        expired = copy.deepcopy(draft)
        expired['createdAt'] = now_ms() - 31 * 86400000
        self.manager.vault.save_preference('drafts', [expired])
        self.assertEqual([], self.manager.view()['drafts'])
        self.assertEqual([], self.manager.vault.preference('drafts'))

    def test_quote_confirmation_updates_server_state_and_keeps_local_quote(self):
        self.bridge.send(control='submit')
        view = self.manager.sync()
        request = view['requests'][0]
        draft = draft_for_test()
        draft['sourceRequestId'] = request['requestId']
        confirmed = self.manager.confirm_quote(draft)
        self.assertEqual('견적확정', confirmed['requests'][0]['status'])
        self.assertEqual(draft['id'], confirmed['drafts'][0]['id'])
        self.assertNotIn('테스트 고객'.encode(), self.manager.vault.path.read_bytes())

    def test_quote_sent_updates_server_state_before_purchase(self):
        self.bridge.send(control='submit')
        view = self.manager.sync()
        draft = draft_for_test()
        draft['sourceRequestId'] = view['requests'][0]['requestId']
        self.manager.confirm_quote(draft)
        sent = self.manager.mark_quote_sent(draft)
        self.assertEqual('견적발송완료', sent['requests'][0]['status'])
        with self.assertRaises(UserError):
            # A fresh request that skipped the sent stage cannot become a sale.
            other = draft_for_test()
            other['sourceRequestId'] = self.bridge.send(control='submit_new')['requestId']
            self.manager.sync()
            self.manager.confirm_quote(other)
            self.manager.confirm_draft(other)

    def test_quote_email_sends_through_server_and_records_delivery_state(self):
        self.bridge.send(control='submit')
        view = self.manager.sync()
        draft = draft_for_test()
        draft['sourceRequestId'] = view['requests'][0]['requestId']
        draft['customerEmail'] = 'buyer@example.com'
        self.manager.confirm_quote(draft)
        profile = self.manager.email_profile()
        self.assertEqual('ssanai91@gmail.com', profile['senderEmail'])
        result = self.manager.send_quote_email(
            draft, '[나무팜] 견적 안내', '견적 안내입니다.',
            '<p>견적 안내입니다.</p><table><tr><td>소나무 2주</td></tr></table>'
        )
        self.assertTrue(result['delivery']['emailSentAt'] > 0)
        self.assertEqual('buyer@example.com', result['delivery']['emailTo'])
        self.assertEqual('ssanai91@gmail.com', result['delivery']['senderEmail'])
        self.assertEqual(result['delivery'], self.manager.quote_delivery_state(draft['id']))

    def test_quote_email_attaches_generated_official_pdf(self):
        self.bridge.send(control='submit')
        view = self.manager.sync()
        draft = draft_for_test()
        draft['sourceRequestId'] = view['requests'][0]['requestId']
        draft['customerEmail'] = 'buyer@example.com'
        self.manager.confirm_quote(draft)
        with tempfile.TemporaryDirectory() as directory:
            pdf = Path(directory) / 'NAMU-20261011-0001_공식견적서.pdf'
            pdf.write_bytes(b'%PDF-1.4\nNAMUFARM PDF TEST\n')
            result = self.manager.send_quote_email(
                draft, '묘목 견적 안내', '견적 안내입니다.',
                '<p>견적 안내입니다.</p>', attachment_path=pdf
            )
        self.assertEqual('NAMU-20261011-0001_공식견적서.pdf', result['attachmentName'])
        self.assertTrue(result['delivery']['emailSentAt'] > 0)

    def test_quote_sms_sends_through_server_and_records_delivery_state(self):
        self.bridge.send(control='configure_sms')
        self.bridge.send(control='submit')
        view = self.manager.sync()
        draft = draft_for_test()
        draft['sourceRequestId'] = view['requests'][0]['requestId']
        self.manager.confirm_quote(draft)
        profile = self.manager.sms_profile()
        self.assertTrue(profile['configured'])
        self.assertEqual('01044460304', profile['senderPhone'])
        result = self.manager.send_quote_sms(draft, '테스트 고객님 견적 안내입니다.')
        self.assertEqual(0, result['delivery']['smsSentAt'])
        self.assertEqual('pending', result['delivery']['smsState'])
        self.assertEqual('01012345678', result['delivery']['smsTo'])
        self.assertEqual('01044460304', result['delivery']['senderPhone'])
        self.assertEqual('M4V_TEST', result['delivery']['smsMessageId'])
        final = self.manager.refresh_quote_sms_status(draft['id'])
        self.assertEqual('success', final['smsState'])
        self.assertTrue(final['smsSentAt'] > 0)
        self.assertEqual('문자 전송에 성공했습니다.', final['smsStatusMessage'])
        self.assertEqual(result['delivery']['smsMessageId'], final['smsMessageId'])

    def test_quote_sms_failure_is_logged_without_marking_sent(self):
        self.bridge.send(control='configure_sms')
        self.bridge.send(control='sms_status_fail')
        self.bridge.send(control='submit')
        view = self.manager.sync()
        draft = draft_for_test()
        draft['sourceRequestId'] = view['requests'][0]['requestId']
        self.manager.confirm_quote(draft)
        self.manager.send_quote_sms(draft, '실패 상태 확인용 문자입니다.')
        final = self.manager.refresh_quote_sms_status(draft['id'])
        self.assertEqual('failed', final['smsState'])
        self.assertEqual(0, final['smsSentAt'])
        self.assertIn('전원이 꺼져', final['smsStatusMessage'])
        self.assertEqual('3043', final['smsStatusCode'])

    def test_shipping_export_preferences_are_encrypted_and_do_not_advance_sale(self):
        row = self.manager.confirm_draft(draft_for_test())['sales'][0]
        before = self.manager.view()['sales']
        sender = {'name': '출고 설정 테스트', 'address': '테스트 출고지', 'phone': '01012345678', 'mobile': ''}
        number = row['metadata']['orderNumber']
        self.manager.remember_shipping_export(sender, {number: {}})
        settings = self.manager.shipping_export_preferences()
        self.assertEqual(sender, settings['sender'])
        self.assertIn(number, settings['history'])
        self.assertEqual(before, self.manager.view()['sales'])
        self.assertNotIn(sender['name'].encode(), self.manager.vault.path.read_bytes())

    def test_request_to_sale_closes_request_and_preserves_document(self):
        self.bridge.send(control='submit')
        view = self.manager.sync()
        draft = draft_for_test()
        draft['sourceRequestId'] = view['requests'][0]['requestId']
        self.manager.save_draft(draft)
        confirmed = self.manager.confirm_quote(draft)
        self.assertEqual('견적확정', confirmed['requests'][0]['status'])
        sent = self.manager.mark_quote_sent(draft)
        self.assertEqual('견적발송완료', sent['requests'][0]['status'])
        view = self.manager.confirm_draft(draft)
        self.assertEqual([], view['requests'])
        self.assertEqual([], view['drafts'])
        self.assertEqual(1, len(view['sales']))
        self.assertIn('quoteDocumentJson', view['sales'][0]['private'])
        with self.assertRaises(UserError):
            self.manager.confirm_draft(draft)
        self.assertEqual(1, len(self.manager.view()['sales']))


    def test_lost_new_order_ack_reuses_event_and_never_duplicates(self):
        draft = draft_for_test()
        self.manager.api.lose_ack = True
        with self.assertRaises(NetworkError):
            self.manager.confirm_draft(draft)
        operation = self.manager.vault.pending()[0]
        self.assertEqual('save_sale', operation['action'])
        view = self.manager.sync()
        self.assertEqual(1, len(view['sales']))
        self.assertEqual(1, view['sales'][0]['metadata']['revision'])
        self.assertEqual([], view['pending'])

    def test_deleted_source_draft_is_purged(self):
        self.bridge.send(control='submit')
        request = self.manager.sync()['requests'][0]
        draft = draft_for_test()
        draft['sourceRequestId'] = request['requestId']
        self.manager.save_draft(draft)
        self.assertEqual([], self.manager.close_request(request['requestId'])['drafts'])
        with self.assertRaises(UserError):
            self.manager.confirm_draft(draft)

    def test_tracking_sync_roundtrip_retry_and_duplicate_guard(self):
        draft = draft_for_test()
        row = self.manager.confirm_draft(draft)['sales'][0]
        number = row['metadata']['orderNumber']
        view = self.manager.save_sale(number, 1, {}, '입금확인')
        self.manager.api.lose_ack = True
        with self.assertRaises(NetworkError):
            self.manager.register_tracking(number, 2, '01234567890')
        row = self.manager.sync()['sales'][0]
        self.assertEqual('01234567890', tracking_number(row['private']['order']))
        self.assertEqual('입금확인', row['metadata']['status'])
        self.assertEqual(3, row['metadata']['revision'])
        second = self.manager.confirm_draft(draft_for_test())['sales'][0]
        other = second['metadata']['orderNumber']
        self.manager.save_sale(other, 1, {}, '입금확인')
        with self.assertRaises(UserError):
            self.manager.register_tracking(other, 2, '01234567890')
        self.manager.import_tracking({number: '01234567890'})
        row = next(r for r in self.manager.view()['sales'] if r['metadata']['orderNumber'] == number)
        self.assertEqual(3, row['metadata']['revision'])


class SellerUiTest(QtTestCase):
    def test_fhd_pages_readonly_demo(self):
        window = SellerWindow()
        show_demo(window)
        window.resize(1920, 1080)
        window.show()
        self.app.processEvents()
        self.assertEqual(7, window.stack.count())
        self.assertFalse(window.sync_button.isEnabled())
        self.assertFalse(window.connection_file_button.isEnabled())
        self.assertFalse(window.copy_button.isEnabled())
        self.assertFalse(hasattr(window, 'inbox_sync_button'))
        self.assertEqual('견적요청 삭제', window.delete_button.text())
        self.assertEqual('견적 작성', window.request_quote_button.text())
        draft_headers = [window.draft_table.horizontalHeaderItem(i).text() for i in range(window.draft_table.columnCount())]
        self.assertEqual(['견적번호', '상태', '작성일', '고객명', '연락처', '메일', '공식견적서', '품목', '판매금액'], draft_headers)
        customer_headers = [window.sale_table.horizontalHeaderItem(i).text() for i in range(window.sale_table.columnCount())]
        self.assertEqual(
            ['주문번호', '상태', '주문일', '고객명', '연락처', '이메일', '수령인', '배송주소', '품목', '결제금액'],
            customer_headers,
        )
        self.assertEqual(['입금확인', '입금확인', '입금확인'], [window.sale_table.item(i, 1).text() for i in range(3)])
        self.assertGreaterEqual(window.request_actions.indexOf(window.delete_button), 0)
        self.assertGreaterEqual(window.request_actions.indexOf(window.request_quote_button), 0)
        self.assertEqual(3, len(window.shipping_rows))
        for index in range(7):
            window.navigate(index)
            self.app.processEvents()
            self.assertEqual(index, window.stack.currentIndex())
            self.assertLessEqual(window.stack.geometry().right(), window.width())
        window.demo_mode = False
        window.close()

    def test_quote_workbench_is_stably_sorted_by_quote_number_and_header_toggles(self):
        window = SellerWindow()
        show_demo(window)
        samples = []
        for number in ('NAMU-20261011-0010', 'NAMU-20261011-0002', 'NAMU-20261011-0007'):
            draft = draft_for_test()
            draft.update(requestReceiptNumber=number, sourceRequestId='')
            samples.append(draft)
        window.data['drafts'] = samples
        window.refresh_workspace()
        ascending = [window.draft_table.item(i, 0).text() for i in range(window.draft_table.rowCount())]
        self.assertEqual(
            ['NAMU-20261011-0002', 'NAMU-20261011-0007', 'NAMU-20261011-0010'],
            ascending,
        )
        window.sort_drafts_by_header(0)
        descending = [window.draft_table.item(i, 0).text() for i in range(window.draft_table.rowCount())]
        self.assertEqual(
            ['NAMU-20261011-0010', 'NAMU-20261011-0007', 'NAMU-20261011-0002'],
            descending,
        )
        self.assertEqual('NAMU-20261011-0010', window.draft_rows[0]['requestReceiptNumber'])
        window.demo_mode = False
        window.close()

    def test_customer_order_dialog_matches_current_workflow_and_collects_fields(self):
        draft = draft_for_test()
        order = order_from_draft(draft)
        row = {'metadata': metadata(order), 'private': {'order': order}}
        dialog = CustomerOrderDialog(row)
        dialog.show()
        self.app.processEvents()
        self.assertEqual('입금확인', dialog.findChild(QLabel, 'customerStatusBadge').text())
        headers = [dialog.items.horizontalHeaderItem(i).text() for i in range(dialog.items.columnCount())]
        self.assertEqual(['묘목명', '규격', '수량', '판매 단가', '판매 금액'], headers)
        self.assertTrue(dialog.delivery_button.isEnabled())
        self.assertTrue(dialog.cancel_order_button.isEnabled())
        dialog.customer_fields['customerEmail'].setText('buyer@example.com')
        fields = dialog.collect_fields()
        self.assertEqual('buyer@example.com', fields['customerEmail'])
        self.assertIn('costConfirmed', fields)
        dialog.close()

    def test_purchase_cancel_is_available_only_after_payment_in_customer_management(self):
        draft = draft_for_test()
        order = order_from_draft(draft)
        row = {'metadata': metadata(order), 'private': {'order': order}}
        dialog = CustomerOrderDialog(row)
        dialog.show()
        self.app.processEvents()
        self.assertEqual('입금확인', dialog.findChild(QLabel, 'customerStatusBadge').text())
        self.assertTrue(dialog.cancel_order_button.isVisible())
        self.assertTrue(dialog.cancel_order_button.isEnabled())
        with patch('namufarm_desktop.workspace.QMessageBox.question',
                   return_value=QMessageBox.StandardButton.Yes):
            dialog.cancel_order_button.click()
        self.assertEqual('cancel', dialog.mode)
        self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())

    def test_request_quote_price_editor_steps_and_manual_input(self):
        draft = draft_for_test()
        draft.update(sourceRequestId='123e4567-e89b-12d3-a456-426614174000', shippingFee=0, isPrepaid=False)
        dialog = QuoteDialog(draft, request_status='신규')
        dialog.show()
        self.app.processEvents()

        headers = [dialog.items.horizontalHeaderItem(i).text() for i in range(dialog.items.columnCount())]
        self.assertEqual(['묘목명', '수량', '판매 금액', '판매 단가'], headers)
        self.assertEqual('견적 확정', dialog.confirm_button.text())
        self.assertEqual('20,000', dialog.items.item(0, 2).text())
        self.assertFalse(hasattr(dialog, 'shipping_cod'))
        self.assertFalse(hasattr(dialog, 'shipping_prepaid'))
        self.assertFalse(hasattr(dialog, 'shipping_fee'))
        self.assertEqual(0, dialog.collect()['shippingFee'])
        self.assertFalse(dialog.collect()['isPrepaid'])
        self.assertGreaterEqual(dialog.note.width(), dialog.width() * 0.75)

        price = dialog.unit_price_editor(0)
        self.assertEqual('10000', price.text())
        price.plus.click()
        self.app.processEvents()
        self.assertEqual('11000', price.text())
        self.assertEqual('22,000', dialog.items.item(0, 2).text())

        price.minus.click()
        price.input.setText('12500')
        self.app.processEvents()
        self.assertEqual(12500, dialog.collect()['items'][0]['unitPrice'])
        self.assertEqual('25,000', dialog.items.item(0, 2).text())

        dialog.set_unit_price(0, 15000)
        self.app.processEvents()
        self.assertEqual('15000', price.text())
        self.assertEqual('30,000', dialog.items.item(0, 2).text())
        dialog.initial = dialog.raw_state()
        dialog.close()

    def test_delivery_dialog_is_separate_from_request_quote_editor(self):
        draft = draft_for_test()
        draft.update(
            sourceRequestId='123e4567-e89b-12d3-a456-426614174000',
            requestReceiptNumber='NAMU-20261011-0001',
            customerEmail='buyer@example.com',
            formalQuoteRequested=True,
            quoteRecipientName='테스트 고객',
            quoteReplyChannel='both',
        )
        dialog = QuoteDeliveryDialog(draft, '견적확정', delivery_state={'emailSentAt': 123456789})
        dialog.show()
        self.app.processEvents()
        headers = [dialog.items.horizontalHeaderItem(i).text() for i in range(dialog.items.columnCount())]
        self.assertEqual(['묘목명', '수량', '판매 단가', '판매 금액'], headers)
        self.assertTrue(dialog.sms_button.isEnabled())
        self.assertTrue(dialog.email_button.isEnabled())
        self.assertTrue(dialog.sent_button.isEnabled())
        self.assertFalse(dialog.payment_button.isVisible())
        self.assertFalse(hasattr(dialog, 'cancel_purchase_button'))
        self.assertEqual('공식견적서 생성', dialog.generate_quote_button.text())
        self.assertFalse(dialog.preview_quote_button.isEnabled())
        self.assertEqual('문자 미전송', dialog.sms_status_badge.text())
        self.assertEqual('메일 전송완료', dialog.email_status_badge.text())
        self.assertTrue(dialog.shipping_prepaid.isChecked())
        self.assertTrue(dialog.shipping_fee.isEnabled())
        self.assertTrue(dialog.shipping_fee_label.isEnabled())
        self.assertEqual('3500', dialog.shipping_fee.text())
        self.assertEqual('25,850원', dialog.total_value.text())
        self.assertIn('선불 3,500원', dialog.shipping_status.text())

        dialog.shipping_cod.click()
        self.app.processEvents()
        self.assertFalse(dialog.shipping_fee.isEnabled())
        self.assertFalse(dialog.shipping_fee_label.isEnabled())
        self.assertEqual('22,350원', dialog.total_value.text())
        self.assertIn('후불', dialog.shipping_status.text())
        self.assertIn('NAMU-20261011-0001', dialog.delivery_text())
        dialog.close()

    def test_sent_quote_dialog_exposes_only_payment_confirmation(self):
        draft = draft_for_test()
        draft.update(sourceRequestId='123e4567-e89b-12d3-a456-426614174000',
                     requestReceiptNumber='NAMU-20261011-0001', shippingFee=6000,
                     isPrepaid=False, taxAmount=0)
        dialog = QuoteDeliveryDialog(draft, '견적발송완료')
        dialog.show()
        self.app.processEvents()
        self.assertFalse(dialog.sent_button.isVisible())
        self.assertTrue(dialog.payment_button.isVisible())
        self.assertTrue(dialog.payment_button.isEnabled())
        self.assertFalse(hasattr(dialog, 'cancel_purchase_button'))
        with patch('namufarm_desktop.workspace.QMessageBox.question',
                   return_value=QMessageBox.StandardButton.Yes):
            dialog.confirm_payment()
        self.assertEqual('paid', dialog.mode)
        self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())


    def test_delivery_dialog_defaults_unset_web_quote_shipping_to_cod_6000(self):
        draft = draft_for_test()
        draft.update(sourceRequestId='123e4567-e89b-12d3-a456-426614174000',
                     shippingFee=0, isPrepaid=False, taxAmount=0)
        dialog = QuoteDeliveryDialog(draft, '견적확정')
        self.assertTrue(dialog.shipping_cod.isChecked())
        self.assertFalse(dialog.shipping_fee.isEnabled())
        self.assertEqual('6000', dialog.shipping_fee.text())
        self.assertEqual('20,000원', dialog.total_value.text())
        dialog.shipping_prepaid.click()
        self.app.processEvents()
        self.assertTrue(dialog.shipping_fee.isEnabled())
        self.assertEqual('26,000원', dialog.total_value.text())
        self.assertTrue(dialog.draft['isPrepaid'])
        dialog.close()

    def test_official_quote_generation_is_temporary_and_enables_preview(self):
        draft = draft_for_test()
        draft.update(sourceRequestId='123e4567-e89b-12d3-a456-426614174000',
                     requestReceiptNumber='NAMU-20261011-0001', shippingFee=6000,
                     isPrepaid=False, taxAmount=0)
        dialog = QuoteDeliveryDialog(draft, '견적확정')
        self.assertIsNone(dialog.generated_quote_image)
        self.assertEqual('공식 견적서 미생성', dialog.quote_file_label.text())
        dialog.generate_official_quote()
        self.assertIsNotNone(dialog.generated_quote_image)
        self.assertEqual((955, 1342), (dialog.generated_quote_image.width(), dialog.generated_quote_image.height()))
        self.assertTrue(dialog.preview_quote_button.isEnabled())
        self.assertEqual('공식견적서 재생성', dialog.generate_quote_button.text())
        self.assertEqual('공식 견적서 생성완료 : NAMU-20261011-0001_공식견적서.pdf',
                         dialog.quote_file_label.text())
        first_path = dialog.generated_quote_path
        self.assertTrue(first_path.is_file())
        dialog.generate_official_quote()
        self.assertEqual(first_path, dialog.generated_quote_path)
        self.assertTrue(first_path.is_file())
        dialog.shipping_prepaid.click()
        self.app.processEvents()
        self.assertIsNone(dialog.generated_quote_image)
        self.assertIsNone(dialog.generated_quote_path)
        self.assertFalse(first_path.exists())
        self.assertEqual('공식 견적서 미생성', dialog.quote_file_label.text())
        self.assertFalse(dialog.preview_quote_button.isEnabled())
        dialog.close()

    def test_official_quote_renderer_uses_exact_a4_ratio_canvas(self):
        draft = draft_for_test()
        draft.update(requestReceiptNumber='NAMU-20261011-0003', taxAmount=0,
                     shippingFee=6000, isPrepaid=False)
        image = render_official_quote_image(draft)
        self.assertEqual(955, image.width())
        self.assertEqual(1342, image.height())
        self.assertFalse(image.isNull())
        red_pixels = 0
        for y in range(86, 136, 4):
            for x in range(858, 912, 4):
                color = image.pixelColor(x, y)
                if color.red() > color.green() + 20 and color.red() > color.blue() + 20:
                    red_pixels += 1
        self.assertGreater(red_pixels, 3)

    def test_temporary_official_quote_pdf_does_not_require_printer(self):
        draft = draft_for_test()
        draft.update(requestReceiptNumber='NAMU-20261011-0003', taxAmount=0,
                     shippingFee=6000, isPrepaid=False)
        image = render_official_quote_image(draft)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'NAMU-20261011-0003_공식견적서.pdf'
            result = write_temporary_quote_pdf(image, path)
            self.assertEqual(path, result)
            self.assertTrue(path.is_file())
            self.assertGreater(path.stat().st_size, 1000)

    def test_delivery_dialog_expands_item_area_for_multiple_rows(self):
        draft = draft_for_test()
        draft.update(sourceRequestId='123e4567-e89b-12d3-a456-426614174000',
                     shippingFee=0, isPrepaid=False, taxAmount=0)
        draft['items'] = [
            dict(species=f'묘목{i}', specification='', quantity=1, unitPrice=1000, unitCost=0)
            for i in range(6)
        ]
        dialog = QuoteDeliveryDialog(draft, '견적확정')
        self.assertGreaterEqual(dialog.items.minimumHeight(), 62 + 6 * 54)
        dialog.close()

    def test_quote_sms_dialog_shows_setup_needed_without_keys(self):
        class FakeSmsManager:
            def sms_profile(self):
                return {'provider': 'SOLAPI', 'configured': False, 'senderPhone': ''}

        draft = draft_for_test()
        draft.update(sourceRequestId='123e4567-e89b-12d3-a456-426614174000',
                     requestReceiptNumber='NAMU-20261011-0001', shippingFee=6000, isPrepaid=False, taxAmount=0)
        dialog = QuoteSmsDialog(draft, FakeSmsManager())
        dialog.show()
        self.app.processEvents()
        if dialog.profile_task:
            self.assertTrue(dialog.profile_task.wait(10000))
            self.app.processEvents()
        self.assertEqual('설정 필요', dialog.sender_phone.text())
        self.assertFalse(dialog.send_button.isEnabled())
        self.assertIn('문자 발신 설정이 필요합니다', dialog.status.text())
        self.assertNotIn('SOLAPI', dialog.status.text())
        self.assertIn('품목1 : 소나무 2주', dialog.message.toPlainText())
        dialog.close()

    def test_quote_sms_does_not_replay_previous_success_on_open(self):
        class FakeSmsManager:
            def quote_delivery_state(self, identifier):
                return {'smsSentAt': now_ms(), 'smsState': 'success', 'smsMessageId': 'M4V_OLD'}
            def sms_profile(self):
                return {'provider': 'SOLAPI', 'configured': True, 'senderPhone': '01044460304'}

        draft = draft_for_test()
        dialog = QuoteSmsDialog(draft, FakeSmsManager())
        dialog.show()
        self.app.processEvents()
        if dialog.profile_task:
            self.assertTrue(dialog.profile_task.wait(10000))
            self.app.processEvents()
        self.assertEqual('문자 발신 준비 완료', dialog.status.text())
        dialog.close()

    def test_quote_sms_success_uses_single_status_line(self):
        class FakeSmsManager:
            def __init__(self):
                self.state = {'smsSentAt': 0, 'smsState': '', 'smsMessageId': ''}

            def quote_delivery_state(self, identifier):
                return copy.deepcopy(self.state)

            def sms_profile(self):
                return {'provider': 'SOLAPI', 'configured': True, 'senderPhone': '01044460304'}

            def send_quote_sms(self, draft, text):
                self.state = {
                    'smsSentAt': 0, 'smsState': 'pending', 'smsMessageId': 'M4V_TEST',
                    'smsTo': draft['customerPhone'], 'senderPhone': '01044460304',
                    'smsStatusCode': '2000', 'smsStatusMessage': '문자 발송을 접수했습니다.'
                }
                return {'delivery': copy.deepcopy(self.state), 'senderPhone': '01044460304',
                        'messageId': 'M4V_TEST', 'statusCode': '2000'}

            def refresh_quote_sms_status(self, identifier):
                self.state.update({
                    'smsSentAt': now_ms(), 'smsState': 'success', 'smsStatusCode': '4000',
                    'smsStatusMessage': '문자 전송에 성공했습니다.'
                })
                return copy.deepcopy(self.state)

        draft = draft_for_test()
        draft.update(sourceRequestId='123e4567-e89b-12d3-a456-426614174000',
                     requestReceiptNumber='NAMU-20261011-0001', shippingFee=6000, isPrepaid=False, taxAmount=0)
        manager = FakeSmsManager()
        dialog = QuoteSmsDialog(draft, manager)
        dialog.show()
        self.app.processEvents()
        if dialog.profile_task:
            self.assertTrue(dialog.profile_task.wait(10000))
            self.app.processEvents()
        self.assertTrue(dialog.send_button.isEnabled())
        self.assertFalse(hasattr(dialog, 'log_view'))

        dialog.send_sms()
        task = dialog.send_task
        self.assertIsNotNone(task)
        self.assertTrue(task.wait(10000))
        self.app.processEvents()
        self.assertEqual('pending', dialog.delivery_state['smsState'])
        self.assertEqual('문자전송 결과 확인중…', dialog.status.text())

        dialog.check_status()
        task = dialog.status_task
        self.assertIsNotNone(task)
        self.assertTrue(task.wait(10000))
        self.app.processEvents()

        self.assertIsNone(dialog.status_task)
        self.assertEqual('success', dialog.delivery_state['smsState'])
        self.assertEqual('문자전송 성공', dialog.status.text())
        self.assertEqual(QDialog.DialogCode.Rejected, dialog.result())
        dialog.close()

    def test_delivery_email_prompt_offers_generated_official_quote_attachment(self):
        class FakeManager:
            def save_draft(self, draft):
                return None
            def quote_delivery_state(self, identifier):
                return {}

        draft = draft_for_test()
        draft.update(customerEmail='buyer@example.com', sourceRequestId='123e4567-e89b-12d3-a456-426614174000',
                     requestReceiptNumber='NAMU-20261011-0001', shippingFee=6000, isPrepaid=False, taxAmount=0)
        dialog = QuoteDeliveryDialog(draft, '견적확정', FakeManager())
        with tempfile.TemporaryDirectory() as directory:
            pdf = Path(directory) / 'NAMU-20261011-0001_공식견적서.pdf'
            pdf.write_bytes(b'%PDF-1.4\nTEST\n')
            dialog.generated_quote_path = pdf
            captured = {}
            class FakeEmailDialog:
                def __init__(self, draft, manager, parent, attachment_path=None):
                    captured['path'] = attachment_path
                    self.delivery_state = {}
                def exec(self):
                    return QDialog.DialogCode.Rejected
            with patch('namufarm_desktop.workspace.QMessageBox.question',
                       return_value=QMessageBox.StandardButton.Yes),                  patch('namufarm_desktop.workspace.QuoteEmailDialog', FakeEmailDialog):
                dialog.open_email()
            self.assertEqual(pdf, captured['path'])
        dialog.generated_quote_path = None
        dialog.close()

    def test_quote_email_dialog_is_message_focused_and_uses_customer_name(self):
        draft = draft_for_test()
        draft.update(customerEmail='buyer@example.com', requestReceiptNumber='NAMU-20261011-0001',
                     customerName='테스트 고객', quoteRecipientName='다른 수신인',
                     shippingFee=3500, isPrepaid=False, taxAmount=0)
        dialog = QuoteEmailDialog(draft)
        dialog.show()
        self.app.processEvents()
        self.assertEqual('테스트 고객', dialog.recipient_name.text())
        self.assertEqual('buyer@example.com', dialog.recipient_email.text())
        self.assertEqual('ssanai91@gmail.com', dialog.sender_email.text())
        self.assertEqual('묘목 견적 안내', dialog.subject.text())
        body = dialog.message.toPlainText()
        self.assertTrue(body.startswith('테스트 고객님, 안녕하세요.'))
        self.assertIn('품목1 : 소나무 2주', body)
        self.assertIn('판매금액 : 20,000원', body)
        self.assertIn('안녕하세요.\n\n나무팜농원입니다.', body)
        self.assertIn('===========================\n\n견적번호', body)
        self.assertNotIn('안녕하세요.\n\n\n나무팜농원입니다.', body)
        self.assertIn('총금액 : 20,000원', body)
        self.assertIn('택배비 : 후불 / 선불 시 : 3,500원', body)
        self.assertIn('010-4446-0304 / 043-732-0980', body)
        self.assertFalse(hasattr(dialog, 'items'))
        self.assertNotIn('<table', dialog.html_body())
        dialog.close()

    def test_quote_email_dialog_sends_selected_official_quote_attachment(self):
        class FakeMailManager:
            def email_profile(self):
                return {'senderName': '나무팜농원', 'senderEmail': 'ssanai91@gmail.com', 'remainingDailyQuota': 99}

            def send_quote_email(self, draft, subject, plain, html, attachment_path=None):
                self.attachment_path = attachment_path
                return {
                    'delivery': {'emailSentAt': 123456789, 'emailTo': draft['customerEmail'],
                                 'senderEmail': 'ssanai91@gmail.com'},
                    'senderEmail': 'ssanai91@gmail.com',
                    'attachmentName': Path(attachment_path).name if attachment_path else '',
                    'sentAt': '2026-10-11T05:00:00.000Z',
                    'remainingDailyQuota': 98,
                }

        draft = draft_for_test()
        draft.update(customerEmail='buyer@example.com', requestReceiptNumber='NAMU-20261011-0001',
                     customerName='테스트 고객', shippingFee=6000, isPrepaid=False, taxAmount=0)
        manager = FakeMailManager()
        with tempfile.TemporaryDirectory() as directory:
            pdf = Path(directory) / 'NAMU-20261011-0001_공식견적서.pdf'
            pdf.write_bytes(b'%PDF-1.4\nTEST\n')
            dialog = QuoteEmailDialog(draft, manager, attachment_path=pdf)
            dialog.show()
            self.app.processEvents()
            if dialog.profile_task:
                self.assertTrue(dialog.profile_task.wait(10000))
                self.app.processEvents()
            self.assertEqual(pdf.name, dialog.attachment_label.text())
            with patch('namufarm_desktop.workspace.QMessageBox.information'):
                dialog.send_email()
                task = dialog.send_task
                self.assertIsNotNone(task)
                self.assertTrue(task.wait(10000))
                self.app.processEvents()
            self.assertEqual(pdf, manager.attachment_path)
            self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())

    def test_quote_email_success_completion_stays_on_gui_thread_and_closes_cleanly(self):
        class FakeMailManager:
            def email_profile(self):
                return {'senderName': '나무팜농원', 'senderEmail': 'ssanai91@gmail.com', 'remainingDailyQuota': 99}

            def send_quote_email(self, draft, subject, plain, html, attachment_path=None):
                return {
                    'delivery': {'emailSentAt': 123456789, 'emailTo': draft['customerEmail'],
                                 'senderEmail': 'ssanai91@gmail.com'},
                    'senderEmail': 'ssanai91@gmail.com',
                    'sentAt': '2026-10-11T05:00:00.000Z',
                    'remainingDailyQuota': 98,
                }

        draft = draft_for_test()
        draft.update(customerEmail='buyer@example.com', requestReceiptNumber='NAMU-20261011-0001',
                     customerName='테스트 고객', shippingFee=6000, isPrepaid=False, taxAmount=0)
        dialog = QuoteEmailDialog(draft, FakeMailManager())
        dialog.show()
        self.app.processEvents()

        if dialog.profile_task:
            self.assertTrue(dialog.profile_task.wait(10000))
            self.app.processEvents()
        self.assertIsNone(dialog.profile_task)

        with patch('namufarm_desktop.workspace.QMessageBox.information') as notice:
            dialog.send_email()
            task = dialog.send_task
            self.assertIsNotNone(task)
            self.assertTrue(task.wait(10000))
            self.app.processEvents()

        self.assertIsNone(dialog.send_task)
        self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())
        self.assertEqual(123456789, dialog.delivery_state['emailSentAt'])
        notice.assert_called_once()

    def test_quote_email_message_includes_prepaid_shipping_in_total(self):
        draft = draft_for_test()
        draft.update(customerEmail='buyer@example.com', requestReceiptNumber='NAMU-20261011-0001',
                     customerName='테스트 고객', shippingFee=6000, isPrepaid=True, taxAmount=0)
        dialog = QuoteEmailDialog(draft)
        body = dialog.default_message()
        self.assertIn('총금액 : 26,000원', body)
        self.assertIn('택배비 : 선불 6,000원 (총금액 포함)', body)

    def test_quote_editor_calculates_and_prints_pdf(self):
        draft = draft_for_test()
        dialog = QuoteDialog(draft)
        dialog.show()
        self.app.processEvents()
        self.assertIn('25,850', dialog.total.text())
        dialog.items.item(0, 2).setText('3')
        self.assertEqual(3, dialog.collect()['items'][0]['quantity'])
        self.assertIn('35,850', dialog.total.text())
        self.assertLessEqual(dialog.items.geometry().right(), dialog.width())
        with tempfile.TemporaryDirectory() as directory:
            printer = QPrinter()
            printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat)
            path = Path(directory)/'quote.pdf'
            printer.setOutputFileName(str(path))
            doc = QTextDocument()
            doc.setHtml(quote_html(dialog.collect()))
            doc.print_(printer)
            self.assertGreater(path.stat().st_size, 1000)
            self.assertTrue(path.read_bytes().startswith(b'%PDF'))
        dialog.initial = dialog.raw_state()
        dialog.close()


if __name__ == '__main__':
    unittest.main()

