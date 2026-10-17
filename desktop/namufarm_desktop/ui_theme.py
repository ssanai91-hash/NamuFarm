"""Shared desktop presentation; no storage or network operations."""
import re

from PySide6.QtCore import QEvent, Qt, QRectF, QTimer
from PySide6.QtGui import QColor, QPainter
from PySide6.QtWidgets import (QApplication, QHeaderView, QLabel, QScrollArea,
                               QSizePolicy, QTableWidget, QVBoxLayout, QWidget,
                               QStyledItemDelegate, QStyleOptionViewItem, QStyle, QPushButton)


def workspace_style(large=False):
    body, small = (18, 16) if large else (16, 14)
    css = """
QWidget{font-family:'Malgun Gothic','NanumGothic',sans-serif;font-size:@body@px;color:#24313a;}
QMainWindow,#workspace,QDialog{background:#f4f6f7;}
#sidebar{background:#193c32;border:0;}
#sidebar QLabel{color:#c6d9cf;}
#sidebar QFrame#brandPanel{background:#285446;border:1px solid #4a7761;border-radius:10px;}
#sidebar #brand{font-size:30px;font-weight:700;color:#fff;background:transparent;}
#sidebar QFrame#sidebarClock{background:#15342b;border:0;border-top:1px solid #4b6d5e;border-radius:8px;}
#sidebar QLabel#sidebarDate{font-size:18px;color:#d0e0d7;background:transparent;}
#sidebar QLabel#sidebarTime{font-size:26px;font-weight:600;color:#fff;background:transparent;}
#sidebar #navFoot{font-size:@small@px;color:#c6d9cf;}
#sidebar QPushButton{background:transparent;color:#e0ebe3;border:2px solid transparent;border-radius:8px;text-align:left;padding:10px 14px;font-size:@nav@px;min-height:@navheight@px;max-height:@navheight@px;font-weight:600;}
#sidebar QPushButton:checked{background:#2e604e;color:#fff;border-left-color:#b6df9a;}
#sidebar QPushButton:hover{background:#285442;}
#sidebar QPushButton[keyboardFocus="true"]{border-style:dashed;border-color:#e4d29e;}
#sidebar QWidget#navRow{background:transparent;}
#sidebar QLabel#navBadge{background:#c7efad;color:#173b26;border:1px solid #c7efad;border-radius:10px;min-width:24px;padding:3px 6px;font-size:@small@px;font-weight:700;}
QFrame#pageSummary{background:#193c32;border:1px solid #193c32;border-radius:8px;}
QLabel#summaryTitle{font-size:22px;font-weight:700;color:#fff;background:transparent;}
QLabel#summaryCount{font-size:48px;font-weight:700;color:#fff;background:transparent;}
QLabel#summaryCount[longCount="true"]{font-size:32px;}
QLabel#summaryCountUnit{font-size:22px;font-weight:600;color:#d0e0d7;background:transparent;padding-bottom:6px;}
QLabel#pageTitle,QLabel#deliveryTitle,QLabel#heroTitle{font-size:24px;font-weight:700;color:#24313a;}
QLabel#sectionTitle,QLabel#quoteItemsTitle,QLabel#emailSectionTitle{font-size:18px;font-weight:700;}
QLabel#metricValue,QLabel#quoteTotalValue,QLabel#customerAmountValue{font-size:28px;font-weight:700;color:#2d6a4f;}
QLabel#muted,QLabel#eyebrow,QLabel#deliverySubtitle,QLabel#infoCaption,QLabel#customerMetricCaption,QLabel#quoteItemsHint,QLabel#emailHelp{font-size:@small@px;color:#5c6b73;}
QLabel#infoValue,QLabel#customerMetricValue,QLabel#deliveryShippingTitle,QLabel#quoteTotalCaption{font-size:@body@px;font-weight:600;}
QFrame#card,QFrame#hero,QFrame#deliveryInfoCard,QFrame#quoteItemsCard,QFrame#requestNoteCard,QFrame#customerSummaryCard,QFrame#customerInfoCard,QFrame#customerFinanceCard,QFrame#emailAddressCard{background:#fff;border:1px solid #ccd5d9;border-radius:12px;}
QLabel#notice,QLabel#deliveryNotice,QLabel#emailSendStatus,QLabel#smsSendStatus{background:#eef3f1;color:#38564b;border:1px solid #ccd5d9;border-radius:8px;padding:12px;}
QFrame#serverLogFrame{background:#fff;border:1px solid #ccd5d9;border-radius:8px;}
QFrame#serverLogFrame[state="working"]{background:#fff7e4;border-color:#b08b3e;}
QFrame#serverLogFrame[state="working"][pulse="true"]{background:#ffe4aa;border-color:#c18d29;}
QFrame#serverLogFrame[state="success"]{background:#e4efff;border-color:#719bd2;}
QFrame#serverLogFrame[state="error"]{background:#fce9e6;border-color:#bf685e;}
QLabel#serverLogTitle{font-size:@small@px;font-weight:700;color:#38564b;}
QLabel#serverLogText{font-size:@body@px;color:#24313a;background:transparent;}
QFrame#serverLogFrame[state="success"] QLabel{color:#24548b;}
QFrame#serverLogFrame[state="error"] QLabel{color:#9e3228;}
QPushButton{background:#fff;color:#24313a;border:1px solid #7f8e95;border-radius:8px;min-height:24px;max-height:24px;padding:9px 14px;font-weight:600;}
QPushButton:hover{background:#edf3ef;border-color:#4b805d;}
QPushButton:pressed{background:#dcebe2;}
QPushButton:focus{border:2px solid #986000;padding:8px 13px;}
QPushButton:disabled{background:#eef1f2;color:#718087;border:1px solid #ccd5d9;}
QPushButton#primary,QPushButton#syncPrimary,QPushButton#sentAction,QPushButton#emailSendPrimary,QPushButton#smsSendPrimary{background:#2d6a4f;color:#fff;border:1px solid #255a43;}
QPushButton#primary:hover,QPushButton#syncPrimary:hover,QPushButton#sentAction:hover,QPushButton#emailSendPrimary:hover,QPushButton#smsSendPrimary:hover{background:#1f573b;}
QPushButton#primary:pressed,QPushButton#syncPrimary:pressed,QPushButton#sentAction:pressed,QPushButton#emailSendPrimary:pressed,QPushButton#smsSendPrimary:pressed{background:#16472e;}
QPushButton#primary:focus,QPushButton#syncPrimary:focus,QPushButton#sentAction:focus,QPushButton#emailSendPrimary:focus,QPushButton#smsSendPrimary:focus{border:2px solid #ad7400;}
QPushButton#primary:disabled,QPushButton#syncPrimary:disabled,QPushButton#sentAction:disabled,QPushButton#emailSendPrimary:disabled,QPushButton#smsSendPrimary:disabled{background:#e1e9e4;color:#677970;border-color:#c4d2c9;}
QPushButton#danger{background:#fff5f1;color:#a33827;border-color:#c78370;}
QPushButton#requestDelete{background:#fcedea;color:#9b3529;border:1px solid #d7aaa2;}
QPushButton#requestDelete:hover{background:#f2d5ce;border-color:#bd7a6e;}
QPushButton#requestDelete:pressed{background:#eac2b8;}
QPushButton#requestDelete:focus{border:2px solid #a33827;}
QPushButton#requestDelete:disabled{background:#f1e7e4;color:#8a706a;border-color:#d7c3bd;}
QPushButton#requestQuote{background:#e8f2ec;color:#23593a;border:1px solid #a8c6ab;}
QPushButton#requestQuote:hover{background:#d3e6d4;border-color:#6b9d74;}
QPushButton#requestQuote:pressed{background:#bdd9c0;}
QPushButton#requestQuote:focus{border:2px solid #2d6a4f;}
QPushButton#requestQuote:disabled{background:#f1f4f5;color:#758173;border-color:#ccd5d9;}
QPushButton#danger:hover{background:#f8e4de;}
QPushButton#danger:focus{border:2px solid #a33827;}
QLineEdit,QComboBox,QDateEdit,QDoubleSpinBox,QSpinBox,QPlainTextEdit{background:#fff;border:1px solid #7f8e95;border-radius:8px;padding:9px 10px;selection-background-color:#e0eee6;selection-color:#24313a;}
QLineEdit,QComboBox,QDateEdit,QDoubleSpinBox,QSpinBox{min-height:24px;}
QLineEdit[readOnly="true"],QPlainTextEdit[readOnly="true"]{background:#f4f6f7;}
QLineEdit:focus,QComboBox:focus,QDateEdit:focus,QDoubleSpinBox:focus,QSpinBox:focus,QPlainTextEdit:focus{border:2px solid #2d6a4f;padding:8px 9px;}
QLineEdit:disabled,QComboBox:disabled,QPlainTextEdit:disabled{background:#eef1f2;color:#718087;}
QTableWidget{background:#fff;alternate-background-color:#f7f9fa;border:1px solid #ccd5d9;border-radius:8px;gridline-color:#e2e8eb;selection-background-color:#e0eee6;selection-color:#24313a;}
QTableWidget:focus{border-color:#2d6a4f;}
QTableWidget::item{padding:8px;border:0;border-bottom:1px solid #e2e8eb;}
QTableWidget::item:selected{background:#e0eee6;color:#24313a;border:0;border-bottom:1px solid #c4d8cc;}
QHeaderView::section{background:#eef2f3;color:#34434b;border:0;border-right:1px solid #ccd5d9;border-bottom:1px solid #ccd5d9;padding:9px 8px;font-weight:700;}
QGroupBox{background:#fff;border:1px solid #ccd5d9;border-radius:12px;margin-top:20px;padding:16px;font-weight:600;}
QGroupBox::title{subcontrol-origin:margin;left:16px;padding:0 6px;}
QScrollArea{border:0;background:transparent;}
QScrollArea>QWidget>QWidget{background:transparent;}
QScrollBar:vertical{background:#f1f4f5;width:16px;margin:0;}
QScrollBar::handle:vertical{background:#91a3ab;border-radius:7px;min-height:44px;}
QScrollBar:horizontal{background:#f1f4f5;height:16px;margin:0;}
QScrollBar::handle:horizontal{background:#91a3ab;border-radius:7px;min-width:44px;}
QScrollBar::add-line,QScrollBar::sub-line{width:0;height:0;}
QScrollBar::add-page,QScrollBar::sub-page{background:transparent;}
QCheckBox,QRadioButton{spacing:10px;padding:6px 0;}
QCheckBox::indicator,QRadioButton::indicator{width:22px;height:22px;}
QTextBrowser{background:#fff;border:1px solid #ccd5d9;border-radius:8px;padding:14px;}
QToolTip{background:#193c32;color:#fff;border:0;padding:10px;font-size:@body@px;}
QLabel#quoteStatusBadge,QLabel#customerStatusBadge,QLabel#channelStatusBadge{background:#e8f2ec;color:#23593a;border:1px solid #c6dace;border-radius:8px;padding:5px 10px;font-size:@small@px;font-weight:600;}
QLabel#channelStatusBadge[done="false"]{background:#f1f4f5;color:#5c6b73;}
QLabel#channelStatusBadge[state="pending"]{background:#fff3d8;color:#78520d;border-color:#ba963d;}
QLabel#channelStatusBadge[state="failed"]{background:#fbe5e2;color:#a13d32;border-color:#c78370;}
QLabel#channelStatusBadge[state="success"]{background:#e8f2ec;color:#23603b;}
QFrame#deliveryShippingCard,QFrame#quoteTotalBand{background:#f3f7f5;border:1px solid #c6dace;border-radius:12px;}
QLabel#deliveryShippingStatus{font-size:@small@px;color:#5c6b73;}
QLineEdit#deliveryShippingFee{min-width:100px;max-width:160px;}
QLabel#quoteFileName{background:#f2f5f6;color:#5c6b73;border:1px solid #ccd5d9;border-radius:8px;padding:8px 10px;font-size:@small@px;}
#unitPriceEditor QPushButton{padding:0;min-height:0;max-height:44px;}
#unitPriceEditor QPushButton:focus{padding:0;}
#unitPriceEditor QLineEdit{padding:2px 5px;min-height:0;max-height:44px;}
#unitPriceEditor QLineEdit:focus{padding:1px 4px;}
QPushButton#filterTab:checked{background:#e8f2ec;color:#2d6a4f;border-color:#2d6a4f;}
QLabel#emptyState{color:#5c6b73;background:transparent;padding:24px;font-size:@body@px;}
QPushButton#disclosure{text-align:left;background:#f4f6f7;border-color:#ccd5d9;}
QDialog#quoteEditor QLineEdit[readOnly="true"]{min-height:@readonly@px;max-height:@readonly@px;}
"""
    return (css.replace('@body@', str(body)).replace('@small@', str(small))
            .replace('@nav@', '22' if large else '20').replace('@navheight@', '36' if large else '32')
            .replace('@readonly@', '24' if large else '20'))


