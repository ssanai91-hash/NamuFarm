"""Shared desktop presentation; no storage or network operations."""
import re

from PySide6.QtCore import QEvent, Qt, QRectF, QTimer
from PySide6.QtGui import QColor, QPainter, QPalette
from PySide6.QtWidgets import (QApplication, QHeaderView, QLabel, QLineEdit, QScrollArea,
                               QSizePolicy, QTableWidget, QVBoxLayout, QWidget,
                               QStyledItemDelegate, QStyleOptionViewItem, QStyle, QPushButton)


TOKENS = {
    "sidebar": "#193C32", "primary": "#2D6A4F", "primary-hover": "#1F573B", "primary-pressed": "#16472E",
    "accent": "#B6DF9A", "danger": "#B3261E", "text": "#1F2A26", "muted": "#5B6B63",
    "border": "#DFE5E2", "input-border": "#B9C7C0", "page-bg": "#F4F6F5", "card": "#FFFFFF",
    "table-head": "#F2F5F3", "row-selected": "#E8F3EC", "nav-selected": "#2A5C49", "nav-caption": "#8FB3A0",
    "waiting": "#F2D38A", "working-bg": "#FFF7E4", "new-fg": "#1D4F91", "new-bg": "#E7F0FB",
    "quote-fg": "#8A5300", "quote-bg": "#FDF1DC", "paid-fg": "#1E6B3F", "paid-bg": "#E3F2E8",
    "ready-fg": "#0B5F56", "ready-bg": "#DFF1EF", "tracking-fg": "#5B3C9E", "tracking-bg": "#EFE9FB",
    "done-fg": "#46545C", "done-bg": "#ECEFF1", "cancel-fg": "#9A2A22", "cancel-bg": "#FBE9E7",
}


def status_badge(stage):
    """Single display-only vocabulary for delegates and detail panels; no status mutation."""
    aliases = {"신규": "신규 요청", "구매확정": "입금확인", "판매완료": "배송완료", "주문취소": "구매취소"}
    text = aliases.get(stage, stage)
    key = {"신규 요청": "new", "견적확정": "quote", "견적발송완료": "quote", "입금확인": "paid",
           "배송준비": "ready", "송장등록": "tracking", "배송완료": "done", "구매취소": "cancel"}.get(text, "done")
    size = 15 if QApplication.instance() and QApplication.instance().property("namufarmLargeFont") else 13
    fg, bg = TOKENS[key + "-fg"], TOKENS[key + "-bg"]
    return {"text": text, "foreground": fg, "background": bg,
            "style": f"color:{fg};background:{bg};border:0;border-radius:8px;padding:4px 10px;font-size:{size}px;font-weight:700;"}


def stage_label(stage, parent=None):
    badge = status_badge(stage)
    result = QLabel(badge["text"], parent)
    result.setObjectName("stageBadge")
    result.setStyleSheet(badge["style"])
    return result


def _theme(css, large=False):
    values = {**TOKENS, **{key: str(value + (2 if large else 0)) for key, value in
              {"body": 15, "small": 13, "title": 22, "section": 16, "action": 17, "total": 26, "detail": 20, "brand": 18}.items()}}
    for key, value in values.items():
        css = css.replace("@" + key + "@", value)
    return css


