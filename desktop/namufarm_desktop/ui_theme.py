"""Shared desktop presentation; no storage or network operations."""
import re

from PySide6.QtCore import QEvent, Qt, Signal, QRectF
from PySide6.QtGui import QColor, QPainter
from PySide6.QtWidgets import (QApplication, QHeaderView, QLabel, QScrollArea,
                               QSizePolicy, QTableWidget, QVBoxLayout, QWidget,
                               QStyledItemDelegate, QStyleOptionViewItem, QStyle)


def workspace_style(large=False):
    body, small = (18, 16) if large else (16, 14)
    css = """
QWidget{font-family:'Malgun Gothic','NanumGothic',sans-serif;font-size:@body@px;color:#263e35;}
QMainWindow,#workspace,QDialog{background:#f3f5f1;}
#sidebar{background:#153e33;border:0;}
#sidebar QLabel{color:#c6d9cf;}
#sidebar #brand{font-size:28px;font-weight:700;color:#fff;}
#sidebar #navFoot{font-size:@small@px;color:#c6d9cf;}
#sidebar QPushButton{background:transparent;color:#e0ebe3;border:2px solid transparent;border-radius:8px;text-align:left;padding:9px 10px;font-weight:600;}
#sidebar QPushButton:checked{background:#2e604e;color:#fff;border-left-color:#b6df9a;}
#sidebar QPushButton:hover{background:#285442;}
#sidebar QPushButton:focus{border-style:dashed;border-color:#e4d29e;}
#sidebar QWidget#navRow{background:transparent;}
#sidebar QLabel#navBadge{background:#c7efad;color:#173b26;border:1px solid #c7efad;border-radius:10px;min-width:24px;padding:3px 6px;font-size:@small@px;font-weight:700;}
QLabel#pageTitle,QLabel#deliveryTitle,QLabel#heroTitle{font-size:28px;font-weight:700;color:#193d2e;}
QLabel#sectionTitle,QLabel#quoteItemsTitle,QLabel#emailSectionTitle{font-size:20px;font-weight:700;}
QLabel#metricValue,QLabel#quoteTotalValue,QLabel#customerAmountValue{font-size:28px;font-weight:700;color:#194d3a;}
QLabel#muted,QLabel#eyebrow,QLabel#deliverySubtitle,QLabel#infoCaption,QLabel#customerMetricCaption,QLabel#quoteItemsHint,QLabel#emailHelp{font-size:@small@px;color:#4f6257;}
QLabel#infoValue,QLabel#customerMetricValue,QLabel#deliveryShippingTitle,QLabel#quoteTotalCaption{font-size:@body@px;font-weight:600;}
QFrame#card,QFrame#hero,QFrame#deliveryInfoCard,QFrame#quoteItemsCard,QFrame#requestNoteCard,QFrame#customerSummaryCard,QFrame#customerInfoCard,QFrame#customerFinanceCard,QFrame#emailAddressCard{background:#fff;border:1px solid #cdd9ce;border-radius:12px;}
QLabel#notice,QLabel#deliveryNotice,QLabel#emailSendStatus,QLabel#smsSendStatus{background:#e8efe6;color:#365c42;border:1px solid #cdd9ce;border-radius:8px;padding:12px;}
QFrame#serverLogFrame{background:#fff;border:1px solid #c5d4c8;border-radius:8px;}
QFrame#serverLogFrame[state="error"]{background:#fff1eb;border-color:#b9654e;}
QFrame#serverLogFrame[state="working"]{background:#fff7e4;border-color:#b08b3e;}
QLabel#serverLogTitle{font-size:@small@px;font-weight:700;color:#365c42;}
QLabel#serverLogText{font-size:@body@px;color:#263e35;background:transparent;}
QFrame#serverLogFrame[state="error"] QLabel{color:#923c28;}
QPushButton{background:#fff;color:#263e35;border:1px solid #9caf9f;border-radius:8px;min-height:24px;padding:9px 14px;font-weight:600;}
QPushButton:hover{background:#eaf1e7;border-color:#4b805d;}
QPushButton:pressed{background:#d8e6d6;}
QPushButton:focus{border:2px solid #986000;padding:8px 13px;}
QPushButton:disabled{background:#ecefea;color:#68756b;border:1px solid #cbd5ca;}
QPushButton#primary,QPushButton#syncPrimary,QPushButton#sentAction,QPushButton#emailSendPrimary,QPushButton#smsSendPrimary{background:#286547;color:#fff;border:1px solid #235b40;min-height:28px;}
QPushButton#primary:hover,QPushButton#syncPrimary:hover,QPushButton#sentAction:hover,QPushButton#emailSendPrimary:hover,QPushButton#smsSendPrimary:hover{background:#1f573b;}
QPushButton#primary:pressed,QPushButton#syncPrimary:pressed,QPushButton#sentAction:pressed,QPushButton#emailSendPrimary:pressed,QPushButton#smsSendPrimary:pressed{background:#16472e;}
QPushButton#primary:focus,QPushButton#syncPrimary:focus,QPushButton#sentAction:focus,QPushButton#emailSendPrimary:focus,QPushButton#smsSendPrimary:focus{border:2px solid #ad7400;}
QPushButton#primary:disabled,QPushButton#syncPrimary:disabled,QPushButton#sentAction:disabled,QPushButton#emailSendPrimary:disabled,QPushButton#smsSendPrimary:disabled{background:#d8e2d8;color:#546458;border-color:#bcccbc;}
QPushButton#danger{background:#fff5f1;color:#a33827;border-color:#c78370;}
QPushButton#danger:hover{background:#f8e4de;}
QPushButton#danger:focus{border:2px solid #a33827;}
QLineEdit,QComboBox,QDateEdit,QDoubleSpinBox,QSpinBox,QPlainTextEdit{background:#fff;border:1px solid #9caf9f;border-radius:8px;padding:9px 10px;selection-background-color:#c7dfd0;selection-color:#173b2e;}
QLineEdit,QComboBox,QDateEdit,QDoubleSpinBox,QSpinBox{min-height:24px;}
QLineEdit[readOnly="true"],QPlainTextEdit[readOnly="true"]{background:#f1f5ef;}
QLineEdit:focus,QComboBox:focus,QDateEdit:focus,QDoubleSpinBox:focus,QSpinBox:focus,QPlainTextEdit:focus{border:2px solid #286547;padding:8px 9px;}
QLineEdit:disabled,QComboBox:disabled,QPlainTextEdit:disabled{background:#ecefea;color:#68756b;}
QTableWidget{background:#fff;alternate-background-color:#f1f6ef;border:1px solid #a8bca9;border-radius:8px;gridline-color:#d8e3d8;selection-background-color:#c7dfd0;selection-color:#173b2e;}
QTableWidget:focus{border:2px solid #286547;}
QTableWidget::item{padding:8px;border:0;border-bottom:1px solid #dce6da;}
QTableWidget::item:selected{background:#c7dfd0;color:#173b2e;border-bottom:2px solid #286547;}
QHeaderView::section{background:#e8eee8;color:#304a3b;border:0;border-right:1px solid #cdd9ce;border-bottom:1px solid #b9cbbb;padding:9px 8px;font-weight:700;}
QGroupBox{background:#fff;border:1px solid #cdd9ce;border-radius:12px;margin-top:22px;padding:20px;font-weight:600;}
QGroupBox::title{subcontrol-origin:margin;left:16px;padding:0 6px;}
QScrollArea{border:0;background:transparent;}
QScrollArea>QWidget>QWidget{background:transparent;}
QScrollBar:vertical{background:#edf2eb;width:16px;margin:0;}
QScrollBar::handle:vertical{background:#879f8b;border-radius:7px;min-height:44px;}
QScrollBar:horizontal{background:#edf2eb;height:16px;margin:0;}
QScrollBar::handle:horizontal{background:#879f8b;border-radius:7px;min-width:44px;}
QScrollBar::add-line,QScrollBar::sub-line{width:0;height:0;}
QScrollBar::add-page,QScrollBar::sub-page{background:transparent;}
QCheckBox,QRadioButton{spacing:10px;padding:6px 0;}
QCheckBox::indicator,QRadioButton::indicator{width:22px;height:22px;}
QTextBrowser{background:#fff;border:1px solid #cdd9ce;border-radius:8px;padding:14px;}
QToolTip{background:#153e33;color:#fff;border:0;padding:10px;font-size:@body@px;}
QLabel#quoteStatusBadge,QLabel#customerStatusBadge,QLabel#channelStatusBadge{background:#e2eddf;color:#23593a;border:1px solid #b5cbb5;border-radius:8px;padding:5px 10px;font-size:@small@px;font-weight:600;}
QLabel#channelStatusBadge[done="false"]{background:#eef1ed;color:#4f6257;}
QLabel#channelStatusBadge[state="pending"]{background:#fff3d8;color:#78520d;border-color:#ba963d;}
QLabel#channelStatusBadge[state="failed"]{background:#fbe5e2;color:#a13d32;border-color:#c78370;}
QLabel#channelStatusBadge[state="success"]{background:#dcebdd;color:#23603b;}
QFrame#deliveryShippingCard,QFrame#quoteTotalBand{background:#eaf2e7;border:1px solid #bdd2bd;border-radius:12px;}
QLabel#deliveryShippingStatus{font-size:@small@px;color:#4f6257;}
QLineEdit#deliveryShippingFee{min-width:100px;max-width:160px;}
QLabel#quoteFileName{background:#eef2eb;color:#4f6257;border:1px solid #cdd9ce;border-radius:8px;padding:8px 10px;font-size:@small@px;}
#unitPriceEditor QPushButton{padding:0;min-height:0;max-height:44px;}
#unitPriceEditor QPushButton:focus{padding:0;}
#unitPriceEditor QLineEdit{padding:2px 5px;min-height:0;max-height:44px;}
#unitPriceEditor QLineEdit:focus{padding:1px 4px;}
QPushButton#filterTab:checked{background:#dcebdd;color:#194d3a;border-color:#286547;}
QLabel#navGroup{font-size:@small@px;color:#a6c2b2;padding:10px 10px 2px;}
QPushButton#compactStatus{font-size:@small@px;padding:5px 8px;min-height:28px;}
"""
    return css.replace('@body@', str(body)).replace('@small@', str(small))