def quote_workbench_style(large=False):
    """Selected forest-green quotation workbench; scoped away from other dialogs."""
    return """
QDialog#quoteWorkbench{background:#ffffff;color:#24313a;}
QDialog#quoteWorkbench QLabel{font-size:@body@px;background:transparent;border:0;padding:0;color:#24313a;}
QDialog#quoteWorkbench QFrame#workbenchHeader{background:#193c32;border:0;}
QDialog#quoteWorkbench QLabel#workbenchBrand{font-size:20px;font-weight:600;color:#e3eee8;}
QDialog#quoteWorkbench QLabel#workbenchTitle{font-size:@title@px;font-weight:700;color:#ffffff;}
QDialog#quoteWorkbench QLabel#workbenchReference{font-size:@caption@px;color:#e3eee8;}
QDialog#quoteWorkbench QScrollArea#workbenchCustomerScroll{border:0;border-right:1px solid #bfd5c9;background:#edf4ef;}
QDialog#quoteWorkbench QWidget#workbenchCustomerRail{background:#edf4ef;}
QDialog#quoteWorkbench QLabel#workbenchSectionTitle{font-size:@section@px;font-weight:700;color:#193c32;}
QDialog#quoteWorkbench QLabel#workbenchCaption{font-size:@caption@px;font-weight:600;color:#52645c;}
QDialog#quoteWorkbench QLabel#workbenchCustomerName{font-size:28px;font-weight:700;color:#193c32;}
QDialog#quoteWorkbench QLabel#workbenchInfo{font-size:@body@px;color:#24313a;}
QDialog#quoteWorkbench QLabel#workbenchFormal{font-size:@caption@px;background:#e4ece7;color:#52645c;border-radius:6px;padding:8px 12px;}
QDialog#quoteWorkbench QLabel#workbenchFormal[requested="true"]{background:#ffe2a5;color:#6b480d;}
QDialog#quoteWorkbench QFrame#workbenchCustomerRule{background:#c5d9ce;border:0;}
QDialog#quoteWorkbench QTableWidget#workbenchItems{background:#ffffff;alternate-background-color:#f0f6f2;color:#24313a;font-size:@body@px;border:1px solid #cfddd4;border-radius:6px;selection-background-color:#e0eee5;selection-color:#193c32;}
QDialog#quoteWorkbench QTableWidget#workbenchItems::item{padding:4px 12px;border-bottom:1px solid #e0e9e3;}
QDialog#quoteWorkbench QTableWidget#workbenchItems QHeaderView::section{background:#2c6852;color:#ffffff;font-size:@body@px;font-weight:700;padding:8px 10px;border:0;border-right:1px solid #548470;}
QDialog#quoteWorkbench QWidget#unitPriceEditor{background:transparent;}
QDialog#quoteWorkbench QWidget#unitPriceEditor QPushButton{min-height:0;max-height:42px;padding:0;border:1px solid #a8bcb0;border-radius:6px;background:#f7faf8;color:#193c32;font-size:22px;}
QDialog#quoteWorkbench QWidget#unitPriceEditor QPushButton:hover{background:#dcece2;}
QDialog#quoteWorkbench QWidget#unitPriceEditor QLineEdit{min-height:0;max-height:42px;padding:0 8px;border:1px solid #a8bcb0;border-radius:6px;background:#ffffff;font-size:@body@px;}
QDialog#quoteWorkbench QWidget#unitPriceEditor QLineEdit:focus{max-height:40px;border:2px solid #a36d17;padding:0 7px;}
QDialog#quoteWorkbench QFrame#workbenchTotalRule{background:#245f46;border:0;}
QDialog#quoteWorkbench QFrame#workbenchSummary{background:#edf4ef;border:0;border-radius:8px;}
QDialog#quoteWorkbench QLabel#workbenchSumTitle{font-size:26px;font-weight:700;color:#193c32;}
QDialog#quoteWorkbench QLabel#workbenchAmountDetail{font-size:@caption@px;color:#52645c;}
QDialog#quoteWorkbench QLabel#workbenchTotal{font-size:@total@px;font-weight:700;color:#193c32;}
QDialog#quoteWorkbench QPlainTextEdit{font-size:@body@px;background:#ffffff;border:1px solid #b8c9bf;border-radius:6px;padding:8px 12px;}
QDialog#quoteWorkbench QFrame#workbenchFooter{background:#ffffff;border:0;border-top:1px solid #cfddd4;}
QDialog#quoteWorkbench QPushButton{font-size:@body@px;min-height:46px;max-height:46px;padding:0 18px;border-radius:6px;}
QDialog#quoteWorkbench QPushButton#primary{background:#25634b;color:#ffffff;border:1px solid #20553f;font-weight:700;}
QDialog#quoteWorkbench QPushButton#primary:hover{background:#194c37;}
QDialog#quoteWorkbench QPushButton#primary:disabled{background:#e1e9e4;color:#6a7b71;border-color:#bdcec3;}
""".replace('@body@', '22' if large else '20').replace('@caption@', '20' if large else '18').replace('@title@', '32' if large else '30').replace('@section@', '26' if large else '24').replace('@total@', '40' if large else '36')