def workspace_style(large=False, compact=False):
    # Compact layouts change geometry, never reduce the readable type scale.
    return _theme("""
QWidget{font-family:'Malgun Gothic','NanumGothic',sans-serif;font-size:@body@px;color:@text@;}
QMainWindow,#workspace,QDialog{background:@page-bg@;}
#sidebar{background:@sidebar@;border:0;}
#sidebar QLabel{color:@card@;background:transparent;}
#sidebar QLabel#brand{font-size:@brand@px;font-weight:700;}
#sidebar QLabel#navGroup,#sidebar QLabel#navFoot{font-size:@small@px;color:@nav-caption@;}
#sidebar QPushButton{background:transparent;color:@card@;border:0;border-left:3px solid transparent;border-radius:8px;text-align:left;padding:0 8px;min-height:44px;max-height:44px;font-size:@body@px;font-weight:600;}
#sidebar QPushButton:checked{background:@nav-selected@;border-left-color:@accent@;}
#sidebar QPushButton:hover{background:@nav-selected@;}
#sidebar QPushButton[keyboardFocus="true"]{border:2px dashed @accent@;}
#sidebar #navRow{background:transparent;}
#sidebar QLabel#navBadge{font-size:@small@px;font-weight:700;background:@border@;color:@text@;border-radius:8px;padding:2px 8px;}
#sidebar QLabel#navBadge[kind="new"]{background:@accent@;}
#sidebar QLabel#navBadge[kind="inquiry"]{background:@waiting@;}
QFrame#pageHeader{background:@card@;border:0;border-bottom:1px solid @border@;}
QLabel#pageTitle,QLabel#summaryTitle,QLabel#heroTitle,QLabel#deliveryTitle{font-size:@title@px;font-weight:700;}
QLabel#sectionTitle,QLabel#quoteItemsTitle,QLabel#emailSectionTitle{font-size:@section@px;font-weight:700;}
QLabel#summaryCount,QLabel#summaryCountUnit,QLabel#muted,QLabel#eyebrow,QLabel#selectionHint,QLabel#deliverySubtitle,QLabel#infoCaption{font-size:@small@px;color:@muted@;}
QLabel#detailCustomer{font-size:@detail@px;font-weight:700;}
QLabel#detailQuantity{font-size:@action@px;font-weight:700;}
QLabel#metricValue,QLabel#quoteTotalValue,QLabel#workbenchTotal{font-size:@total@px;font-weight:700;color:@primary@;}
QFrame#card,QFrame#hero,QFrame#detailPanel,QFrame#quoteItemsCard,QFrame#customerInfoCard,QFrame#emailAddressCard,QFrame#workbenchDocumentTools{background:@card@;border:1px solid @border@;border-radius:10px;}
QFrame#statusBar{background:@card@;border:0;border-top:1px solid @border@;}
QFrame#statusBar QLabel{font-size:@small@px;}
QFrame#statusBar[state="working"]{background:@working-bg@;}
QFrame#statusBar[state="success"] QLabel{color:@primary@;}
QFrame#errorBanner{background:@cancel-bg@;border:0;border-radius:8px;}
QFrame#errorBanner QLabel{color:@cancel-fg@;font-size:@small@px;}
QPushButton{background:@card@;color:@text@;border:1px solid @input-border@;border-radius:8px;min-height:44px;max-height:44px;padding:0 16px;font-size:@body@px;font-weight:600;}
QPushButton:hover{background:@row-selected@;border-color:@primary@;}
QPushButton:pressed{background:@border@;}
QPushButton:focus{border:2px solid @primary@;}
QPushButton:disabled{background:@table-head@;color:@muted@;border-color:@border@;}
QPushButton#primary,QPushButton[role="primary"]{background:@primary@;color:@card@;border-color:@primary@;font-size:@action@px;min-height:52px;max-height:52px;}
QPushButton#primary:hover,QPushButton[role="primary"]:hover{background:@primary-hover@;}
QPushButton#primary:pressed,QPushButton[role="primary"]:pressed{background:@primary-pressed@;}
QPushButton#primary:disabled,QPushButton[role="primary"]:disabled{background:@border@;color:@muted@;border-color:@border@;}
QPushButton#danger,QPushButton#requestDelete,QPushButton[role="danger"]{background:transparent;color:@danger@;border:0;}
QPushButton[role="text"],QPushButton#textAction{background:transparent;color:@primary@;border:0;}
QPushButton#smsAction,QPushButton#mailAction,QPushButton#pdfAction,QPushButton[role="outline"]{background:@card@;color:@primary@;border:1px solid @primary@;}
QLineEdit,QComboBox,QDateEdit,QDoubleSpinBox,QSpinBox,QPlainTextEdit{background:@card@;border:1px solid @input-border@;border-radius:8px;padding:8px;selection-background-color:@row-selected@;selection-color:@text@;}
QLineEdit,QComboBox,QDateEdit,QDoubleSpinBox,QSpinBox{min-height:26px;}
QLineEdit:focus,QComboBox:focus,QPlainTextEdit:focus{border:2px solid @primary@;}
QLineEdit[readOnly="true"],QPlainTextEdit[readOnly="true"]{background:@table-head@;}
QLineEdit:disabled,QComboBox:disabled,QPlainTextEdit:disabled{background:@table-head@;color:@muted@;}
QComboBox{padding-right:32px;}
QComboBox::drop-down{subcontrol-origin:padding;subcontrol-position:top right;width:28px;border:0;background:transparent;}
QComboBox::down-arrow{image:none;width:0;height:0;border-left:5px solid transparent;border-right:5px solid transparent;border-top:6px solid @muted@;margin-right:8px;}
QTableWidget{background:@card@;alternate-background-color:@card@;border:1px solid @border@;border-radius:10px;gridline-color:@border@;selection-background-color:@row-selected@;selection-color:@text@;font-size:@body@px;}
QTableWidget::item{padding:8px;border:0;border-bottom:1px solid @border@;}
QTableWidget::item:selected{background:@row-selected@;color:@text@;}
QHeaderView::section{background:@table-head@;color:@muted@;border:0;border-bottom:1px solid @border@;padding:8px;font-size:@small@px;font-weight:700;}
QGroupBox{background:@card@;border:1px solid @border@;border-radius:10px;margin-top:24px;padding:16px;font-size:@section@px;font-weight:700;}
QGroupBox::title{subcontrol-origin:margin;left:16px;padding:0 8px;}
QScrollArea{border:0;background:transparent;}
QScrollArea>QWidget>QWidget{background:transparent;}
QScrollBar:vertical{background:@page-bg@;width:12px;}
QScrollBar::handle:vertical{background:@input-border@;border-radius:8px;min-height:44px;}
QScrollBar:horizontal{background:@page-bg@;height:12px;}
QScrollBar::handle:horizontal{background:@input-border@;border-radius:8px;min-width:44px;}
QScrollBar::add-line,QScrollBar::sub-line{width:0;height:0;}
QCheckBox,QRadioButton{spacing:8px;padding:8px 0;}
QCheckBox::indicator,QRadioButton::indicator{width:20px;height:20px;}
QTextBrowser{background:@card@;border:1px solid @border@;border-radius:8px;padding:8px;}
QToolTip{background:@sidebar@;color:@card@;border:0;padding:8px;font-size:@small@px;}
QPushButton#filterTab:checked{background:@row-selected@;color:@primary@;border-color:@primary@;}
QPushButton#stageTab{border:0;border-radius:0;border-bottom:3px solid transparent;min-height:48px;max-height:48px;padding:0 8px;}
QPushButton#stageTab:checked{border-bottom-color:@primary@;color:@primary@;}
QTabWidget::pane{border:0;border-top:1px solid @border@;}
QTabBar::tab{background:transparent;border:0;border-bottom:3px solid transparent;padding:8px 16px;min-height:32px;}
QTabBar::tab:selected{color:@primary@;border-bottom-color:@primary@;}
QLabel#channelStatusBadge{font-size:@small@px;background:@table-head@;color:@muted@;border-radius:8px;padding:4px 8px;}
QLabel#channelStatusBadge[state="pending"]{background:@working-bg@;color:@quote-fg@;}
QLabel#channelStatusBadge[state="failed"]{background:@cancel-bg@;color:@cancel-fg@;}
QLabel#channelStatusBadge[state="success"]{background:@paid-bg@;color:@paid-fg@;}
QLabel#emptyState{font-size:@body@px;color:@muted@;background:transparent;padding:24px;}
""", large)