class StatusMessage(QLabel):
    """Readable status with the complete original message available as details."""
    changed = Signal()

    def __init__(self, text='', parent=None):
        super().__init__(parent)
        self.setText(text)

    def setText(self, text):
        self.details = str(text)
        summary = re.sub(r'^[A-Z][A-Z _]+\s*>\s*', '', self.details)
        if 'Code.gs' in summary or 'SecureCommerce.gs' in summary:
            summary = '서버 업데이트가 필요합니다. 자세히 보기에서 연결 안내를 확인해주세요.'
        super().setText(summary)
        self.setToolTip(self.details)
        self.changed.emit()


class CustomerDelegate(QStyledItemDelegate):
    """Customer first, stable receipt/order number on a second line."""
    def paint(self, painter, option, index):
        secondary = index.data(Qt.ItemDataRole.UserRole)
        if not secondary:
            return super().paint(painter, option, index)
        opt = QStyleOptionViewItem(option)
        self.initStyleOption(opt, index)
        primary = opt.text
        opt.text = ''
        style = opt.widget.style() if opt.widget else QApplication.style()
        style.drawControl(QStyle.ControlElement.CE_ItemViewItem, opt, painter, opt.widget)
        painter.save()
        rect = option.rect.adjusted(8, 3, -8, -3)
        font = option.font
        font.setBold(True)
        painter.setFont(font)
        painter.setPen(QColor('#173b2e'))
        top = QRectF(rect.x(), rect.y(), rect.width(), rect.height() / 2)
        painter.drawText(top, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                         painter.fontMetrics().elidedText(primary, Qt.TextElideMode.ElideRight, rect.width()))
        font.setBold(False)
        font.setPixelSize(16 if QApplication.instance().property('namufarmLargeFont') else 14)
        painter.setFont(font)
        painter.setPen(QColor('#4f6257'))
        bottom = top.translated(0, rect.height() / 2)
        painter.drawText(bottom, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                         painter.fontMetrics().elidedText(str(secondary), Qt.TextElideMode.ElideRight, rect.width()))
        painter.restore()