def quote_delivery_style(large=False, compact=False):
    """Scoped to the selected quotation dialog; other workspace screens keep their theme."""
    body, caption = (20, 18) if large else (18, 16)
    return """
QDialog#quoteDeliveryDialog,QWidget#deliveryBody{background:#ffffff;}
QDialog#quoteDeliveryDialog QLabel{font-size:@body@px;background:transparent;border:0;padding:0;}
QDialog#quoteDeliveryDialog QFrame#deliveryHeader{border:0;border-bottom:2px solid #2d6a4f;}
QDialog#quoteDeliveryDialog QLabel#deliveryCustomer{font-size:@customer@px;font-weight:700;color:#24313a;}
QDialog#quoteDeliveryDialog QLabel#deliveryTotal{font-size:@total@px;font-weight:700;color:#2d6a4f;}
QDialog#quoteDeliveryDialog QLabel#deliveryReference{font-size:@body@px;color:#5c6b73;}
QDialog#quoteDeliveryDialog QLabel#deliveryCaption,QDialog#quoteDeliveryDialog QLabel#deliveryPdfName{font-size:@caption@px;color:#5c6b73;}
QDialog#quoteDeliveryDialog QLabel#deliveryInfoValue{font-size:@body@px;font-weight:600;}
QDialog#quoteDeliveryDialog QLabel#deliverySectionTitle{font-size:@body@px;font-weight:700;}
QDialog#quoteDeliveryDialog QFrame#deliveryShippingStrip{background:#f4f6f7;border:0;border-radius:6px;}
QDialog#quoteDeliveryDialog QFrame#deliveryVerticalRule{background:#d5dee2;border:0;}
QDialog#quoteDeliveryDialog QFrame#deliverySectionRule{background:#4d6f61;border:0;}
QDialog#quoteDeliveryDialog QFrame#deliveryFooter{background:#fff;border:0;border-top:2px solid #4d6f61;}
QDialog#quoteDeliveryDialog QPushButton{font-size:@body@px;min-height:46px;max-height:46px;padding:0 12px;border-radius:6px;}
QDialog#quoteDeliveryDialog QPushButton:focus{padding:0 11px;border:2px solid #986000;}
QDialog#quoteDeliveryDialog QPushButton#smsAction{background:#2d6a4f;color:#fff;border-color:#255a43;}
QDialog#quoteDeliveryDialog QPushButton#smsAction:hover{background:#1f573b;}
QDialog#quoteDeliveryDialog QPushButton#smsAction:disabled{background:#e1e9e4;color:#677970;border-color:#c4d2c9;}
QDialog#quoteDeliveryDialog QPushButton#sentAction{background:#fff;color:#2d6a4f;border:1px solid #2d6a4f;}
QDialog#quoteDeliveryDialog QPushButton#sentAction:hover{background:#edf4ef;}
QDialog#quoteDeliveryDialog QLabel#channelStatusBadge{font-size:@caption@px;background:#f1f4f5;border:0;border-radius:6px;padding:2px 8px;color:#5c6b73;}
QDialog#quoteDeliveryDialog QLabel#channelStatusBadge[state="pending"]{background:#fff3d8;color:#78520d;}
QDialog#quoteDeliveryDialog QLabel#channelStatusBadge[state="failed"]{background:#fbe5e2;color:#a13d32;}
QDialog#quoteDeliveryDialog QLabel#channelStatusBadge[state="success"]{background:#e8f2ec;color:#23603b;}
QDialog#quoteDeliveryDialog QLineEdit{font-size:@body@px;min-height:24px;max-height:24px;padding:9px 10px;border-radius:6px;}
QDialog#quoteDeliveryDialog QLineEdit#deliveryShippingFee{min-width:100px;max-width:100px;}
QDialog#quoteDeliveryDialog QRadioButton{font-size:@body@px;}
QDialog#quoteDeliveryDialog QTableWidget{font-size:@body@px;border-radius:6px;}
QDialog#quoteDeliveryDialog QTableWidget#deliveryItems QHeaderView::section{font-size:@body@px;background:#d7e5df;color:#25483b;}
""".replace('@body@', str(body)).replace('@caption@', str(caption)).replace('@customer@', '30' if compact else '34').replace('@total@', '34' if compact else '40')