def quote_workbench_style(large=False):
    return _theme("""
QDialog#quoteWorkbench{background:@page-bg@;}
QDialog#quoteWorkbench QFrame#workbenchHeader{background:@sidebar@;border:0;}
QDialog#quoteWorkbench #workbenchHeader QLabel{color:@card@;background:transparent;border:0;}
QDialog#quoteWorkbench QLabel#workbenchTitle{font-size:@title@px;font-weight:700;}
QDialog#quoteWorkbench QLabel#workbenchBrand{font-size:@action@px;font-weight:700;}
QDialog#quoteWorkbench QLabel#workbenchReference{font-size:@small@px;}
QDialog#quoteWorkbench #workbenchCustomerRail{background:@page-bg@;}
QDialog#quoteWorkbench QLabel#workbenchCaption{font-size:@small@px;color:@muted@;background:transparent;border:0;padding:0;}
QDialog#quoteWorkbench QFrame#workbenchDocumentTools{border-radius:10px;}
QDialog#quoteWorkbench QTableWidget#workbenchItems{font-size:@body@px;background:@card@;}
QDialog#quoteWorkbench QTableWidget::item{padding:4px 8px;}
QDialog#quoteWorkbench #quoteSpeciesEditor QLineEdit{border:0;background:transparent;padding:0;min-height:24px;}
QDialog#quoteWorkbench #quoteSpeciesEditor QPushButton{background:transparent;border:0;color:@primary@;font-size:@small@px;min-height:24px;max-height:24px;padding:0;}
QDialog#quoteWorkbench #unitPriceEditor QPushButton{min-width:40px;max-width:40px;min-height:40px;max-height:40px;padding:0;}
QDialog#quoteWorkbench #unitPriceEditor QLineEdit{min-height:38px;max-height:38px;min-width:64px;padding:0 4px;}
QDialog#quoteWorkbench QFrame#workbenchFooter{background:@card@;border:0;border-top:1px solid @border@;}
QDialog#quoteWorkbench QLabel#workbenchTotal{font-size:@total@px;font-weight:700;color:@primary@;}
QDialog#quoteWorkbench QFrame#workbenchShipping{background:@table-head@;border-radius:8px;}
QDialog#quoteWorkbench #workbenchShipping QLineEdit{min-height:28px;max-height:28px;}
QDialog#quoteWorkbench #workbenchOriginalText{background:@table-head@;border:1px solid @border@;border-radius:8px;font-size:@small@px;}
QDialog#quoteWorkbench QPushButton[deliveryCompletion="true"]{min-width:160px;max-width:180px;min-height:52px;max-height:52px;}
QDialog#quoteWorkbench QPushButton#smsAction,QDialog#quoteWorkbench QPushButton#mailAction{min-height:52px;max-height:52px;font-size:@action@px;padding:0 8px;}
QDialog#quoteWorkbench QLabel#deliveryActionTitle{font-size:@action@px;font-weight:700;}
QDialog#quoteWorkbench #workbenchCustomerInput{background:@card@;}
""", large)