class StatusDelegate(QStyledItemDelegate):
    def paint(self, painter, option, index):
        opt = QStyleOptionViewItem(option)
        self.initStyleOption(opt, index)
        text = opt.text
        opt.text = ''
        style = opt.widget.style() if opt.widget else QApplication.style()
        style.drawControl(QStyle.ControlElement.CE_ItemViewItem, opt, painter, opt.widget)
        pending = text in ('견적확정', '입금확인', '신규')
        canceled = text == '구매취소'
        background, foreground = (('#fff0d2', '#735016') if pending else
                                  ('#f4e4e0', '#903c2d') if canceled else ('#e2eddf', '#23593a'))
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor(background))
        rect = option.rect.adjusted(6, 10, -6, -10)
        painter.drawRoundedRect(rect, 6, 6)
        painter.setPen(QColor(foreground))
        painter.setFont(option.font)
        painter.drawText(rect, Qt.AlignmentFlag.AlignCenter,
                         painter.fontMetrics().elidedText(text, Qt.TextElideMode.ElideRight, max(1, rect.width()-8)))
        painter.restore()


def customer_reference(widget, customer_column, reference_column):
    for row in range(widget.rowCount()):
        customer, reference = widget.item(row, customer_column), widget.item(row, reference_column)
        if customer is not None and reference is not None:
            customer.setData(Qt.ItemDataRole.UserRole, reference.text())
            customer.setToolTip(customer.text() + '\n' + reference.text())


