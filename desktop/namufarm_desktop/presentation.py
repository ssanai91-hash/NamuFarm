"""Display-only formatting and panels. Never imports manager, API, store, or pricing logic."""
from datetime import datetime, timedelta
import re
from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QApplication, QFrame, QLabel, QVBoxLayout, QHBoxLayout,
                              QScrollArea, QWidget, QPushButton)
from .domain import SEOUL
from .ui_theme import TOKENS, status_badge


def phone_text(value):
    raw = str(value or '')
    digits = re.sub(r'\D', '', raw)
    if len(digits) == 11 and digits.startswith('0'):
        return f'{digits[:3]}-{digits[3:7]}-{digits[7:]}'
    if len(digits) == 10:
        split = 2 if digits.startswith('02') else 3
        return f'{digits[:split]}-{digits[split:-4]}-{digits[-4:]}'
    if len(digits) == 9 and digits.startswith('02'):
        return f'{digits[:2]}-{digits[2:5]}-{digits[5:]}'
    return raw


def date_text(value, now=None):
    if not value:
        return '—'
    try:
        date = datetime.fromisoformat(value.replace('Z', '+00:00')) if isinstance(value, str) else datetime.fromtimestamp(value / 1000, SEOUL)
        date = date.replace(tzinfo=SEOUL) if date.tzinfo is None else date.astimezone(SEOUL)
    except (ValueError, TypeError, OverflowError, OSError):
        return str(value)
    now = now or datetime.now(SEOUL)
    if date.date() == now.date():
        return date.strftime('오늘 %H:%M')
    if date.date() == (now - timedelta(days=1)).date():
        return date.strftime('어제 %H:%M')
    return date.strftime('%m.%d' if date.year == now.year else '%y.%m.%d')


def plain_label(text='', name='', wrap=True):
    result = QLabel(str(text))
    result.setObjectName(name)
    result.setTextFormat(Qt.TextFormat.PlainText)
    result.setWordWrap(wrap)
    result.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
    return result


class DetailPanel(QFrame):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName('detailPanel')
        self.setMinimumWidth(360)
        self.setMaximumWidth(400)
        root = QVBoxLayout(self)
        root.setContentsMargins(16, 16, 16, 16)
        root.setSpacing(8)
        title_row = QHBoxLayout()
        self.title = plain_label('목록에서 선택해주세요.', 'detailCustomer')
        self.badge = plain_label('', 'stageBadge', False)
        title_row.addWidget(self.title, 1)
        title_row.addWidget(self.badge)
        root.addLayout(title_row)
        self.subtitle = plain_label('', 'muted')
        root.addWidget(self.subtitle)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setMinimumWidth(0)
        body = QWidget()
        self.body = QVBoxLayout(body)
        self.body.setContentsMargins(0, 0, 8, 0)
        self.body.setSpacing(8)
        scroll.setWidget(body)
        root.addWidget(scroll, 1)
        self.footer = QVBoxLayout()
        self.footer.setSpacing(8)
        root.addLayout(self.footer)

    def reset(self, name, reference='', stage=''):
        while self.body.count():
            item = self.body.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        self.title.setText(name)
        self.subtitle.setText(reference)
        badge = status_badge(stage)
        self.badge.setText(badge['text'])
        self.badge.setStyleSheet(badge['style'])
        self.badge.setVisible(bool(stage))

    def info(self, caption, value, *, copy=False):
        row = QWidget()
        layout = QHBoxLayout(row)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(8)
        key = plain_label(caption, 'muted')
        key.setFixedWidth(92)
        layout.addWidget(key)
        layout.addWidget(plain_label(value or '—'), 1)
        if copy:
            action = QPushButton('복사')
            action.setProperty('role', 'text')
            action.setMaximumWidth(56)
            action.clicked.connect(lambda: QApplication.clipboard().setText(str(value)))
            layout.addWidget(action)
        self.body.addWidget(row)

    def note(self, text):
        result = plain_label(text)
        result.setStyleSheet(f'background:{TOKENS["table-head"]};border-radius:8px;padding:8px;')
        self.body.addWidget(result)
        return result

    def items(self, items):
        for item in items:
            row = QFrame()
            row.setObjectName('card')
            layout = QHBoxLayout(row)
            layout.setContentsMargins(8, 8, 8, 8)
            info = QVBoxLayout()
            name = plain_label(item.get('species', ''), 'sectionTitle')
            info.addWidget(name)
            info.addWidget(plain_label(item.get('specification', '') or '규격 미입력', 'muted'))
            layout.addLayout(info, 1)
            qty = plain_label(f"{item.get('quantity', 0):,}주")
            qty.setStyleSheet('font-size:17px;font-weight:700;')
            layout.addWidget(qty)
            self.body.addWidget(row)


def order_stage(row):
    """Classify existing records for display only; never introduce persisted states."""
    from .commerce import tracking_number, shipping_prepared
    status = row['metadata']['status']
    order = row['private']['order']
    if status in ('구매취소', '주문취소'):
        return '구매취소'
    if status in ('배송완료', '판매완료'):
        return '배송완료'
    if status not in ('입금확인', '구매확정'):
        return status
    if tracking_number(order):
        return '송장등록'
    return '배송준비' if shipping_prepared(order) else '입금확인'