class StatusMessage(QLabel):
    """Readable status with the complete original message available as details."""
    def __init__(self, text='', parent=None):
        super().__init__(parent)
        self.setText(text)

    def setText(self, text):
        self.details = str(text)
        summary = re.sub(r'^[A-Z][A-Z _]+\s*>\s*', '', self.details)
        if 'Code.gs' in summary or 'SecureCommerce.gs' in summary:
            summary = '서버 업데이트가 필요합니다. 연결·설정에서 서버 정보를 확인해주세요.'
        super().setText(summary)
        self.setToolTip(self.details)


class RowSelectionDelegate(QStyledItemDelegate):
    """Use the row highlight and table focus border instead of cell focus boxes."""
    def initStyleOption(self, option, index):
        super().initStyleOption(option, index)
        option.state &= ~QStyle.StateFlag.State_HasFocus


class NavigationButton(QPushButton):
    """Keyboard focus stays visible without making mouse focus look selected."""
    def __init__(self, text, parent=None):
        super().__init__(text, parent)
        self.setProperty("keyboardFocus", False)

    def focusInEvent(self, event):
        self.setProperty("keyboardFocus", event.reason() in (
            Qt.FocusReason.TabFocusReason, Qt.FocusReason.BacktabFocusReason,
            Qt.FocusReason.ShortcutFocusReason))
        self.style().unpolish(self)
        self.style().polish(self)
        super().focusInEvent(event)

    def focusOutEvent(self, event):
        self.setProperty("keyboardFocus", False)
        self.style().unpolish(self)
        self.style().polish(self)
        super().focusOutEvent(event)


