"""Shared recovery controls for quote/payment/report sends; no automatic resend."""
from PySide6.QtCore import Slot
from PySide6.QtWidgets import QMessageBox, QPushButton


class DeliveryRecoveryTools:
    def setup_delivery_recovery(self, actions, channel):
        self.delivery_channel = channel
        self.status_task = None
        if not hasattr(self, 'check_button'):
            self.check_button = QPushButton('결과 확인')
            self.check_button.setEnabled(False)
            self.check_button.clicked.connect(self.check_uncertain_delivery)
            actions.addWidget(self.check_button)
        self.dismiss_button = QPushButton('발송되지 않았음을 확인했어요')
        self.dismiss_button.clicked.connect(self.dismiss_delivery)
        self.dismiss_button.hide()
        if hasattr(self.layout(), "addRow"):
            self.layout().addRow(self.dismiss_button)
        else:
            self.layout().addWidget(self.dismiss_button)
        self.restore_uncertain_delivery()

    def recovery_identifier(self):
        return 'report' if self.delivery_channel == 'report_email' else self.draft['id']

    def recovery_state(self):
        if not self.manager:
            return {}
        if self.delivery_channel == 'report_email':
            return self.manager.vault.preference('sales_report_delivery', {})
        getter = getattr(self.manager, "payment_delivery_state" if getattr(self, "payment_notice", False) else "quote_delivery_state", None)
        value = getter(self.recovery_identifier()) if callable(getter) else {}
        return value if isinstance(value, dict) else {}

    def restore_uncertain_delivery(self, *, allow_dismiss=False):
        self.delivery_state = self.recovery_state()
        pending = self.delivery_state.get('pendingDelivery')
        if not pending:
            return False
        self.send_button.setEnabled(False)
        self.message.setEnabled(False)
        if hasattr(self, 'subject'):
            self.subject.setEnabled(False)
        if hasattr(self, 'recipient_phone'):
            self.recipient_phone.setReadOnly(True)
            if pending.get('channel') == 'sms':
                self.recipient_phone.setText(pending.get('recipient', ''))
        if hasattr(self, 'recipient'):
            self.recipient.setReadOnly(True)
        if hasattr(self, 'payment_confirm_button'):
            self.payment_confirm_button.setEnabled(False)
        self.cancel_button.setEnabled(True)
        same_channel = pending.get('channel') == self.delivery_channel
        self.check_button.setEnabled(same_channel and not self.profile_task and not self.send_task and not self.status_task)
        self.dismiss_button.setVisible(allow_dismiss and same_channel)
        self.status.setText('결과 불명 · 결과 확인 필요' if same_channel else
                            '이전 발송 결과를 먼저 확인해주세요. 해당 문자/메일 창에서 결과 확인을 눌러주세요.')
        return True

    def check_uncertain_delivery(self, checked=False):
        if not self.manager or self.profile_task or self.send_task or self.status_task:
            return
        pending = self.recovery_state().get('pendingDelivery')
        if not pending or pending.get('channel') != self.delivery_channel:
            return
        from .app import Task
        self.check_button.setEnabled(False)
        self.dismiss_button.setEnabled(False)
        self.status.setText('발송 결과 확인 중')
        task = Task(lambda: self.manager.check_quote_delivery(self.recovery_identifier(), self.delivery_channel,
                    payment=getattr(self, 'payment_notice', False)), None, self)
        self.status_task = task
        task.completed.connect(self.uncertain_delivery_checked)
        task.start()

    @Slot(object, str, bool)
    def uncertain_delivery_checked(self, value, error, conflict):
        task = self.status_task
        if task is not None:
            task.wait()
            self.status_task = None
            task.deleteLater()
        self.dismiss_button.setEnabled(True)
        if self.restore_uncertain_delivery(allow_dismiss=not error):
            self.status.setText('서버 처리 결과를 확인하지 못했습니다.' + (' ' + error if error else
                                ' SOLAPI 발송내역/보낸편지함을 확인해주세요.'))
            return
        if error:
            # The journal may have resolved SENT before a carrier-status lookup failed.
            self.delivery_state = self.recovery_state()
            self.delivery_recovered(self.delivery_state)
            self.status.setText('발송 접수 기록을 복구했습니다. 최종 결과 확인 필요 · ' + error)
            return
        self.delivery_state = value or self.recovery_state()
        self.dismiss_button.hide()
        self.delivery_recovered(self.delivery_state)

    def dismiss_delivery(self, checked=False):
        if self.profile_task or self.send_task or self.status_task:
            return
        answer = QMessageBox.question(self, '발송내역 확인',
            'SOLAPI 발송내역 또는 보낸편지함에서 발송되지 않았음을 직접 확인했나요?\n'
            '실제로 발송된 요청을 해제하고 다시 보내면 중복 발송될 수 있습니다.',
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No)
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.delivery_state = self.manager.dismiss_uncertain_delivery(self.recovery_identifier(), self.delivery_channel,
                                payment=getattr(self, 'payment_notice', False))
        self.dismiss_button.hide()
        self.delivery_recovered(self.delivery_state)

    def delivery_recovered(self, state):
        raise NotImplementedError