def quote_delivery_style(large=False, compact=False):
    return workspace_style(large, compact)


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


class QuoteRemarksDelegate(RowSelectionDelegate):
    """An empty-cell hint is presentation only, never quote data or export text."""
    def initStyleOption(self, option, index):
        super().initStyleOption(option, index)
        if not index.data(Qt.ItemDataRole.DisplayRole):
            option.text = "입력가능"
            option.font.setItalic(True)
            option.palette.setColor(QPalette.ColorRole.Text, QColor(TOKENS["muted"]))
            option.palette.setColor(QPalette.ColorRole.HighlightedText, QColor(TOKENS["muted"]))

    def createEditor(self, parent, option, index):
        editor = super().createEditor(parent, option, index)
        if isinstance(editor, QLineEdit):
            font = parent.font()
            font.setItalic(False)
            editor.setFont(font)
            editor.setPlaceholderText("입력가능")
        return editor


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
        from .presentation import date_text
        option.text = date_text(option.text)


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
        painter.setPen(QColor(TOKENS['text']))
        top = QRectF(rect.x(), rect.y(), rect.width(), rect.height() / 2)
        painter.drawText(top, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                         painter.fontMetrics().elidedText(primary, Qt.TextElideMode.ElideRight, rect.width()))
        font.setBold(False)
        font.setPixelSize(15 if QApplication.instance().property('namufarmLargeFont') else 13)
        painter.setFont(font)
        painter.setPen(QColor(TOKENS['muted']))
        bottom = top.translated(0, rect.height() / 2)
        painter.drawText(bottom, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                         painter.fontMetrics().elidedText(str(secondary), Qt.TextElideMode.ElideRight, rect.width()))
        painter.restore()