class CompactDateDelegate(RowSelectionDelegate):
    def initStyleOption(self, option, index):
        super().initStyleOption(option, index)
        if len(option.text) >= 16 and option.text[4:5] == '-':
            option.text = option.text[5:16].replace('-', '.')


class CustomerDelegate(RowSelectionDelegate):
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
        painter.setPen(QColor('#24313a'))
        top = QRectF(rect.x(), rect.y(), rect.width(), rect.height() / 2)
        painter.drawText(top, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                         painter.fontMetrics().elidedText(primary, Qt.TextElideMode.ElideRight, rect.width()))
        font.setBold(False)
        font.setPixelSize(16 if QApplication.instance().property('namufarmLargeFont') else 14)
        painter.setFont(font)
        painter.setPen(QColor('#5c6b73'))
        bottom = top.translated(0, rect.height() / 2)
        painter.drawText(bottom, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                         painter.fontMetrics().elidedText(str(secondary), Qt.TextElideMode.ElideRight, rect.width()))
        painter.restore()


class StatusDelegate(RowSelectionDelegate):
    def paint(self, painter, option, index):
        opt = QStyleOptionViewItem(option)
        self.initStyleOption(opt, index)
        text = opt.text
        opt.text = ''
        style = opt.widget.style() if opt.widget else QApplication.style()
        style.drawControl(QStyle.ControlElement.CE_ItemViewItem, opt, painter, opt.widget)
        pending = text in ('견적확정', '입금확인', '신규')
        canceled = text == '구매취소'
        background, foreground = (('#fff3da', '#805a17') if pending else
                                  ('#fcedea', '#9c3c2e') if canceled else ('#e8f2ec', '#23593a'))
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor(background))
        width = min(option.rect.width() - 12, option.fontMetrics.horizontalAdvance(text) + 24)
        rect = QRectF(0, 0, max(1, width), 30)
        rect.moveCenter(QRectF(option.rect).center())
        painter.drawRoundedRect(rect, 6, 6)
        painter.setPen(QColor(foreground))
        painter.setFont(option.font)
        painter.drawText(rect, Qt.AlignmentFlag.AlignCenter,
                         painter.fontMetrics().elidedText(text, Qt.TextElideMode.ElideRight, max(1, int(rect.width())-8)))
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
        self.setItemDelegate(RowSelectionDelegate(self))
        self._empty_message = '표시할 자료가 없습니다.'
        self._empty_label = QLabel(self.viewport())
        self._empty_label.setObjectName("emptyState")
        self._empty_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._empty_label.setWordWrap(True)
        self._empty_label.setTextFormat(Qt.TextFormat.PlainText)
        self._empty_label.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self._weights = ()
        self._fitting = False
        self.viewport().installEventFilter(self)
        row_height = 64 if QApplication.instance().property("namufarmLargeFont") else 56
        self.verticalHeader().setMinimumSectionSize(row_height)
        self.verticalHeader().setDefaultSectionSize(row_height)
        self.model().rowsInserted.connect(self.update_empty_state)
        self.model().rowsRemoved.connect(self.update_empty_state)
        self.model().modelReset.connect(self.update_empty_state)

    def set_column_layout(self, columns):
        self._weights = tuple((c[0], c[1], c[2] if len(c) > 2 else 60) for c in columns)
        order = [column for column, _weight, _minimum in self._weights]
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
            minimum_total = sum(minimum for _column, _weight, minimum in self._weights)
            total = sum(weight for _column, weight, _minimum in self._weights)
            extra = max(0, available - minimum_total)
            used = 0
            for position, (column, weight, minimum) in enumerate(self._weights):
                width = minimum + int(extra * weight / total) if total else minimum
                if position == len(self._weights) - 1:
                    width = max(minimum, available - used)
                self.setColumnWidth(column, width)
                used += width
        finally:
            self._fitting = False

    def eventFilter(self, watched, event):
        if watched is self.viewport() and event.type() == QEvent.Type.Resize:
            self.fit_columns()
            self.update_empty_state()
        return super().eventFilter(watched, event)

    def set_empty_message(self, text):
        self._empty_message = text
        self.update_empty_state()

    def setRowCount(self, count):
        super().setRowCount(count)
        if hasattr(self, "_empty_label"):
            self.update_empty_state()

    def update_empty_state(self, *_args):
        self._empty_label.setText(self._empty_message)
        self._empty_label.setGeometry(self.viewport().rect().adjusted(16, 8, -16, -8))
        self._empty_label.setVisible(self.rowCount() == 0)
        self._empty_label.raise_()