class DataTable(QTableWidget):
    """Keep visible business columns within the viewport; logical indices stay stable."""
    def __init__(self, rows, columns):
        super().__init__(rows, columns)
        self._weights = ()
        self._fitting = False
        self.viewport().installEventFilter(self)
        row_height = 64 if QApplication.instance().property("namufarmLargeFont") else 56
        self.verticalHeader().setMinimumSectionSize(row_height)
        self.verticalHeader().setDefaultSectionSize(row_height)

    def set_column_layout(self, columns):
        self._weights = tuple(columns)
        order = [column for column, _weight in self._weights]
        header = self.horizontalHeader()
        header.setStretchLastSection(False)
        header.setMinimumSectionSize(60)
        for column in range(self.columnCount()):
            self.setColumnHidden(column, column not in order)
            header.setSectionResizeMode(column, QHeaderView.ResizeMode.Interactive)
        for position, column in enumerate(order):
            header.moveSection(header.visualIndex(column), position)
        self.fit_columns()

    def fit_columns(self):
        if not self._weights or self._fitting:
            return
        self._fitting = True
        try:
            available = max(1, self.viewport().width())
            total = sum(weight for _column, weight in self._weights)
            used = 0
            for position, (column, weight) in enumerate(self._weights):
                width = (available - used) if position == len(self._weights) - 1 else int(available * weight / total)
                width = max(self.horizontalHeader().minimumSectionSize(), width)
                self.setColumnWidth(column, width)
                used += width
        finally:
            self._fitting = False

    def eventFilter(self, watched, event):
        if watched is self.viewport() and event.type() == QEvent.Type.Resize:
            self.fit_columns()
        return super().eventFilter(watched, event)


def align_table(widget):
    for row in range(widget.rowCount()):
        for column in range(widget.columnCount()):
            item = widget.item(row, column)
            if item is None:
                continue
            heading = widget.horizontalHeaderItem(column).text()
            if any(word in heading for word in ('금액', '단가', '수량', '매출', '이익', '비용')):
                alignment = Qt.AlignmentFlag.AlignRight
            elif any(word in heading for word in ('상태', '일자', '작성일', '접수일', '주문일', '규격', '견적서')):
                alignment = Qt.AlignmentFlag.AlignHCenter
            else:
                alignment = Qt.AlignmentFlag.AlignLeft
            item.setTextAlignment(alignment | Qt.AlignmentFlag.AlignVCenter)
            item.setToolTip(item.text())


def fit_window(window, width, height):
    screen = window.screen() or QApplication.primaryScreen()
    if screen:
        available = screen.availableGeometry()
        width = min(width, max(480, available.width() - 40))
        height = min(height, max(400, available.height() - 70))
    window.resize(width, height)


def scroll_dialog(dialog, layout):
    """Keep the first header and last action row visible while the body scrolls."""
    if layout.count() < 3:
        return
    body = QWidget()
    content = QVBoxLayout(body)
    content.setContentsMargins(0, 0, 0, 0)
    content.setSpacing(16)
    while layout.count() > 2:
        stretch = layout.stretch(1)
        item = layout.takeAt(1)
        if item.widget() is not None:
            content.addWidget(item.widget(), stretch, item.alignment())
        elif item.layout() is not None:
            nested = item.layout()
            nested.setParent(None)
            content.addLayout(nested, stretch)
        else:
            content.addItem(item)
    scroll = QScrollArea()
    scroll.setWidgetResizable(True)
    scroll.setWidget(body)
    scroll.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
    layout.insertWidget(1, scroll, 1)
    fit_window(dialog, dialog.width(), dialog.height())


def scroll_page(page):
    scroll = QScrollArea()
    scroll.setWidgetResizable(True)
    scroll.setWidget(page)
    return scroll
