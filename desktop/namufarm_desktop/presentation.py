"""Display-only formatting and panels. Never imports manager, API, store, or pricing logic."""
from datetime import datetime, timedelta
from html import escape
import re
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import (QApplication, QFrame, QLabel, QVBoxLayout, QHBoxLayout,
                              QScrollArea, QWidget, QPushButton, QGridLayout, QSizePolicy, QStyle)
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


def time_value(*values):
    from .list_filters import record_date
    return next((value for value in values if record_date(value) is not None), None)


def phones_in_text(value):
    return re.sub(r'(?<![0-9])0[0-9]{8,10}(?![0-9])', lambda match: phone_text(match.group()), str(value))


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
            qty.setObjectName("detailQuantity")
            layout.addWidget(qty)
            self.body.addWidget(row)


class RequestDetailPanel(QFrame):
    """Customer card for incoming quotes only; the sales DetailPanel stays unchanged."""
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName('requestDetailPanel')
        self.setMinimumWidth(360)
        self.setMaximumWidth(420)
        root = QVBoxLayout(self)
        root.setContentsMargins(12, 12, 12, 12)
        root.setSpacing(12)
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.scroll.setMinimumWidth(0)
        root.addWidget(self.scroll, 1)
        self.footer = QVBoxLayout()
        self.footer.setSpacing(8)
        root.addLayout(self.footer)
        self._shown_request = object()
        self.show_request(None)

    @staticmethod
    def text(value='', name='', *, wrap=True):
        result = plain_label(value, name, wrap)
        result.setMinimumWidth(0)
        if wrap:
            result.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        return result

    def info_cell(self, caption, value):
        cell = QFrame()
        cell.setObjectName('requestInfoCell')
        column = QVBoxLayout(cell)
        column.setContentsMargins(10, 8, 10, 8)
        column.setSpacing(4)
        column.addWidget(self.text(caption, 'requestInfoCaption'))
        column.addWidget(self.text(value or '—', 'requestInfoValue'))
        return cell

    def show_request(self, row):
        # Copy display fields only, so unrelated updates do not reset reading/copy feedback.
        personal = row['personal'] if row is not None else {}
        payload = None if row is None else {
            'name': personal.get('customerName') or '—',
            'phone': phone_text(personal.get('customerPhone')),
            'region': personal.get('region'), 'email': personal.get('customerEmail'),
            'channel': personal.get('replyChannel'), 'formal': bool(personal.get('formalQuoteRequested')),
            'items': [{key: item.get(key) for key in ('species', 'specification', 'quantity')}
                      for item in personal.get('items', [])],
            'message': personal.get('message') or '',
            'reference': row.get('receiptNumber') or '접수번호 없음', 'createdAt': row.get('createdAt'),
        }
        if payload == self._shown_request:
            return
        self._shown_request = payload
        old = self.scroll.takeWidget()
        if old is not None:
            old.hide()
            old.deleteLater()
        body = QWidget()
        body.setObjectName('requestDetailBody')
        column = QVBoxLayout(body)
        column.setContentsMargins(0, 0, 2, 0)
        column.setSpacing(14)
        if payload is None:
            empty = self.text('목록에서 선택해주세요.', 'requestEmpty')
            empty.setAlignment(Qt.AlignmentFlag.AlignCenter)
            column.addWidget(empty, 1)
            self.scroll.setWidget(body)
            return

        heading = QHBoxLayout()
        heading.setSpacing(12)
        avatar = self.text(payload['name'][0], 'requestAvatar', wrap=False)
        avatar.setFixedSize(56, 56)
        avatar.setAlignment(Qt.AlignmentFlag.AlignCenter)
        heading.addWidget(avatar, 0, Qt.AlignmentFlag.AlignTop)
        identity = QVBoxLayout()
        identity.setSpacing(4)
        identity.addWidget(self.text(payload['name'], 'requestCustomerName'))
        badge = self.text('신규 요청', 'requestNewBadge', wrap=False)
        identity.addWidget(badge, 0, Qt.AlignmentFlag.AlignLeft)
        heading.addLayout(identity, 1)
        column.addLayout(heading)
        column.addWidget(self.text(payload['reference'] + ' · ' + date_text(payload['createdAt']), 'requestReference'))

        contact = QFrame()
        contact.setObjectName('requestContactCard')
        contact_box = QVBoxLayout(contact)
        contact_box.setContentsMargins(10, 10, 10, 10)
        contact_box.setSpacing(4)
        contact_box.addWidget(self.text('연락처', 'requestContactCaption'))
        phone_row = QHBoxLayout()
        phone_row.setSpacing(6)
        phone = self.text(payload['phone'] or '—', 'requestPhone', wrap=False)
        phone.setToolTip(payload['phone'])
        phone_row.addWidget(phone, 1)
        action = QPushButton('복사')
        action.setObjectName('requestPhoneCopy')
        action.setMinimumWidth(96)
        action.setFixedHeight(46)
        action.setIcon(QIcon.fromTheme('edit-copy', self.style().standardIcon(QStyle.StandardPixmap.SP_FileIcon)))
        action.setAccessibleName('연락처 복사')
        action.setEnabled(bool(payload['phone']))
        timer = QTimer(action)
        timer.setSingleShot(True)
        timer.timeout.connect(lambda: action.setText('복사'))
        def copy_phone():
            QApplication.clipboard().setText(payload['phone'])
            action.setText('복사됨')
            timer.start(1500)
        action.clicked.connect(copy_phone)
        phone_row.addWidget(action)
        contact_box.addLayout(phone_row)
        column.addWidget(contact)

        grid = QGridLayout()
        grid.setSpacing(8)
        grid.setColumnStretch(0, 1)
        grid.setColumnStretch(1, 1)
        grid.addWidget(self.info_cell('지역', payload['region']), 0, 0)
        grid.addWidget(self.info_cell('받는 방법', payload['channel']), 0, 1)
        grid.addWidget(self.info_cell('이메일', payload['email']), 1, 0, 1, 2)
        official = self.info_cell('공식견적서', 'PDF 견적서 요청' if payload['formal'] else '요청 없음')
        official.setObjectName('requestOfficialCard')
        official.setProperty('requested', payload['formal'])
        grid.addWidget(official, 2, 0, 1, 2)
        column.addLayout(grid)

        items_heading = QHBoxLayout()
        items_heading.addWidget(self.text('주문 묘목', 'requestItemsHeading'), 1)
        items_heading.addWidget(self.text(f"{len(payload['items']):,}종", 'requestItemsCount', wrap=False))
        column.addLayout(items_heading)
        items = QFrame()
        items.setObjectName('requestItemsCard')
        item_rows = QVBoxLayout(items)
        item_rows.setContentsMargins(0, 0, 0, 0)
        item_rows.setSpacing(0)
        quantity = 0
        for index, item in enumerate(payload['items'], 1):
            item_row = QWidget()
            item_row.setObjectName('requestItemRow')
            line = QHBoxLayout(item_row)
            line.setContentsMargins(10, 10, 10, 10)
            line.setSpacing(8)
            number = self.text(str(index), 'requestItemNumber', wrap=False)
            number.setFixedSize(26, 26)
            number.setAlignment(Qt.AlignmentFlag.AlignCenter)
            line.addWidget(number)
            seedling = QVBoxLayout()
            seedling.setSpacing(2)
            seedling.addWidget(self.text(item['species'] or '—', 'requestItemSpecies'))
            seedling.addWidget(self.text(item['specification'] or '규격 미입력', 'requestItemSpecification'))
            line.addLayout(seedling, 1)
            count = item['quantity'] or 0
            quantity += count
            line.addWidget(self.text(f'{count:,}주', 'requestItemQuantity', wrap=False))
            item_rows.addWidget(item_row)
        total = self.text(f'합계 {quantity:,}주', 'requestItemsTotal')
        total.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        item_rows.addWidget(total)
        column.addWidget(items)
        column.addWidget(self.text('고객 요청사항', 'requestNoteHeading'))
        note = self.text('', 'requestCustomerNote')
        note.setProperty('hasMessage', bool(payload['message']))
        note.setTextFormat(Qt.TextFormat.RichText)
        message = escape(payload['message'] or '고객 요청사항 없음').replace('\n', '<br>')
        note.setText('<p style="line-height:160%;white-space:pre-wrap;margin:0;">' + message + '</p>')
        column.addWidget(note)
        column.addStretch(1)
        self.scroll.setWidget(body)
        self.scroll.verticalScrollBar().setValue(0)


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