class SequenceDelegate(RowSelectionDelegate):
    def paint(self, painter, option, index):
        opt = QStyleOptionViewItem(option)
        self.initStyleOption(opt, index)
        text, opt.text = opt.text, ''
        style = opt.widget.style() if opt.widget else QApplication.style()
        style.drawControl(QStyle.ControlElement.CE_ItemViewItem, opt, painter, opt.widget)
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        rect = QRectF(0, 0, 28, 28)
        rect.moveCenter(QRectF(option.rect).center())
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor(TOKENS['sidebar']))
        painter.drawEllipse(rect)
        painter.setPen(QColor(TOKENS['card']))
        font = option.font
        font.setPixelSize(15 if QApplication.instance().property('namufarmLargeFont') else 13)
        font.setBold(True)
        painter.setFont(font)
        painter.drawText(rect, Qt.AlignmentFlag.AlignCenter, text)
        painter.restore()


class StatusDelegate(RowSelectionDelegate):
    def paint(self, painter, option, index):
        opt = QStyleOptionViewItem(option)
        self.initStyleOption(opt, index)
        text = opt.text
        opt.text = ''
        style = opt.widget.style() if opt.widget else QApplication.style()
        style.drawControl(QStyle.ControlElement.CE_ItemViewItem, opt, painter, opt.widget)
        badge = status_badge(text)
        text = badge["text"]
        background, foreground = badge["background"], badge["foreground"]
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor(background))
        width = min(option.rect.width() - 12, option.fontMetrics.horizontalAdvance(text) + 24)
        rect = QRectF(0, 0, max(1, width), 30)
        rect.moveCenter(QRectF(option.rect).center())
        painter.drawRoundedRect(rect, 8, 8)
        painter.setPen(QColor(foreground))
        font = option.font
        font.setPixelSize(15 if QApplication.instance().property("namufarmLargeFont") else 13)
        font.setBold(True)
        painter.setFont(font)
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
        row_height = 64 if QApplication.instance().property("namufarmLargeFont") else 60
        self.verticalHeader().setMinimumSectionSize(row_height)
        self.verticalHeader().setDefaultSectionSize(row_height)
        self.horizontalHeader().setFixedHeight(44)
        self.model().rowsInserted.connect(self.update_empty_state)
        self.model().rowsRemoved.connect(self.update_empty_state)
        self.model().modelReset.connect(self.update_empty_state)

    def set_column_layout(self, columns):
        self._weights = tuple((c[0], c[1], c[2] if len(c) > 2 else 60) for c in columns)
        order = [column for column, _weight, _minimum in self._weights]
        header = self.horizontalHeader()
        header.setStretchLastSection(False)
        header.setMinimumSectionSize(min([60] + [minimum for _column, _weight, minimum in self._weights]))
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
            if minimum_total > available:
                columns = [(column, weight, max(36, int(minimum * available / minimum_total)))
                           for column, weight, minimum in self._weights]
            else:
                columns = self._weights
            minimum_total = sum(minimum for _, _, minimum in columns)
            extra = max(0, available - minimum_total)
            used = 0
            for position, (column, weight, minimum) in enumerate(columns):
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
            else:
                alignment = Qt.AlignmentFlag.AlignLeft
            item.setTextAlignment(alignment | Qt.AlignmentFlag.AlignVCenter)
            if not item.toolTip():
                item.setToolTip(item.text())
            widget.horizontalHeaderItem(column).setTextAlignment(alignment | Qt.AlignmentFlag.AlignVCenter)


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
    set_button_icon(toggle, "chevron", TOKENS["muted"])
    content.setVisible(expanded)
    toggle.toggled.connect(content.setVisible)
    layout.addWidget(toggle)
    layout.addWidget(content)
    return section