def align_table(widget):
    for row in range(widget.rowCount()):
        for column in range(widget.columnCount()):
            item = widget.item(row, column)
            if item is None:
                continue
            heading = widget.horizontalHeaderItem(column).text()
            if any(word in heading for word in ('금액', '단가', '수량', '매출', '이익', '비용', '원가', '세액', '건수')):
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
    content.setSpacing(8)
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


def size_table_to_rows(table, max_rows=4):
    """Short item lists use their real height; longer lists retain one scroll."""
    table.setProperty("visibleItemRows", max_rows)
    def resize():
        if not table.isVisible():
            # sizeHint is still usable before a dialog is shown.
            header = table.horizontalHeader().sizeHint().height()
        else:
            header = table.horizontalHeader().height()
        rows = min(max(1, table.rowCount()), max_rows)
        table.setFixedHeight(header + rows * table.verticalHeader().defaultSectionSize() + 4)
    resize()
    QTimer.singleShot(0, resize)


def collapsible(title, content, expanded=False):
    from .ui_icons import set_button_icon
    section = QWidget()
    layout = QVBoxLayout(section)
    layout.setContentsMargins(0, 0, 0, 0)
    layout.setSpacing(8)
    toggle = QPushButton(title)
    toggle.setObjectName("disclosure")
    toggle.setCheckable(True)
    toggle.setChecked(expanded)
    toggle.setAccessibleName(title + " 펼치기 또는 접기")
    set_button_icon(toggle, "chevron", "#5c6b73")
    content.setVisible(expanded)
    toggle.toggled.connect(content.setVisible)
    layout.addWidget(toggle)
    layout.addWidget(content)
    return section
