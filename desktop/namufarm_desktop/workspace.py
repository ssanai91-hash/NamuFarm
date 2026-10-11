"""FHD seller workspace, built on the existing verified sync controller."""
import copy
import json
from datetime import datetime
from pathlib import Path
from html import escape as html_escape
from urllib.parse import quote as url_quote

from PySide6.QtCore import Qt, QUrl, QSaveFile, QIODevice, QTimer, Signal
from PySide6.QtGui import QColor, QDesktopServices, QFont, QIntValidator, QKeySequence, QShortcut, QTextDocument, QPageSize
from PySide6.QtPrintSupport import QPrinter, QPrintDialog
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QDialog, QFileDialog, QFormLayout,
    QFrame, QGridLayout, QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QListWidget,
    QMessageBox, QPlainTextEdit, QPushButton, QScrollArea, QSplitter, QStackedWidget,
    QTableWidgetItem, QTextBrowser, QVBoxLayout, QWidget, QSpinBox)

from . import __version__
from .app import MainWindow, Task, table, fill_table, money, timestamp
from .commerce import (ISSUER, new_draft, draft_amounts, validate_draft, quote_html, quote_document_html, shipping_html,
                       shipping_problem, parse_tracking_csv, tracking_number)
from .domain import SEOUL, profit, require
from .errors import UserError
from .shipping_excel import shipping_xls, parse_tracking_xls, export_orders, UPLOAD_SHEET

STYLE = """
QWidget{font-family:'Malgun Gothic','NanumGothic',sans-serif;font-size:13px;color:#263e35;}
QMainWindow,#workspace{background:#f3f5f1;}
#sidebar{background:#153e33;border:0;}
#sidebar QLabel{color:#bdd2c7;} #sidebar #brand{font-size:29px;font-weight:800;color:#fff;}
#sidebar QPushButton{background:transparent;color:#c6d9cf;border:0;border-radius:8px;text-align:left;padding:14px 18px;font-weight:600;}
#sidebar QPushButton:checked{background:#2e604e;color:#fff;}
#sidebar QPushButton:hover{background:#285442;color:#fff;}
#sidebar #navFoot{color:#a8c0b3;font-size:12px;}
QLabel#pageTitle{font-size:26px;font-weight:800;color:#193d2e;}
QLabel#muted{color:#76867c;} QLabel#eyebrow{color:#748a78;font-size:11px;font-weight:700;}
QLabel#sectionTitle{font-size:18px;font-weight:700;}
QLabel#metricValue{font-size:30px;font-weight:800;color:#194d3a;}
QFrame#card{background:#fff;border:1px solid #dfe7df;border-radius:12px;}
QFrame#hero{background:#e3eddf;border:1px solid #d5e2d0;border-radius:14px;}
QFrame#hero QLabel{background:transparent;}
QLabel#heroTitle{font-size:25px;font-weight:800;color:#1a482f;}
QLabel#notice{background:#e8efe6;color:#365c42;border-radius:7px;padding:10px;}
QPushButton{background:#fff;border:1px solid #d3ded5;border-radius:7px;padding:9px 15px;font-weight:600;}
QPushButton:hover{background:#eaf1e7;border-color:#9bb59e;}
QPushButton:pressed{background:#dbe8d8;}
QPushButton:disabled{background:#ecefea;color:#9aa69d;border-color:#e1e6df;}
QPushButton#primary{background:#286547;color:#fff;border-color:#286547;}
QPushButton#primary:hover{background:#1b5036;} QPushButton#primary:disabled{background:#a3b7a7;border-color:#a3b7a7;color:#eef2ed;}
QPushButton#danger{color:#ac5a40;}
QLineEdit,QComboBox,QDateEdit,QDoubleSpinBox,QSpinBox,QPlainTextEdit{background:#fff;border:1px solid #d5dfd6;border-radius:6px;padding:7px;selection-background-color:#d3e5d7;selection-color:#173b2e;}
QLineEdit:focus,QPlainTextEdit:focus{border:1px solid #4b805d;}
QLineEdit:disabled,QComboBox:disabled,QPlainTextEdit:disabled{background:#f2f4f0;color:#829085;}
QTableWidget{background:#fff;alternate-background-color:#fafbf8;border:1px solid #dfe7df;border-radius:8px;gridline-color:#edf1eb;selection-background-color:#e3efdf;selection-color:#183d2c;}
QTableWidget::item{padding:9px;border:0;border-bottom:1px solid #edf1eb;}
QTableWidget::item:selected{background:#e2efdf;color:#16492e;}
QHeaderView::section{background:#dfeadd;color:#315b46;border:0;border-bottom:1px solid #c9d9c8;padding:11px 9px;font-weight:700;}
QGroupBox{background:#fff;border:1px solid #dfe7df;border-radius:9px;margin-top:18px;padding:15px;font-weight:700;}
QGroupBox::title{subcontrol-origin:margin;left:15px;padding:0 5px;}
QScrollArea{border:0;background:transparent;} QScrollArea>QWidget>QWidget{background:transparent;}
QScrollBar:vertical{background:transparent;width:9px;margin:0;}
QScrollBar::handle:vertical{background:#cad7c8;border-radius:4px;min-height:30px;}
QScrollBar::add-line:vertical,QScrollBar::sub-line:vertical{height:0;}
QScrollBar::add-page:vertical,QScrollBar::sub-page:vertical{background:transparent;}
QCheckBox{spacing:8px;} QCheckBox::indicator{width:16px;height:16px;}
QTextBrowser{background:#fff;border:1px solid #dfe7df;padding:16px;}
QToolTip{background:#153e33;color:#fff;border:0;padding:8px;}
QDialog#quoteDeliveryDialog{
    background:#f4f7f3;
}

QLabel#deliveryTitle{
    font-size:28px;
    font-weight:800;
    color:#173f2f;
}

QLabel#deliverySubtitle{
    color:#6f8175;
    font-size:13px;
}

QLabel#quoteStatusBadge{
    background:#dcebdc;
    color:#23593a;
    border:1px solid #bdd2bd;
    border-radius:14px;
    padding:6px 14px;
    font-weight:700;
}

QFrame#deliveryInfoCard{
    background:#ffffff;
    border:1px solid #d9e4da;
    border-radius:12px;
}

QLabel#infoCaption{
    color:#7b8b80;
    font-size:11px;
    font-weight:600;
}

QLabel#infoValue{
    color:#183d2d;
    font-size:14px;
    font-weight:700;
}

QFrame#quoteItemsCard{
    background:#ffffff;
    border:1px solid #cbdcca;
    border-radius:12px;
}

QLabel#quoteItemsTitle{
    color:#173f2f;
    font-size:20px;
    font-weight:800;
}

QLabel#quoteItemsHint{
    color:#77877d;
    font-size:12px;
}

QTableWidget#deliveryItems{
    background:#ffffff;
    alternate-background-color:#f7faf6;
    border:1px solid #ceddce;
    border-radius:8px;
    gridline-color:#d4e1d4;
    selection-background-color:#e0eddf;
    selection-color:#173f2f;
}

QTableWidget#deliveryItems::item{
    padding:12px 10px;
    border-right:1px solid #dde7dd;
    border-bottom:1px solid #dde7dd;
    font-size:14px;
}

QTableWidget#deliveryItems QHeaderView::section{
    background:#2f6842;
    color:#ffffff;
    border:0;
    border-right:1px solid #5c8265;
    padding:11px 10px;
    font-weight:800;
}

QFrame#quoteTotalBand{
    background:#e3eee0;
    border:1px solid #c4d8c1;
    border-radius:10px;
}

QLabel#quoteTotalCaption{
    color:#42644e;
    font-size:14px;
    font-weight:700;
}

QLabel#quoteTotalValue{
    color:#174c31;
    font-size:30px;
    font-weight:900;
}

QFrame#requestNoteCard{
    background:#ffffff;
    border:1px solid #dbe5db;
    border-radius:10px;
}

QLabel#deliveryNotice{
    background:#eaf2e7;
    color:#49654f;
    border:1px solid #d6e4d3;
    border-radius:8px;
    padding:11px 13px;
}

QPushButton#smsAction{
    background:#3e7a50;
    color:#ffffff;
    border:1px solid #3e7a50;
    border-radius:8px;
    padding:11px 20px;
    font-weight:700;
}

QPushButton#smsAction:hover{
    background:#316640;
    border-color:#316640;
}

QPushButton#mailAction{
    background:#416f9d;
    color:#ffffff;
    border:1px solid #416f9d;
    border-radius:8px;
    padding:11px 20px;
    font-weight:700;
}

QPushButton#mailAction:hover{
    background:#345d86;
    border-color:#345d86;
}

QPushButton#pdfAction{
    background:#ffffff;
    color:#3c5546;
    border:1px solid #9caf9f;
    border-radius:8px;
    padding:11px 20px;
    font-weight:700;
}

QPushButton#pdfAction:hover{
    background:#edf3eb;
}

QPushButton#closeAction{
    background:#ffffff;
    color:#657269;
    border:1px solid #ccd5cd;
    border-radius:8px;
    padding:11px 20px;
}

QPushButton#sentAction{
    background:#185d3c;
    color:#ffffff;
    border:1px solid #185d3c;
    border-radius:8px;
    padding:12px 24px;
    font-weight:800;
}

QPushButton#sentAction:hover{
    background:#104a2f;
    border-color:#104a2f;
}

QPushButton#sentAction:disabled{
    background:#a9baad;
    color:#edf2ed;
    border-color:#a9baad;
}

QLabel#channelStatusBadge{
    border-radius:7px;
    padding:3px 6px;
    font-size:10px;
    font-weight:700;
    qproperty-alignment:AlignCenter;
}
QLabel#channelStatusBadge[done="false"]{
    background:#eef1ed;
    color:#859087;
    border:1px solid #d8dfd8;
}
QLabel#channelStatusBadge[done="true"]{
    background:#dcebdd;
    color:#23603b;
    border:1px solid #b8d2bd;
}

QDialog#quoteEmailDialog{background:#f4f7f3;}
QFrame#emailAddressCard{
    background:#ffffff;
    border:1px solid #d9e4da;
    border-radius:11px;
}
QLabel#emailSectionTitle{
    color:#173f2f;
    font-size:17px;
    font-weight:800;
}
QLabel#emailHelp{
    color:#6f8175;
    font-size:12px;
}
QLabel#emailSendStatus{
    background:#edf4ea;
    color:#47624e;
    border:1px solid #d5e2d2;
    border-radius:8px;
    padding:9px 11px;
}
QPushButton#emailSendPrimary{
    background:#2f6597;
    color:#ffffff;
    border:1px solid #2f6597;
    border-radius:8px;
    padding:11px 24px;
    font-weight:800;
}
QPushButton#emailSendPrimary:hover{background:#275680;border-color:#275680;}
QPushButton#emailSendPrimary:disabled{background:#aab8c6;border-color:#aab8c6;color:#eef2f5;}
"""


def label(text, name="", wrap=False):
    widget = QLabel(text)
    widget.setObjectName(name)
    widget.setWordWrap(wrap)
    return widget


def button(text, callback=None, primary=False):
    widget = QPushButton(text)
    if primary:
        widget.setObjectName("primary")
    if callback:
        widget.clicked.connect(callback)
    return widget


def card(title):
    frame = QFrame()
    frame.setObjectName("card")
    layout = QVBoxLayout(frame)
    layout.setContentsMargins(22, 18, 22, 18)
    layout.setSpacing(12)
    layout.addWidget(label(title, "sectionTitle"))
    return frame, layout


def polish_table(widget):
    widget.verticalHeader().setDefaultSectionSize(51)
    widget.setShowGrid(False)
    widget.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
    widget.horizontalHeader().setDefaultAlignment(Qt.AlignmentFlag.AlignCenter)


class UnitPriceEditor(QWidget):
    """Manual unit-price input with 1,000-won step buttons.

    Keeping the price in a dedicated editor also gives a future Excel price
    lookup one stable place to inject an automatically resolved unit price.
    """
    changed = Signal()
    STEP = 1000
    MAX_PRICE = 2147483647

    def __init__(self, value=0, parent=None):
        super().__init__(parent)
        self.setObjectName("unitPriceEditor")
        row = QHBoxLayout(self)
        row.setContentsMargins(4, 2, 4, 2)
        row.setSpacing(5)

        self.minus = QPushButton("-")
        self.minus.setToolTip("판매 단가 1,000원 내리기")
        self.minus.setFixedWidth(38)
        self.minus.clicked.connect(lambda: self.adjust(-self.STEP))
        row.addWidget(self.minus)

        self.input = QLineEdit(str(max(0, int(value or 0))))
        self.input.setValidator(QIntValidator(0, self.MAX_PRICE, self.input))
        self.input.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.input.setPlaceholderText("판매 단가")
        self.input.textChanged.connect(lambda _text: self.changed.emit())
        row.addWidget(self.input, 1)

        self.plus = QPushButton("+")
        self.plus.setToolTip("판매 단가 1,000원 올리기")
        self.plus.setFixedWidth(38)
        self.plus.clicked.connect(lambda: self.adjust(self.STEP))
        row.addWidget(self.plus)

    def text(self):
        return self.input.text().strip()

    def set_value(self, value):
        value = max(0, min(self.MAX_PRICE, int(value)))
        self.input.setText(str(value))

    def adjust(self, delta):
        raw = self.text()
        current = int(raw) if raw.isascii() and raw.isdigit() else 0
        self.set_value(max(0, min(self.MAX_PRICE, current + delta)))

def center_table_columns(widget, columns=None):
    columns = range(widget.columnCount()) if columns is None else columns
    for row in range(widget.rowCount()):
        for column in columns:
            item = widget.item(row, column)
            if item is not None:
                item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)


def print_document(parent, html, filename):
    dialog = QDialog(parent)
    dialog.setWindowTitle("문서 미리보기")
    dialog.resize(990, 840)
    layout = QVBoxLayout(dialog)
    view = QTextBrowser()
    view.setHtml(html)
    layout.addWidget(view)
    row = QHBoxLayout()
    row.addWidget(label("A4 문서 · 출력할 프린터 또는 PDF를 선택하세요.", "muted"))
    row.addStretch()
    def output(pdf):
        printer = QPrinter(QPrinter.PrinterMode.HighResolution)
        printer.setPageSize(QPageSize(QPageSize.PageSizeId.A4))
        if pdf:
            path, _ = QFileDialog.getSaveFileName(dialog, "PDF 저장", filename + ".pdf", "PDF (*.pdf)")
            if not path:
                return
            if not path.lower().endswith(".pdf"):
                path += ".pdf"
            printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat)
            printer.setOutputFileName(path)
        elif QPrintDialog(printer, dialog).exec() != QDialog.DialogCode.Accepted:
            return
        document = QTextDocument()
        document.setHtml(html)
        document.print_(printer)
        if pdf and (not Path(path).is_file() or Path(path).stat().st_size == 0):
            QMessageBox.warning(dialog, "저장 확인", "PDF를 저장하지 못했습니다. 폴더 권한과 파일 상태를 확인해주세요.")
    row.addWidget(button("PDF 저장", lambda: output(True)))
    row.addWidget(button("프린터 출력", lambda: output(False), True))
    row.addWidget(button("닫기", dialog.accept))
    layout.addLayout(row)
    dialog.exec()


class QuoteDialog(QDialog):
    """Website requests use a minimal price-only quote editor."""
    def __init__(self, draft, parent=None, request_status=""):
        super().__init__(parent)
        self.draft, self.mode, self.request_status = copy.deepcopy(draft), "", request_status
        self.simple_request = bool(draft.get("sourceRequestId"))
        self.setWindowTitle("견적 작업대")
        self.resize(1160 if self.simple_request else 1280, 760 if self.simple_request else 840)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)

        head = QHBoxLayout()
        head.addWidget(label("견적 작업대", "pageTitle"))
        head.addStretch()
        head.addWidget(label(("홈페이지 요청 · " + (request_status or "신규")) if self.simple_request else "PC 수기 견적", "muted"))
        layout.addLayout(head)

        split = QSplitter()
        self.fields = {}
        if self.simple_request:
            info = QWidget()
            form = QFormLayout(info)
            for title, value in (("견적번호", draft.get("requestReceiptNumber", "") or draft.get("sourceRequestId", "")),
                                 ("고객명", draft.get("customerName", "")),
                                 ("연락처", draft.get("customerPhone", "")),
                                 ("이메일", draft.get("customerEmail", "") or "미입력"),
                                 ("지역", draft.get("requestRegion", ""))):
                field = QLineEdit(str(value))
                field.setReadOnly(True)
                form.addRow(title, field)

            formal = bool(draft.get("formalQuoteRequested", False))
            formal_value = QLineEdit("요청" if formal else "미요청")
            formal_value.setReadOnly(True)
            form.addRow("공식 견적서", formal_value)
            if formal:
                recipient = QLineEdit(draft.get("quoteRecipientName", "") or draft.get("customerName", ""))
                recipient.setReadOnly(True)
                form.addRow("견적서 수신인", recipient)
                channel = {"sms": "문자", "email": "이메일", "both": "문자 + 이메일"}.get(
                    draft.get("quoteReplyChannel", "sms"), "문자"
                )
                reply = QLineEdit(channel)
                reply.setReadOnly(True)
                form.addRow("수신 방법", reply)
            self.shipping_method = QComboBox()
            self.shipping_method.addItems(["후불", "선불"])
            self.shipping_method.setCurrentIndex(1 if draft.get("isPrepaid") else 0)
            form.addRow("택배비 방식", self.shipping_method)

            self.shipping_fee = QLineEdit(str(int(draft.get("shippingFee") or 6000)))
            self.shipping_fee.setValidator(QIntValidator(0, 2147483647, self.shipping_fee))
            self.shipping_fee.setPlaceholderText("선불 시 택배비")
            form.addRow("선불 시 택배비 (원)", self.shipping_fee)

            self.note = QPlainTextEdit(draft.get("note", ""))
            self.note.setReadOnly(True)
            self.note.setMaximumHeight(120)
            form.addRow("요청사항", self.note)
            form.addRow(label("고객이 요청한 품목·수량과 택배비를 확인하고 판매 단가를 입력하세요.", "muted", True))
            split.addWidget(info)
            self.cost = QCheckBox()
            self.shipping_method.currentIndexChanged.connect(self.recalculate)
            self.shipping_fee.textChanged.connect(self.recalculate)
        else:
            info = QWidget()
            form = QFormLayout(info)
            for key, title in (("customerName", "고객명"), ("customerPhone", "연락처"), ("customerEmail", "이메일"),
                               ("receiverName", "수령인"), ("zipNo", "우편번호"), ("roadAddress", "도로명 주소"), ("detailAddress", "상세 주소"),
                               ("validUntil", "견적 유효기간"), ("paymentAccount", "입금계좌 안내")):
                field = QLineEdit(str(draft.get(key, "")))
                field.setPlaceholderText(title)
                self.fields[key] = field
                form.addRow(title, field)
            self.note = QPlainTextEdit(draft.get("note", ""))
            self.note.setMaximumHeight(95)
            form.addRow("고객·배송 메모", self.note)
            self.prepaid = QCheckBox("선불 배송비를 합계에 포함")
            self.prepaid.setChecked(draft["isPrepaid"])
            form.addRow(self.prepaid)
            for key, title in (("shippingFee", "배송비 (원)"), ("taxAmount", "세액 (원)")):
                field = QLineEdit(str(draft[key]))
                self.fields[key] = field
                field.textChanged.connect(self.recalculate)
                form.addRow(title, field)
            self.cost = QCheckBox("입력한 매입 원가를 확인했습니다")
            self.cost.setChecked(draft["costConfirmed"])
            form.addRow(self.cost)
            self.prepaid.toggled.connect(self.recalculate)
            scroll = QScrollArea()
            scroll.setWidgetResizable(True)
            scroll.setWidget(info)
            split.addWidget(scroll)

        right = QWidget()
        content = QVBoxLayout(right)
        content.addWidget(label("견적 품목", "sectionTitle"))
        if self.simple_request:
            content.addWidget(label("수량은 홈페이지 요청값입니다. 판매 단가를 입력하면 판매 금액을 계산합니다.", "muted", True))
            self.items = table(["묘목명", "수량", "판매 금액", "판매 단가"])
        else:
            content.addWidget(label("수기 견적 품목으로 등록합니다. 휴대폰 재고 수량은 차감하지 않습니다.", "muted", True))
            self.items = table(["묘목명", "규격", "수량", "판매 단가", "매입 단가", "판매 금액"])
        self.items.setEditTriggers(self.items.EditTrigger.DoubleClicked | self.items.EditTrigger.EditKeyPressed | self.items.EditTrigger.AnyKeyPressed)
        self.items.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        polish_table(self.items)
        self.items.itemChanged.connect(self.recalculate)
        content.addWidget(self.items)

        if not self.simple_request:
            actions = QHBoxLayout()
            actions.addWidget(button("+ 품목 추가", self.add_item))
            actions.addWidget(button("선택 품목 삭제", self.remove_item))
            actions.addStretch()
            content.addLayout(actions)

        self.total = label("", "metricValue", True)
        content.addWidget(self.total)
        self.amount_detail = label("", "muted", True)
        content.addWidget(self.amount_detail)
        split.addWidget(right)
        split.setSizes([360 if self.simple_request else 420, 760 if self.simple_request else 780])
        layout.addWidget(split, 1)

        footer = QHBoxLayout()
        if not self.simple_request:
            footer.addWidget(button("발행자 정보", self.edit_issuer))
            footer.addWidget(button("견적안 미리보기 / PDF", self.preview))
        footer.addStretch()
        footer.addWidget(button("닫기", self.reject))
        footer.addWidget(button("임시저장", lambda: self.finish("save")))
        if self.simple_request:
            self.confirm_button = button("견적 확정", lambda: self.finish("finalize"), True)
            self.confirm_button.setEnabled(request_status in ("", "신규"))
            footer.addWidget(self.confirm_button)
        else:
            footer.addWidget(button("입금확인 · 판매 등록", lambda: self.finish("confirm"), True))
        layout.addLayout(footer)

        for item in draft["items"]:
            self.add_item(item)
        if not draft["items"] and not self.simple_request:
            self.add_item()
        self.recalculate()
        self.initial = self.raw_state()

    def add_item(self, item=None):
        item = item if isinstance(item, dict) else {"species": "", "specification": "", "quantity": 1, "unitPrice": 0, "unitCost": 0}
        self.items.blockSignals(True)
        index = self.items.rowCount()
        self.items.insertRow(index)
        if self.simple_request:
            values = (item["species"], item["quantity"], f"{item['quantity'] * item['unitPrice']:,}")
            for column, value in enumerate(values):
                cell = QTableWidgetItem(str(value))
                cell.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                cell.setFlags(cell.flags() & ~Qt.ItemFlag.ItemIsEditable)
                self.items.setItem(index, column, cell)
            price_editor = UnitPriceEditor(item["unitPrice"], self.items)
            price_editor.changed.connect(self.recalculate)
            self.items.setCellWidget(index, 3, price_editor)
        else:
            for column, key in enumerate(("species", "specification", "quantity", "unitPrice", "unitCost")):
                cell = QTableWidgetItem(str(item[key]))
                if column >= 2:
                    cell.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                self.items.setItem(index, column, cell)
            amount = QTableWidgetItem("")
            amount.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            amount.setFlags(amount.flags() & ~Qt.ItemFlag.ItemIsEditable)
            self.items.setItem(index, 5, amount)
        self.items.blockSignals(False)
        self.recalculate()

    def unit_price_editor(self, row):
        editor = self.items.cellWidget(row, 3) if self.simple_request else None
        require(isinstance(editor, UnitPriceEditor), "판매 단가 입력칸을 확인해주세요.")
        return editor

    def set_unit_price(self, row, value):
        """Set one request item's unit price; future Excel lookup can call this."""
        self.unit_price_editor(row).set_value(value)

    def remove_item(self):
        if self.simple_request:
            return
        index = self.items.currentRow()
        if index >= 0:
            self.items.removeRow(index)
            self.recalculate()

    def raw_state(self):
        if self.simple_request:
            item_state = [[self.items.item(r, 0).text(), self.items.item(r, 1).text(), self.unit_price_editor(r).text()]
                          for r in range(self.items.rowCount())]
            shipping_state = [self.shipping_method.currentText(), self.shipping_fee.text()]
        else:
            item_state = [[self.items.item(r, c).text() for c in range(5)] for r in range(self.items.rowCount())]
            shipping_state = []
        return ([field.text() for field in self.fields.values()], self.note.toPlainText(), item_state,
                shipping_state, copy.deepcopy(self.draft["issuer"]))

    def reject(self):
        if hasattr(self, "initial") and self.raw_state() != self.initial:
            if QMessageBox.question(self, "미저장 견적", "저장하지 않은 입력을 버리고 닫을까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
        super().reject()

    def collect(self, validate=True):
        draft = copy.deepcopy(self.draft)
        if self.simple_request:
            shipping_fee = self.shipping_fee.text().strip()
            require(shipping_fee.isascii() and shipping_fee.isdigit(), "선불 시 택배비를 0 이상의 정수로 입력해주세요.")
            draft.update(shippingFee=int(shipping_fee), isPrepaid=self.shipping_method.currentText() == "선불",
                         taxAmount=0, costConfirmed=False, items=[])
            for row in range(self.items.rowCount()):
                species = self.items.item(row, 0).text().strip()
                quantity = self.items.item(row, 1).text().strip()
                price = self.unit_price_editor(row).text()
                require(quantity.isascii() and quantity.isdigit() and price.isascii() and price.isdigit(),
                        f"{row+1}번째 품목 수량·판매 단가를 정수로 입력해주세요.")
                draft["items"].append(dict(species=species, specification="", quantity=int(quantity), unitPrice=int(price), unitCost=0))
            return validate_draft(draft) if validate else draft

        for key, field in self.fields.items():
            value = field.text().strip()
            if key in ("shippingFee", "taxAmount"):
                require(value.isascii() and value.isdigit(), "배송비와 세액을 0 이상의 정수로 입력해주세요.")
                value = int(value)
            draft[key] = value
        draft["customerPhone"] = draft["customerPhone"].replace("-", "").replace(" ", "")
        draft.update(note=self.note.toPlainText().strip(), isPrepaid=self.prepaid.isChecked(), costConfirmed=self.cost.isChecked(), items=[])
        for row in range(self.items.rowCount()):
            values = [self.items.item(row, c).text().strip() for c in range(5)]
            require(all(v.isascii() and v.isdigit() for v in values[2:]), f"{row+1}번째 품목 수량·단가를 정수로 입력해주세요.")
            draft["items"].append(dict(species=values[0], specification=values[1], quantity=int(values[2]), unitPrice=int(values[3]), unitCost=int(values[4])))
        return validate_draft(draft) if validate else draft

    def recalculate(self, *args):
        if not hasattr(self, "items") or not hasattr(self, "total"):
            return
        try:
            draft = self.collect(False)
            self.items.blockSignals(True)
            if self.simple_request:
                for row, item in enumerate(draft["items"]):
                    self.items.item(row, 2).setText(f"{item['quantity'] * item['unitPrice']:,}")
            else:
                for row, item in enumerate(draft["items"]):
                    self.items.item(row, 5).setText(f"{item['quantity'] * item['unitPrice']:,}")
            self.items.blockSignals(False)
            amount = draft_amounts(draft)
            self.total.setText(f"합계 {amount['total']:,}원")
            if self.simple_request:
                item_total = sum(i["quantity"] * i["unitPrice"] for i in draft["items"])
                shipping_text = (f"택배비 선불 {draft['shippingFee']:,}원 · 합계 포함"
                                 if draft["isPrepaid"]
                                 else f"택배비 후불 · 선불 시 {draft['shippingFee']:,}원")
                self.amount_detail.setText(
                    f"총 수량 {amount['quantity']:,}주 · 묘목 금액 {item_total:,}원 · {shipping_text}"
                )
            else:
                self.amount_detail.setText(
                    f"{amount['quantity']:,}주   ·   공급가 {amount['supply']:,}원   ·   세액 {amount['tax']:,}원\n"
                    f"매입 원가 {amount['cost']:,}원"
                )
        except UserError:
            self.total.setText("판매 단가를 입력해주세요")
            self.amount_detail.setText("판매 금액 = 수량 × 판매 단가" if self.simple_request else "판매 금액은 수량 × 판매 단가로 계산합니다.")

    def edit_issuer(self):
        if self.simple_request:
            return
        dialog = QDialog(self)
        dialog.setWindowTitle("견적 발행자")
        form = QFormLayout(dialog)
        fields = {}
        titles = ("상호", "사업자등록번호", "대표자", "주소", "연락처", "업태", "종목")
        for (key, value), title in zip(self.draft["issuer"].items(), titles):
            field = QLineEdit(value)
            fields[key] = field
            form.addRow(title, field)
        form.addRow(button("적용", dialog.accept, True))
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.draft["issuer"] = {key: field.text().strip() for key, field in fields.items()}

    def preview(self):
        if self.simple_request:
            return
        try:
            draft = self.collect()
            print_document(self, quote_html(draft), "나무팜_견적안_" + draft["id"][:8])
        except UserError as error:
            QMessageBox.warning(self, "견적 입력 확인", str(error))

    def finish(self, mode):
        try:
            draft = self.collect()
            if mode == "finalize" and QMessageBox.question(self, "견적 확정", "판매 단가와 합계를 확인했나요?\n확정하면 온라인 요청 상태가 '견적확정'으로 바뀌고 견적 작업대로 이동합니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
            if mode == "confirm" and QMessageBox.question(self, "입금확인", "고객의 구매와 입금을 확인했나요?\n품목·수량·금액은 판매 등록 후 변경할 수 없습니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
            self.draft, self.mode = draft, mode
            self.accept()
        except UserError as error:
            QMessageBox.warning(self, "견적 입력 확인", str(error))


class QuoteEmailDialog(QDialog):
    """메일 내용 확인과 실제 전송에 집중한 간결한 견적 메일 창."""

    SENDER_EMAIL = "ssanai91@gmail.com"

    def __init__(self, draft, manager=None, parent=None):
        super().__init__(parent)
        self.draft = copy.deepcopy(draft)
        self.manager = manager
        self.delivery_state = {}
        self.profile_task = None
        self.send_task = None
        self.setObjectName("quoteEmailDialog")
        self.setWindowTitle("견적 메일 보내기")
        self.setSizeGripEnabled(True)

        screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
        available = screen.availableGeometry() if screen else None
        if available:
            width = min(860, max(660, int(available.width() * 0.68)))
            height = min(640, max(500, int(available.height() * 0.72)))
            width = min(width, max(560, available.width() - 40))
            height = min(height, max(460, available.height() - 60))
            self.resize(width, height)
        else:
            self.resize(880, 660)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        layout.setSpacing(12)

        layout.addWidget(label("견적 메일 보내기", "deliveryTitle"))
        layout.addWidget(label("받는 사람과 메일 내용을 확인하고 필요한 부분을 수정한 뒤 실제 메일을 전송합니다.", "deliverySubtitle", True))

        address_card = QFrame()
        address_card.setObjectName("emailAddressCard")
        address = QGridLayout(address_card)
        address.setContentsMargins(16, 12, 16, 12)
        address.setHorizontalSpacing(12)
        address.setVerticalSpacing(8)

        # 메일 수신인은 고객명을 기본으로 사용합니다.
        self.recipient_name = QLineEdit(self.draft.get("customerName", ""))
        self.recipient_name.setReadOnly(True)
        self.recipient_email = QLineEdit(self.draft.get("customerEmail", ""))
        self.recipient_email.setReadOnly(True)
        self.sender_name = QLineEdit(self.draft.get("issuer", {}).get("businessName") or "나무팜농원")
        self.sender_name.setReadOnly(True)
        self.sender_email = QLineEdit(self.SENDER_EMAIL)
        self.sender_email.setReadOnly(True)

        address.addWidget(label("받는 사람"), 0, 0)
        address.addWidget(self.recipient_name, 0, 1)
        address.addWidget(label("수신 이메일"), 0, 2)
        address.addWidget(self.recipient_email, 0, 3)
        address.addWidget(label("보내는 사람"), 1, 0)
        address.addWidget(self.sender_name, 1, 1)
        address.addWidget(label("발신 이메일"), 1, 2)
        address.addWidget(self.sender_email, 1, 3)
        address.setColumnStretch(1, 1)
        address.setColumnStretch(3, 2)
        layout.addWidget(address_card)

        subject_row = QHBoxLayout()
        subject_row.setSpacing(12)
        subject_row.addWidget(label("메일 제목", "emailSectionTitle"))
        self.subject = QLineEdit("묘목 견적 안내")
        self.subject.setMaxLength(160)
        subject_row.addWidget(self.subject, 1)
        layout.addLayout(subject_row)

        layout.addWidget(label("메일 내용", "emailSectionTitle"))

        self.message = QPlainTextEdit(self.default_message())
        self.message.setMinimumHeight(300)
        layout.addWidget(self.message, 1)

        self.status = label("Apps Script 발신 계정을 확인하고 있습니다.", "emailSendStatus", True)
        layout.addWidget(self.status)

        actions = QHBoxLayout()
        self.cancel_button = button("취소", self.reject)
        self.send_button = QPushButton("메일 전송")
        self.send_button.setObjectName("emailSendPrimary")
        self.send_button.setEnabled(False)
        self.send_button.clicked.connect(self.send_email)
        actions.addStretch()
        actions.addWidget(self.cancel_button)
        actions.addWidget(self.send_button)
        layout.addLayout(actions)

        QTimer.singleShot(0, self.load_profile)

    def quote_number(self):
        return self.draft.get("requestReceiptNumber") or ("PCQ_" + self.draft["id"][:8])

    def default_message(self):
        customer = self.draft.get("customerName") or "고객"
        item_lines = []
        for index, item in enumerate(self.draft.get("items", []), 1):
            quantity = int(item["quantity"])
            amount = quantity * int(item["unitPrice"])
            item_lines.extend([
                f"품목{index} : {item['species']} {quantity:,}주",
                f"판매금액 : {amount:,}원",
            ])
            if index < len(self.draft.get("items", [])):
                item_lines.append("")

        item_total = sum(int(item["quantity"]) * int(item["unitPrice"]) for item in self.draft.get("items", []))
        shipping_fee = int(self.draft.get("shippingFee", 0) or 0)
        if self.draft.get("isPrepaid"):
            total = item_total + shipping_fee
            shipping_line = f"택배비 : 선불 {shipping_fee:,}원 (총금액 포함)"
        else:
            total = item_total
            shipping_line = f"택배비 : 후불 / 선불 시 : {shipping_fee:,}원"

        return "\n".join([
            f"{customer}님, 안녕하세요.",
            "",
            "나무팜농원입니다.",
            "요청하신 묘목 견적을 안내드립니다.",
            "",
            "===========================",
            "",
            f"견적번호 : {self.quote_number()}",
            *item_lines,
            "",
            f"총금액 : {total:,}원",
            shipping_line,
            "",
            "===========================",
            "",
            "견적 내용을 확인하시고 궁금하신 사항은 편하게 연락 부탁드립니다.",
            "감사합니다.",
            "",
            "나무팜농원",
            "010-4446-0304 / 043-732-0980",
        ]).strip()

    def load_profile(self):
        if not self.manager:
            self.status.setText("메일 전송은 PC를 서버에 연결한 뒤 사용할 수 있습니다.")
            return
        self.send_button.setEnabled(False)
        task = Task(self.manager.email_profile, None, self)
        self.profile_task = task
        task.completed.connect(lambda value, error, conflict: self.profile_loaded(task, value, error))
        task.start()

    def profile_loaded(self, task, value, error):
        task.wait()
        if self.profile_task is task:
            self.profile_task = None
        task.deleteLater()
        if error:
            self.status.setText(error)
            self.send_button.setEnabled(False)
            return
        sender = str(value.get("senderEmail") or "")
        if sender.lower() != self.SENDER_EMAIL.lower():
            self.status.setText(f"발신 계정을 확인해주세요. 현재 계정: {sender or '확인 불가'}")
            self.send_button.setEnabled(False)
            return
        self.sender_name.setText(str(value.get("senderName") or self.sender_name.text()))
        self.sender_email.setText(self.SENDER_EMAIL)
        quota = int(value.get("remainingDailyQuota", 0) or 0)
        self.status.setText(f"발신 계정 확인 완료 · 오늘 남은 메일 전송 한도 {quota:,}건")
        self.send_button.setEnabled(bool(self.recipient_email.text().strip()) and quota > 0)

    def plain_body(self):
        return self.message.toPlainText().strip()

    def html_body(self):
        message = html_escape(self.plain_body()).replace("\n", "<br>")
        return (
            "<div style='font-family:Arial,Malgun Gothic,sans-serif;color:#263e35;"
            "font-size:14px;line-height:1.75'>"
            f"{message}"
            "</div>"
        )

    def send_email(self):
        subject = self.subject.text().strip()
        message = self.message.toPlainText().strip()
        if not subject or not message:
            QMessageBox.warning(self, "메일 확인", "메일 제목과 내용을 입력해주세요.")
            return
        if not self.manager or self.send_task:
            return
        self.subject.setEnabled(False)
        self.message.setEnabled(False)
        self.send_button.setEnabled(False)
        self.cancel_button.setEnabled(False)
        self.status.setText("고객에게 견적 메일을 전송하고 있습니다…")
        plain, html = self.plain_body(), self.html_body()
        task = Task(lambda: self.manager.send_quote_email(self.draft, subject, plain, html), None, self)
        self.send_task = task
        task.completed.connect(lambda value, error, conflict: self.email_sent(task, value, error))
        task.start()

    def email_sent(self, task, value, error):
        task.wait()
        if self.send_task is task:
            self.send_task = None
        task.deleteLater()
        if error:
            self.subject.setEnabled(True)
            self.message.setEnabled(True)
            self.cancel_button.setEnabled(True)
            self.send_button.setEnabled(True)
            self.status.setText(error)
            return
        self.delivery_state = dict(value.get("delivery") or {})
        self.sender_email.setText(self.SENDER_EMAIL)
        self.status.setText("메일 전송완료 · 고객 메일함으로 실제 전송했습니다.")
        QMessageBox.information(self, "메일 전송완료", f"{self.recipient_email.text()} 주소로 견적 메일을 전송했습니다.")
        self.accept()

    def reject(self):
        if self.profile_task or self.send_task:
            self.status.setText("현재 메일 처리가 끝난 뒤 창을 닫아주세요.")
            return
        super().reject()

class QuoteDeliveryDialog(QDialog):
    """확정된 견적의 고객 전달만 담당하는 전용 팝업."""

    def __init__(self, draft, request_status, manager=None, delivery_state=None, parent=None):
        super().__init__(parent)

        self.draft = copy.deepcopy(draft)
        self.manager = manager
        self.delivery_state = dict(delivery_state or {})
        self.request_status = (
            request_status
            or ("수기견적" if not draft.get("sourceRequestId") else "상태확인")
        )
        self.mode = ""

        self.setObjectName("quoteDeliveryDialog")
        self.setWindowTitle("견적 전달")
        self.resize(1120, 820)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 24, 28, 24)
        layout.setSpacing(16)

        # ─────────────────────────────
        # Header
        # ─────────────────────────────
        head = QHBoxLayout()
        head.setSpacing(12)

        title_box = QVBoxLayout()
        title_box.setSpacing(3)

        title = QLabel("견적 전달")
        title.setObjectName("deliveryTitle")

        subtitle = QLabel(
            "확정된 견적을 확인하고 문자·메일·견적서로 고객에게 전달합니다."
        )
        subtitle.setObjectName("deliverySubtitle")

        title_box.addWidget(title)
        title_box.addWidget(subtitle)

        head.addLayout(title_box)
        head.addStretch()

        delivery_badges = QVBoxLayout()
        delivery_badges.setSpacing(4)
        self.sms_status_badge = QLabel()
        self.email_status_badge = QLabel()
        for badge in (self.sms_status_badge, self.email_status_badge):
            badge.setObjectName("channelStatusBadge")
            badge.setFixedSize(88, 24)
            badge.setAlignment(Qt.AlignmentFlag.AlignCenter)
            delivery_badges.addWidget(badge)
        self.update_delivery_badges()
        head.addLayout(delivery_badges)

        status = QLabel(self.request_status)
        status.setObjectName("quoteStatusBadge")
        status.setAlignment(Qt.AlignmentFlag.AlignCenter)
        status.setFixedSize(176, 52)

        head.addWidget(status)
        layout.addLayout(head)

        # ─────────────────────────────
        # Customer / quote summary
        # ─────────────────────────────
        info_card = QFrame()
        info_card.setObjectName("deliveryInfoCard")

        info = QGridLayout(info_card)
        info.setContentsMargins(20, 16, 20, 16)
        info.setHorizontalSpacing(28)
        info.setVerticalSpacing(9)

        channel = {
            "sms": "문자",
            "email": "이메일",
            "both": "문자 + 이메일",
        }.get(
            self.draft.get("quoteReplyChannel", "sms"),
            "문자",
        )

        summary = (
            ("견적번호", self.quote_number()),
            ("고객명", self.draft.get("customerName", "")),
            ("연락처", self.draft.get("customerPhone", "")),
            ("메일", self.draft.get("customerEmail") or "미입력"),
            (
                "공식견적서",
                "요청"
                if self.draft.get("formalQuoteRequested")
                else "미요청",
            ),
            ("수신방법", channel),
        )

        for index, (caption, value) in enumerate(summary):
            column = (index % 3) * 2
            row = index // 3

            caption_label = QLabel(caption)
            caption_label.setObjectName("infoCaption")

            value_label = QLabel(str(value))
            value_label.setObjectName("infoValue")
            value_label.setTextInteractionFlags(
                Qt.TextInteractionFlag.TextSelectableByMouse
            )

            info.addWidget(caption_label, row, column)
            info.addWidget(value_label, row, column + 1)

        info.setColumnStretch(1, 1)
        info.setColumnStretch(3, 1)
        info.setColumnStretch(5, 2)

        layout.addWidget(info_card)

        # ─────────────────────────────
        # Quote items — main area
        # ─────────────────────────────
        items_card = QFrame()
        items_card.setObjectName("quoteItemsCard")

        items_layout = QVBoxLayout(items_card)
        items_layout.setContentsMargins(18, 16, 18, 18)
        items_layout.setSpacing(10)

        items_head = QHBoxLayout()

        items_title_box = QVBoxLayout()
        items_title_box.setSpacing(2)

        items_title = QLabel("견적 품목")
        items_title.setObjectName("quoteItemsTitle")

        items_hint = QLabel(
            "고객에게 전달할 묘목 · 수량 · 판매단가를 최종 확인하세요."
        )
        items_hint.setObjectName("quoteItemsHint")

        items_title_box.addWidget(items_title)
        items_title_box.addWidget(items_hint)

        items_head.addLayout(items_title_box)
        items_head.addStretch()

        count = sum(
            int(item.get("quantity", 0))
            for item in self.draft.get("items", [])
        )

        count_label = QLabel(
            f"{len(self.draft.get('items', []))}개 품목 · 총 {count:,}주"
        )
        count_label.setObjectName("quoteItemsHint")
        items_head.addWidget(count_label)

        items_layout.addLayout(items_head)

        self.items = table(
            ["묘목명", "수량", "판매 단가", "판매 금액"]
        )
        self.items.setObjectName("deliveryItems")
        self.items.setAlternatingRowColors(True)
        self.items.setShowGrid(True)
        self.items.setMinimumHeight(240)
        self.items.verticalHeader().setDefaultSectionSize(54)

        header = self.items.horizontalHeader()
        header.setStretchLastSection(False)
        header.setSectionResizeMode(
            0,
            QHeaderView.ResizeMode.Stretch,
        )
        header.setSectionResizeMode(
            1,
            QHeaderView.ResizeMode.Fixed,
        )
        header.setSectionResizeMode(
            2,
            QHeaderView.ResizeMode.Fixed,
        )
        header.setSectionResizeMode(
            3,
            QHeaderView.ResizeMode.Fixed,
        )

        self.items.setColumnWidth(1, 130)
        self.items.setColumnWidth(2, 170)
        self.items.setColumnWidth(3, 180)

        rows = []

        for item in self.draft.get("items", []):
            quantity = int(item["quantity"])
            unit_price = int(item["unitPrice"])
            amount = quantity * unit_price

            rows.append(
                (
                    item["species"],
                    f"{quantity:,}주",
                    f"{unit_price:,}원",
                    f"{amount:,}원",
                )
            )

        fill_table(self.items, rows)
        center_table_columns(self.items, (1, 2, 3))

        # 묘목명은 왼쪽 정렬 + 약간 강조
        for row in range(self.items.rowCount()):
            cell = self.items.item(row, 0)
            if cell:
                font = cell.font()
                font.setBold(True)
                cell.setFont(font)
                cell.setTextAlignment(
                    Qt.AlignmentFlag.AlignLeft
                    | Qt.AlignmentFlag.AlignVCenter
                )

        items_layout.addWidget(self.items)

        # Total amount band
        total_band = QFrame()
        total_band.setObjectName("quoteTotalBand")

        total_layout = QHBoxLayout(total_band)
        total_layout.setContentsMargins(20, 12, 20, 12)

        total_caption = QLabel("총 판매금액")
        total_caption.setObjectName("quoteTotalCaption")

        amounts = draft_amounts(self.draft)

        total_value = QLabel(
            f"{amounts['total']:,}원"
        )
        total_value.setObjectName("quoteTotalValue")

        total_layout.addWidget(total_caption)
        total_layout.addStretch()
        total_layout.addWidget(total_value)

        items_layout.addWidget(total_band)

        layout.addWidget(items_card, 1)

        # ─────────────────────────────
        # Customer request note
        # ─────────────────────────────
        if self.draft.get("note"):
            note_card = QFrame()
            note_card.setObjectName("requestNoteCard")

            note_layout = QVBoxLayout(note_card)
            note_layout.setContentsMargins(16, 12, 16, 14)
            note_layout.setSpacing(7)

            note_title = QLabel("요청사항")
            note_title.setObjectName("sectionTitle")

            note_text = QLabel(self.draft.get("note", ""))
            note_text.setWordWrap(True)
            note_text.setTextInteractionFlags(
                Qt.TextInteractionFlag.TextSelectableByMouse
            )

            note_layout.addWidget(note_title)
            note_layout.addWidget(note_text)

            layout.addWidget(note_card)

        # ─────────────────────────────
        # Delivery notice
        # ─────────────────────────────
        if self.draft.get("formalQuoteRequested"):
            notice_text = (
                "공식 견적서 요청 건입니다. "
                "PDF / 출력에서 견적서를 확인한 뒤 문자 또는 메일로 전달하세요. "
                "실제 발송이 끝난 후 견적발송완료를 눌러주세요."
            )
        else:
            notice_text = (
                "문자 또는 메일 앱을 여는 것만으로는 발송완료 처리되지 않습니다. "
                "고객에게 실제 전송을 마친 뒤 견적발송완료를 눌러주세요."
            )

        notice = QLabel(notice_text)
        notice.setObjectName("deliveryNotice")
        notice.setWordWrap(True)

        layout.addWidget(notice)

        # ─────────────────────────────
        # Action buttons
        # ─────────────────────────────
        actions = QHBoxLayout()
        actions.setSpacing(10)

        self.sms_button = QPushButton("문자 보내기")
        self.sms_button.setObjectName("smsAction")
        self.sms_button.clicked.connect(self.open_sms)
        self.sms_button.setEnabled(
            bool(self.draft.get("customerPhone"))
        )

        self.email_button = QPushButton("메일 보내기")
        self.email_button.setObjectName("mailAction")
        self.email_button.clicked.connect(self.open_email)
        self.email_button.setEnabled(
            bool(self.draft.get("customerEmail"))
        )

        self.pdf_button = QPushButton(
            "공식 견적서 PDF / 출력"
            if self.draft.get("formalQuoteRequested")
            else "견적서 PDF / 출력"
        )
        self.pdf_button.setObjectName("pdfAction")
        self.pdf_button.clicked.connect(self.preview_pdf)

        close_button = QPushButton("닫기")
        close_button.setObjectName("closeAction")
        close_button.clicked.connect(self.reject)

        self.sent_button = QPushButton("견적발송완료")
        self.sent_button.setObjectName("sentAction")
        self.sent_button.clicked.connect(self.mark_sent)
        self.sent_button.setEnabled(
            bool(self.draft.get("sourceRequestId"))
            and self.request_status == "견적확정"
        )

        actions.addWidget(self.sms_button)
        actions.addWidget(self.email_button)
        actions.addWidget(self.pdf_button)

        actions.addStretch()

        actions.addWidget(close_button)
        actions.addWidget(self.sent_button)

        layout.addLayout(actions)

    def quote_number(self):
        return (
            self.draft.get("requestReceiptNumber")
            or ("PCQ_" + self.draft["id"][:8])
        )

    def update_delivery_badges(self):
        states = (
            (self.sms_status_badge, bool(self.delivery_state.get("smsSentAt")), "문자 전송완료", "문자 미전송"),
            (self.email_status_badge, bool(self.delivery_state.get("emailSentAt")), "메일 전송완료", "메일 미전송"),
        )
        for badge, done, done_text, pending_text in states:
            badge.setText(done_text if done else pending_text)
            badge.setProperty("done", done)
            badge.style().unpolish(badge)
            badge.style().polish(badge)

    def delivery_text(self):
        amount = draft_amounts(self.draft)["total"]

        items = ", ".join(
            f"{item['species']} {item['quantity']:,}주"
            for item in self.draft["items"]
        )

        return "\n".join(
            [
                "[나무팜 견적 안내]",
                "",
                f"{self.draft['customerName']}님, 요청하신 묘목 견적을 안내드립니다.",
                "",
                f"견적번호: {self.quote_number()}",
                f"품목: {items}",
                f"판매금액: {amount:,}원",
                "",
                "확인 후 문의사항이 있으시면 연락 부탁드립니다.",
            ]
        )

    def open_sms(self):
        body = self.delivery_text()

        uri = (
            f"sms:{self.draft['customerPhone']}"
            f"?body={url_quote(body)}"
        )

        if not QDesktopServices.openUrl(
            QUrl.fromEncoded(uri.encode("utf-8"))
        ):
            QApplication.clipboard().setText(body)

            QMessageBox.information(
                self,
                "문자 작성",
                "문자 앱을 열 수 없어 견적 내용을 클립보드에 복사했습니다.",
            )

    def open_email(self):
        email = self.draft.get("customerEmail", "").strip()
        if not email:
            QMessageBox.information(self, "메일 주소 확인", "고객 이메일이 입력되지 않았습니다.")
            return
        dialog = QuoteEmailDialog(self.draft, self.manager, self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.delivery_state = dict(dialog.delivery_state or self.delivery_state)
            self.update_delivery_badges()

    def preview_pdf(self):
        print_document(
            self,
            quote_html(self.draft, True),
            "나무팜_견적서_"
            + self.quote_number().replace("/", "_"),
        )

    def mark_sent(self):
        answer = QMessageBox.question(
            self,
            "견적발송완료",
            (
                "문자 또는 메일 등으로 고객에게 "
                "실제 견적 전송을 마쳤나요?\n\n"
                "확인하면 서버 상태를 "
                "'견적발송완료'로 변경합니다."
            ),
            QMessageBox.StandardButton.Yes
            | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )

        if answer != QMessageBox.StandardButton.Yes:
            return

        self.mode = "sent"
        self.accept()


class ShippingExportDialog(QDialog):
    def __init__(self, rows, saved_sender=None, parent=None):
        super().__init__(parent)
        self.rows = copy.deepcopy(export_orders(rows))
        self.setWindowTitle("로젠 XLS · 출고자료 확인")
        self.resize(1240, 740)
        layout = QVBoxLayout(self)
        layout.addWidget(label(f"입금확인 {len(rows)}건 · 로젠 엑셀 저장", "sectionTitle"))
        layout.addWidget(label("첨부 양식의 A~X열과 빈 열을 유지합니다. 첫 줄은 제목, 실제 주문은 2행부터입니다.", "muted", True))
        sender_group = QGroupBox("발송인 · 실제 출고지를 확인하세요")
        form = QGridLayout(sender_group)
        defaults = {"name": ISSUER["businessName"], "address": ISSUER["address"], "phone": ISSUER["phone"], "mobile": ""}
        defaults.update(saved_sender or {})
        form.setColumnStretch(1, 3)
        form.setColumnStretch(3, 2)
        self.sender_inputs = {}
        for index, (key, title) in enumerate((("name", "발송인명"), ("phone", "전화"), ("address", "주소"), ("mobile", "휴대폰(선택)"))):
            widget = QLineEdit(defaults[key])
            widget.setMaxLength(300 if key == "address" else 100 if key == "name" else 20)
            self.sender_inputs[key] = widget
            form.addWidget(label(title), index // 2, index % 2 * 2)
            form.addWidget(widget, index // 2, index % 2 * 2 + 1)
        layout.addWidget(sender_group)
        common = QHBoxLayout()
        self.common_freight = QSpinBox()
        self.common_freight.setRange(-1, 99999999)
        self.common_freight.setValue(-1)
        self.common_freight.setSpecialValueText("직접 입력")
        self.common_freight.setSuffix(" 원")
        self.common_code = QLineEdit()
        self.common_code.setMaxLength(3)
        self.common_code.setPlaceholderText("예: 1 또는 010")
        self.common_jeju = QLineEdit()
        self.common_jeju.setMaxLength(3)
        self.common_jeju.setPlaceholderText("미사용 시 빈칸")
        common.addWidget(label("공통 운임"))
        common.addWidget(self.common_freight)
        common.addWidget(label("선착불 코드"))
        common.addWidget(self.common_code)
        common.addWidget(label("제주 코드"))
        common.addWidget(self.common_jeju)
        common.addWidget(button("입력값 전체 적용", self.apply_common))
        layout.addLayout(common)
        layout.addWidget(label("운임은 주문별 계약 운임 합계입니다. 고객에게 받은 배송비와 구분해 입력하세요. 선착불 코드는 로젠 설정을 확인해 입력합니다.", "muted", True))
        self.parcel_table = table(["주문번호", "수령인", "묘목 수량", "고객 배송비", "박스 수", "계약 운임", "선착불 코드", "제주 코드"])
        fill_table(self.parcel_table, [(r["metadata"]["orderNumber"], r["private"]["order"]["receiverName"],
            f"{r['metadata']['totalQty']}주", "선불" if r["private"]["order"]["isPrepaid"] else "착불", "", "", "", "") for r in rows])
        polish_table(self.parcel_table)
        center_table_columns(self.parcel_table, range(8))
        self.parcel_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Interactive)
        for index, width in enumerate((270, 110, 90, 100, 85, 125, 120, 120)):
            self.parcel_table.setColumnWidth(index, width)
        self.parcel_inputs = []
        for y in range(len(rows)):
            self.parcel_table.setRowHeight(y, 56)
            boxes, freight = QSpinBox(), QSpinBox()
            boxes.setRange(1, 999)
            boxes.setValue(1)
            freight.setRange(-1, 99999999)
            freight.setValue(-1)
            freight.setSpecialValueText("입력 필요")
            fare, jeju = QLineEdit(), QLineEdit()
            fare.setMaxLength(3)
            jeju.setMaxLength(3)
            fare.setPlaceholderText("입력 필요")
            boxes.lineEdit().setAlignment(Qt.AlignmentFlag.AlignCenter)
            freight.lineEdit().setAlignment(Qt.AlignmentFlag.AlignCenter)
            fare.setAlignment(Qt.AlignmentFlag.AlignCenter)
            jeju.setAlignment(Qt.AlignmentFlag.AlignCenter)
            for x, widget in enumerate((boxes, freight, fare, jeju), 4):
                self.parcel_table.setCellWidget(y, x, widget)
            self.parcel_inputs.append((boxes, freight, fare, jeju))
        layout.addWidget(self.parcel_table, 1)
        layout.addWidget(label("박스 수는 묘목 수량과 별도입니다. 여러 박스의 송장은 로젠에서 각각 확인하세요. 나무팜에는 현재 주문별 대표 송장 1개를 등록합니다.", "muted", True))
        self.error_label = label("", "notice", True)
        self.error_label.hide()
        layout.addWidget(self.error_label)
        actions = QHBoxLayout()
        actions.addWidget(label("저장한 XLS를 로젠에 불러온 후 공식 송장을 출력합니다.", "muted", True), 1)
        actions.addWidget(button("취소", self.reject))
        actions.addWidget(button("확인 후 XLS 저장", self.validate_export, True))
        layout.addLayout(actions)

    def apply_common(self):
        for _, freight, fare, jeju in self.parcel_inputs:
            if self.common_freight.value() >= 0:
                freight.setValue(self.common_freight.value())
            if self.common_code.text().strip():
                fare.setText(self.common_code.text().strip())
            jeju.setText(self.common_jeju.text().strip())

    def collect(self):
        sender = {k: widget.text().strip() for k, widget in self.sender_inputs.items()}
        parcels = {row["metadata"]["orderNumber"]: {"boxes": widgets[0].value(), "freight": widgets[1].value(),
                    "fareCode": widgets[2].text().strip(), "jejuCode": widgets[3].text().strip()}
                   for row, widgets in zip(self.rows, self.parcel_inputs)}
        return sender, parcels

    def validate_export(self):
        try:
            self.sender, self.parcels = self.collect()
            self.xls_data = shipping_xls(self.rows, self.parcels, self.sender)
            self.accept()
        except (UserError, UnicodeError) as error:
            self.error_label.setText(str(error))
            self.error_label.show()


class SellerWindow(MainWindow):
    PAGES = (("오늘의 업무", "OVERVIEW"), ("온라인 견적 요청", "INBOX"), ("견적 작업대", "QUOTATIONS"),
             ("판매 · 고객 관리", "ORDERS"), ("배송 · 로젠 송장", "FULFILLMENT"), ("매출 · 이익 분석", "REPORTS"), ("연결 · 설정", "SETTINGS"))

    def __init__(self, vault_path=None):
        self.workspace_ready = False
        super().__init__(vault_path)
        self.setWindowTitle("나무팜 판매관리 | NamuFarm Seller")
        self.setMinimumSize(1240, 760)
        self.resize(1920, 1080)
        self.setStyleSheet(STYLE)
        old_root = self.takeCentralWidget()
        old_pages = []
        while self.tabs.count():
            page = self.tabs.widget(0)
            self.tabs.removeTab(0)
            page.setParent(None)
            old_pages.append(page)
        self.tabs.setParent(self)
        self.tabs.hide()
        root = QWidget()
        root.setObjectName("workspace")
        self.setCentralWidget(root)
        outer = QHBoxLayout(root)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)
        sidebar = QFrame()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(242)
        side = QVBoxLayout(sidebar)
        side.setContentsMargins(18, 32, 18, 22)
        side.setSpacing(9)
        side.addWidget(label("  namufarm", "brand"))
        side.addWidget(label(f"    나무팜 판매관리 (Ver. {__version__})", "navFoot"))
        side.addSpacing(35)
        self.nav = []
        for index, (title, _) in enumerate(self.PAGES):
            widget = button(f"{index+1:02d}   {title}", lambda checked=False, i=index: self.navigate(i))
            widget.setCheckable(True)
            self.nav.append(widget)
            side.addWidget(widget)
        side.addStretch()
        outer.addWidget(sidebar)
        content = QWidget()
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(30, 22, 30, 16)
        content_layout.setSpacing(15)
        header = QHBoxLayout()
        heading = QVBoxLayout()
        self.page_eyebrow = label("", "eyebrow")
        self.page_title = label("", "pageTitle")
        heading.addWidget(self.page_eyebrow)
        heading.addWidget(self.page_title)
        header.addLayout(heading)
        header.addStretch()
        header.addWidget(label(datetime.now(SEOUL).strftime("%Y.%m.%d"), "muted"))
        self.global_search = QLineEdit()
        self.global_search.setPlaceholderText("고객 · 주문 검색   Ctrl+K")
        self.global_search.setFixedWidth(280)
        self.global_search.returnPressed.connect(self.search_all)
        header.addWidget(self.global_search)
        header.addWidget(self.sync_button)
        self.new_quote_button = button("+ 새 견적", self.edit_quote, True)
        header.addWidget(self.new_quote_button)
        content_layout.addLayout(header)
        self.message.setObjectName("notice")
        content_layout.addWidget(self.message)
        self.stack = QStackedWidget()
        self.stack.addWidget(self.build_dashboard())
        self.stack.addWidget(old_pages[0])
        self.stack.addWidget(self.build_drafts())
        self.stack.addWidget(old_pages[1])
        self.stack.addWidget(self.build_shipping())
        self.stack.addWidget(old_pages[2])
        self.stack.addWidget(old_pages[3])
        content_layout.addWidget(self.stack, 1)
        content_layout.addWidget(self.summary)
        outer.addWidget(content, 1)
        old_root.deleteLater()
        self.request_quote_button = button("견적 작성", self.quote_request, True)
        self.request_table.itemDoubleClicked.connect(self.quote_request)
        self.request_actions.addWidget(self.request_quote_button)
        for item in old_pages[0].findChildren(QLabel):
            if "수동 견적" in item.text():
                item.setText("요청을 더블클릭해 견적을 작성하세요. 견적확정 후 견적 작업대에서 고객 전달을 진행합니다.")
        for widget in self.findChildren(type(self.request_table)):
            polish_table(widget)
        self.save_button.setObjectName("primary")
        self.next_button.setObjectName("primary")
        # Keep the two transaction actions visible even at Windows 125% scaling.
        sale_actions = QHBoxLayout()
        sale_actions.addWidget(label("선택한 주문의 고객정보·원가를 확인한 뒤 저장하세요.", "muted"))
        sale_actions.addStretch()
        self.quote_print_button = button("확정 견적서 출력", self.print_saved_quote)
        sale_actions.addWidget(self.quote_print_button)
        sale_actions.addWidget(self.save_button)
        sale_actions.addWidget(self.next_button)
        old_pages[1].layout().addLayout(sale_actions)
        self.delete_button.setObjectName("danger")
        self.workspace_ready = True
        self.navigate(0)
        QShortcut(QKeySequence("Ctrl+K"), self, activated=self.global_search.setFocus)
        QShortcut(QKeySequence("Ctrl+N"), self, activated=self.edit_quote)
        QShortcut(QKeySequence("F5"), self, activated=self.manual_sync)
        self.refresh_workspace()
        self.update_controls()

    def build_dashboard(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(20)
        hero = QFrame()
        hero.setObjectName("hero")
        row = QHBoxLayout(hero)
        row.setContentsMargins(26, 23, 26, 23)
        left = QVBoxLayout()
        left.addWidget(label("좋은 나무, 빈틈없는 판매 관리", "heroTitle"))
        self.hero_subtitle = label("새 견적부터 마지막 출고까지, 오늘 처리할 일을 확인하세요.", "muted")
        left.addWidget(self.hero_subtitle)
        row.addLayout(left)
        row.addStretch()
        row.addWidget(button("출고 대기 확인 →", lambda: self.navigate(4), True))
        layout.addWidget(hero)
        metrics = QHBoxLayout()
        metrics.setSpacing(16)
        self.metrics = []
        for title, subtitle, target in (("새 견적 요청", "온라인 접수함", 1), ("견적 작업대", "확정 · 발송 관리", 2), ("출고 대기", "입금확인 · 배송완료 전", 4), ("이번 달 배송완료 매출", "공급가 기준 · 한국 시간", 5)):
            frame, box = card(title)
            value = label("—", "metricValue")
            self.metrics.append(value)
            box.addWidget(value)
            box.addWidget(label(subtitle, "muted"))
            link = button("자세히 보기  →", lambda checked=False, i=target: self.navigate(i))
            box.addWidget(link)
            metrics.addWidget(frame, 1)
        layout.addLayout(metrics)
        bottom = QHBoxLayout()
        bottom.setSpacing(20)
        frame, box = card("먼저 처리할 주문")
        box.addWidget(label("입금 · 주소 · 송장 준비 상태를 확인하고 다음 업무로 이동하세요.", "muted"))
        self.work_table = table(["고객 / 주문", "품목", "결제금액", "다음 할 일"])
        self.work_table.itemDoubleClicked.connect(self.open_work_order)
        box.addWidget(self.work_table)
        box.addWidget(button("전체 주문 보기 →", lambda: self.navigate(3)), 0, Qt.AlignmentFlag.AlignRight)
        bottom.addWidget(frame, 3)
        frame, box = card("판매 업무 흐름")
        for n, title, description in (("01", "견적 작성", "접수 요청을 더블클릭해 수량·단가를 확정하세요."),
                                       ("02", "견적확정", "확정된 견적의 문자·메일·PDF 전달과 고객 회신을 관리하세요."),
                                       ("03", "입금확인 · 출고", "입금을 확인한 주문의 로젠 송장과 배송을 준비하세요."),
                                       ("04", "배송완료 / 구매취소", "최종 상태를 확정해 매출과 업무를 정리하세요.")):
            box.addWidget(label(n + "   " + title, "sectionTitle"))
            box.addWidget(label(description, "muted", True))
        box.addStretch()
        self.health = label("보관함을 열면 운영 기록이 표시됩니다.", "notice", True)
        box.addWidget(self.health)
        bottom.addWidget(frame, 1)
        layout.addLayout(bottom, 1)
        return page

    def build_drafts(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.addWidget(label("확정된 견적을 고객에게 전달하고 후속 작업을 관리하세요.", "sectionTitle"))
        layout.addWidget(label("온라인 요청에서 견적을 확정하면 이 작업대로 이동합니다. 문자·메일·견적서 전달 후 견적발송완료 상태로 변경하세요.", "muted", True))
        self.draft_table = table(["견적번호", "상태", "작성일", "고객명", "연락처", "메일", "공식견적서", "품목", "판매금액"])
        header = self.draft_table.horizontalHeader()
        header.setStretchLastSection(False)
        header.setMinimumSectionSize(70)
        for column, width in enumerate((190, 115, 145, 100, 135, 220, 105, 320, 120)):
            header.setSectionResizeMode(column, QHeaderView.ResizeMode.Interactive)
            self.draft_table.setColumnWidth(column, width)
        header.setSectionResizeMode(7, QHeaderView.ResizeMode.Stretch)
        self.draft_table.itemDoubleClicked.connect(self.open_draft)
        layout.addWidget(self.draft_table)
        bar = QHBoxLayout()
        self.draft_delete = button("선택 견적 삭제", self.remove_draft)
        self.draft_open = button("선택 견적 열기", self.open_draft, True)
        bar.addWidget(self.draft_delete)
        bar.addStretch()
        bar.addWidget(self.draft_open)
        layout.addLayout(bar)
        return page

    def build_shipping(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.addWidget(label("출고 준비부터 송장번호까지 한 번에", "sectionTitle"))
        layout.addWidget(label("① 입금확인 주문 선택 · 로젠 XLS 저장  →  ② 로젠에 불러오기 · 송장 일괄 출력  →  ③ 송장번호 가져오기", "muted", True))
        bar = QHBoxLayout()
        self.shipping_search = QLineEdit()
        self.shipping_search.setPlaceholderText("주문번호 · 수령인 · 주소 · 송장번호 검색")
        self.shipping_search.textChanged.connect(self.refresh_shipping)
        self.shipping_filter = QComboBox()
        self.shipping_filter.addItems(["출고 대기", "송장 미등록", "송장 등록", "전체 배송 주문"])
        self.shipping_filter.currentIndexChanged.connect(self.refresh_shipping)
        bar.addWidget(self.shipping_search, 1)
        bar.addWidget(self.shipping_filter)
        bar.addWidget(button("로젠 기업택배 열기 ↗", lambda: QDesktopServices.openUrl(QUrl("https://www.ilogen.com/web/enterprise"))))
        layout.addLayout(bar)
        self.shipping_table = table(["주문번호", "수령인", "연락처", "배송주소", "수량", "배송 준비", "송장번호"])
        self.shipping_table.setSelectionMode(self.shipping_table.SelectionMode.ExtendedSelection)
        self.shipping_table.itemSelectionChanged.connect(self.select_shipping)
        layout.addWidget(self.shipping_table, 1)
        selection = QHBoxLayout()
        selection.addWidget(button("목록 전체 선택", self.shipping_table.selectAll))
        selection.addWidget(button("선택 해제", self.shipping_table.clearSelection))
        selection.addStretch()
        layout.addLayout(selection)
        self.shipping_status = label("주문을 선택하세요. Ctrl 또는 Shift로 여러 주문을 선택할 수 있습니다.", "muted", True)
        layout.addWidget(self.shipping_status)
        panel, box = card("송장번호 등록 · 출력")
        line = QHBoxLayout()
        self.tracking_input = QLineEdit()
        self.tracking_input.setPlaceholderText("로젠에서 발급받은 송장번호 11자리")
        self.tracking_input.setMaxLength(20)
        self.tracking_save = button("번호 등록", self.save_tracking, True)
        self.shipping_edit = button("배송정보 수정", self.edit_shipping_order)
        line.addWidget(self.tracking_input, 1)
        line.addWidget(self.tracking_save)
        line.addWidget(self.shipping_edit)
        box.addLayout(line)
        box.addWidget(label("송장번호는 기존 암호화 배송 메모에 함께 저장되어 휴대폰 수정 후에도 유지됩니다.", "muted", True))
        actions = QHBoxLayout()
        self.export_button = button("선택 주문 로젠 XLS 저장", self.export_shipping, True)
        self.import_button = button("송장 XLS / CSV 가져오기", self.import_shipping)
        self.print_button = button("송장번호 · 출고 확인서 출력", self.print_shipping)
        actions.addWidget(self.export_button)
        actions.addWidget(self.import_button)
        actions.addStretch()
        actions.addWidget(self.print_button)
        box.addLayout(actions)
        layout.addWidget(panel)
        layout.addWidget(label("택배 부착용 운송장은 로젠 프로그램에서 출력합니다. 나무팜은 첨부 로젠 양식의 XLS 파일로 주문을 전달합니다.", "muted", True))
        return page

    def navigate(self, index):
        self.stack.setCurrentIndex(index)
        self.page_title.setText(self.PAGES[index][0])
        self.page_eyebrow.setText("NAMUFARM  /  " + self.PAGES[index][1])
        for i, widget in enumerate(self.nav):
            widget.setChecked(i == index)

    def search_all(self):
        if not self.allow_discard():
            return
        self.dirty = False
        self.sale_search.setText(self.global_search.text())
        self.state_filter.setCurrentIndex(0)
        self.refresh_sales()
        self.navigate(3)

    def update_controls(self):
        super().update_controls()
        if not self.workspace_ready:
            return
        ready = self.manager is not None and self.task is None
        paired = ready and self.data["paired"] and not self.data["pending"]
        self.stack.setEnabled(self.task is None)
        self.new_quote_button.setEnabled(ready)
        self.request_quote_button.setEnabled(ready and self.request_table.currentRow() >= 0)
        self.draft_open.setEnabled(ready)
        self.draft_delete.setEnabled(ready)
        self.export_button.setEnabled(paired)
        self.import_button.setEnabled(paired)
        self.print_button.setEnabled(ready and not self.data["pending"])
        self.tracking_save.setEnabled(paired)
        self.shipping_edit.setEnabled(ready)
        self.quote_print_button.setEnabled(ready and not self.data["pending"] and self.selected_sale is not None and bool(self.selected_sale["private"].get("quoteDocumentJson")))
        if getattr(self, "demo_mode", False):
            for widget in (self.sync_button, self.new_quote_button, self.request_quote_button,
                           self.draft_open, self.draft_delete, self.export_button, self.import_button, self.print_button,
                           self.tracking_save, self.open_button, self.pair_button, self.report_button,
                           self.connection_file_button, self.copy_button):
                widget.setEnabled(False)

    def apply_view(self, value):
        super().apply_view(value)
        if self.workspace_ready:
            self.refresh_workspace()

    def refresh_workspace(self):
        sales = self.data["sales"]
        self.metrics[0].setText(f"{sum(r.get('status','신규') == '신규' for r in self.data['requests']):,}건")
        self.metrics[1].setText(f"{sum(r.get('status') in ('견적확정','견적발송완료') for r in self.data['requests']):,}건")
        self.metrics[2].setText(f"{sum(r['metadata']['status'] in ('입금확인','구매확정') for r in sales):,}건")
        month = datetime.now(SEOUL).strftime("%Y-%m")
        completed = [r for r in sales if r["metadata"]["status"] in ("배송완료","판매완료") and timestamp(r["metadata"]["completedAt"]).startswith(month)]
        self.metrics[3].setText(money(sum(r["metadata"]["totalPrice"] for r in completed)))
        self.work_rows = [r for r in sales if r["metadata"]["status"] in ("구매확정", "입금확인")][:12]
        rows = []
        for r in self.work_rows:
            order = r["private"]["order"]
            step = shipping_problem(order) or ("로젠 송장 등록" if not tracking_number(order) else "출고 · 배송 확인")
            rows.append((order.get("customerName") or order["orderNumber"], ", ".join(i["species"] for i in r["metadata"]["items"]), money(order["totalPrice"] + order["taxAmount"]), step))
        fill_table(self.work_table, rows)
        request_status = {r["requestId"]: r.get("status", "신규") for r in self.data["requests"]}
        draft_pairs = []
        for draft in self.data.get("drafts", []):
            source = draft.get("sourceRequestId", "")
            status = request_status.get(source, "상태확인") if source else "수기견적"
            if source and status not in ("견적확정", "견적발송완료"):
                continue
            draft_pairs.append((draft, status))
        self.draft_rows = [draft for draft, _status in draft_pairs]
        self.draft_statuses = [status for _draft, status in draft_pairs]
        fill_table(self.draft_table, [(
            d.get("requestReceiptNumber") or ("PCQ_" + d["id"][:8]),
            status,
            timestamp(d["createdAt"]),
            d["customerName"],
            d["customerPhone"],
            d.get("customerEmail") or "—",
            "요청" if d.get("formalQuoteRequested") else "—",
            ", ".join(f"{i['species']} {i['quantity']:,}주" for i in d["items"]),
            money(draft_amounts(d)["total"]),
        ) for d, status in draft_pairs])
        center_table_columns(self.draft_table, (0, 1, 2, 3, 4, 6, 8))
        pending = len(self.data["pending"])
        self.health.setText(f"전송 대기 {pending}건\n동기화하여 저장 여부를 확인하세요." if pending else "운영 시트 연결됨\n수정 중에는 자동 새로고침을 멈춥니다." if self.data["paired"] else "연결 · 설정에서 보관함을 열고\nPC를 연결해주세요.")
        self.refresh_shipping()

    def refresh_shipping(self):
        if not hasattr(self, "shipping_table"):
            return
        query = self.shipping_search.text().casefold()
        mode = self.shipping_filter.currentIndex()
        rows = []
        for row in self.data["sales"]:
            order = row["private"]["order"]
            if order["status"] == "주문취소" or query not in str(order).casefold():
                continue
            number = tracking_number(order)
            if mode == 0 and order["status"] != "입금확인":
                continue
            if mode == 1 and (order["status"] != "입금확인" or number):
                continue
            if mode == 2 and not number:
                continue
            rows.append(row)
        self.shipping_rows = rows
        self.shipping_table.blockSignals(True)
        fill_table(self.shipping_table, [(r["metadata"]["orderNumber"], r["private"]["order"].get("receiverName", ""), r["private"]["order"]["customerPhone"],
            r["private"]["order"].get("roadAddress", ""), r["metadata"]["totalQty"], shipping_problem(r["private"]["order"]) or r["metadata"]["status"], tracking_number(r["private"]["order"]) or "미등록") for r in rows])
        self.shipping_table.clearSelection()
        self.shipping_table.setCurrentCell(-1, -1)
        self.shipping_table.blockSignals(False)
        self.select_shipping()

    def selected_shipping(self):
        return [self.shipping_rows[index.row()] for index in self.shipping_table.selectionModel().selectedRows()]

    def select_shipping(self):
        if not hasattr(self, "tracking_input"):
            return
        selected = self.selected_shipping()
        self.tracking_input.setText(tracking_number(selected[0]["private"]["order"]) if len(selected) == 1 else "")
        self.shipping_status.setText(f"선택 {len(selected)}건 / 목록 {len(self.shipping_rows)}건 · 여러 주문은 Ctrl 또는 Shift로 선택하세요.")

    def edit_quote(self, checked=False, draft=None, request_status=""):
        if getattr(self, "demo_mode", False) or not self.manager or self.task or not self.allow_discard():
            return
        self.dirty = False
        dialog = QuoteDialog(draft or new_draft(), self, request_status=request_status)
        # Modal input is protected from the 60-second refresh timer as well.
        self.timer.stop()
        result = dialog.exec()
        self.timer.start()
        if result != QDialog.DialogCode.Accepted:
            return
        draft, mode = dialog.draft, dialog.mode
        if mode == "confirm":
            self.run_job("입금확인 내역을 운영 시트에 저장 중…", lambda: self.manager.confirm_draft(draft), self.quote_confirmed)
        elif mode == "finalize":
            self.run_job("견적확정 상태를 운영 시트에 저장 중…", lambda: self.manager.confirm_quote(draft), self.quote_finalized)
        else:
            online_request = bool(draft.get("sourceRequestId"))
            self.run_job("견적을 임시저장 중…", lambda: self.manager.save_draft(draft),
                         lambda value: self.draft_saved(value, online_request))

    def quote_confirmed(self, value):
        self.apply_view(value)
        self.message.setText("입금확인으로 판매 등록했습니다. 배송을 준비하세요.")
        self.navigate(3)

    def quote_finalized(self, value):
        self.apply_view(value)
        self.message.setText("견적을 확정했습니다. 견적 작업대에서 문자·메일 등 후속 작업을 진행하세요.")
        self.navigate(2)

    def draft_saved(self, value, online_request=False):
        self.apply_view(value)
        self.message.setText("견적을 이 PC에 임시저장했습니다. 단가를 확인한 뒤 견적을 확정하세요.")
        self.navigate(1 if online_request else 2)

    def draft_deleted(self, value):
        self.apply_view(value)
        self.message.setText("선택한 견적을 삭제했습니다.")
        self.navigate(2)

    def quote_request(self, *args):
        index = self.request_table.currentRow()
        if not 0 <= index < len(self.request_rows):
            return
        request = self.request_rows[index]
        status = request.get("status", "신규")
        if status in ("입금확인", "배송완료", "구매취소"):
            sale = next((r for r in self.data.get("sales", []) if r["private"]["order"].get("sourceRequestId") == request["requestId"]), None)
            if sale:
                self.show_order(sale["metadata"]["orderNumber"])
            else:
                QMessageBox.information(self, "판매 기록 확인", "판매 상태로 진행된 요청입니다. 판매 · 고객 관리에서 확인해주세요.")
            return
        existing = next((d for d in self.data.get("drafts", []) if d["sourceRequestId"] == request["requestId"]), None)
        draft = copy.deepcopy(existing) if existing else new_draft(request)
        draft["requestReceiptNumber"] = request.get("receiptNumber") or request["requestId"]
        draft["customerEmail"] = request["personal"].get("customerEmail", "")
        self.edit_quote(draft=draft, request_status=status)

    def open_draft(self, *args):
        index = self.draft_table.currentRow()
        if not 0 <= index < len(self.draft_rows):
            return
        draft = copy.deepcopy(self.draft_rows[index])
        status = self.draft_statuses[index] if index < len(self.draft_statuses) else ""
        source = draft.get("sourceRequestId", "")
        request = next((r for r in self.data.get("requests", []) if r["requestId"] == source), None) if source else None
        if request:
            draft["requestReceiptNumber"] = request.get("receiptNumber") or request["requestId"]
            draft["customerEmail"] = request["personal"].get("customerEmail", "")
        if source:
            self.open_delivery_quote(draft, status)
        else:
            self.edit_quote(draft=draft, request_status=status)

    def open_delivery_quote(self, draft, status):
        if getattr(self, "demo_mode", False) or not self.manager or self.task:
            return
        delivery_state = self.manager.quote_delivery_state(draft["id"])
        dialog = QuoteDeliveryDialog(draft, status, manager=self.manager, delivery_state=delivery_state, parent=self)
        self.timer.stop()
        result = dialog.exec()
        self.timer.start()
        if result == QDialog.DialogCode.Accepted and dialog.mode == "sent":
            self.run_job("견적발송완료 상태를 운영 시트에 저장 중…",
                         lambda: self.manager.mark_quote_sent(draft), self.quote_sent)

    def quote_sent(self, value):
        self.apply_view(value)
        self.message.setText("견적발송완료로 저장했습니다. 고객 회신과 입금 여부를 확인하세요.")
        self.navigate(2)

    def remove_draft(self):
        index = self.draft_table.currentRow()
        if not self.manager or not 0 <= index < len(self.draft_rows):
            return
        if QMessageBox.question(self, "견적 삭제", "선택한 PC 견적을 삭제할까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) == QMessageBox.StandardButton.Yes:
            identifier = self.draft_rows[index]["id"]
            self.run_job("견적 삭제 중…", lambda: self.manager.delete_draft(identifier), self.draft_deleted)

    def open_work_order(self, item):
        self.show_order(self.work_rows[item.row()]["metadata"]["orderNumber"])

    def show_order(self, number):
        if not self.allow_discard():
            return
        self.dirty = False
        self.state_filter.setCurrentIndex(0)
        self.sale_search.setText(number)
        self.refresh_sales()
        if self.sale_rows:
            self.sale_table.selectRow(0)
        self.navigate(3)

    def edit_shipping_order(self):
        rows = self.selected_shipping()
        if len(rows) == 1:
            self.show_order(rows[0]["metadata"]["orderNumber"])
        else:
            QMessageBox.information(self, "주문 선택", "수정할 주문을 한 건 선택해주세요.")

    def save_tracking(self):
        rows = self.selected_shipping()
        if len(rows) != 1:
            QMessageBox.information(self, "주문 선택", "송장번호를 등록할 주문을 한 건 선택해주세요.")
            return
        if not self.allow_discard():
            return
        row, number = rows[0], self.tracking_input.text()
        if tracking_number(row["private"]["order"]) and QMessageBox.question(self, "송장번호 수정", "등록된 송장번호를 변경할까요? 실제 발급 내역을 확인해주세요.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
            return
        self.run_job("송장번호 저장 중…", lambda: self.manager.register_tracking(row["metadata"]["orderNumber"], row["metadata"]["revision"], number), self.tracking_saved)

    def tracking_saved(self, value):
        self.apply_view(value)
        self.message.setText("송장번호를 운영 시트에 저장했습니다. 송장 등록만으로 배송완료 처리되지 않습니다.")

    def export_shipping(self):
        if not self.manager or self.task or getattr(self, "demo_mode", False) or not self.allow_discard():
            return
        try:
            numbers = [r["metadata"]["orderNumber"] for r in export_orders(self.selected_shipping())]
            require(not self.data["pending"], "전송 대기를 먼저 동기화해주세요.")
            self.run_job("출고 전 최신 입금·배송 상태 확인 중…", self.manager.sync,
                         lambda view: self.prepare_shipping_export(view, numbers))
        except UserError as error:
            QMessageBox.warning(self, "출고자료 확인", str(error))

    def prepare_shipping_export(self, view, numbers):
        self.apply_view(view)
        self.timer.stop()
        try:
            current = {r["metadata"]["orderNumber"]: r for r in self.data["sales"]}
            require(all(n in current for n in numbers), "일부 주문이 삭제·만료되었습니다. 다시 선택해주세요.")
            rows = export_orders([current[n] for n in numbers])
            preferences = self.manager.shipping_export_preferences()
            repeated = any(tracking_number(r["private"]["order"]) or r["metadata"]["orderNumber"] in preferences.get("history", {}) for r in rows)
            if repeated and QMessageBox.question(self, "중복 출고 확인", "송장이 등록되었거나 이 PC에서 최근 내보낸 주문이 포함되어 있습니다. 로젠 접수 내역을 확인한 뒤 다시 내보낼까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
            dialog = ShippingExportDialog(rows, preferences.get("sender"), self)
            if dialog.exec() != QDialog.DialogCode.Accepted:
                return
            name = datetime.now(SEOUL).strftime("나무팜_로젠출고_%Y%m%d_%H%M%S.xls")
            path, _ = QFileDialog.getSaveFileName(self, "로젠 양식 XLS 저장", name, "Excel 97-2003 (*.xls)")
            if not path:
                return
            if not Path(path).suffix:
                path += ".xls"
            require(Path(path).suffix.lower() == ".xls", "확장자는 .xls로 저장해주세요.")
            target = QSaveFile(path)
            require(target.open(QIODevice.OpenModeFlag.WriteOnly), "파일을 열지 못했습니다. 저장 위치와 Excel 사용 여부를 확인해주세요.")
            if target.write(dialog.xls_data) != len(dialog.xls_data):
                target.cancelWriting()
                raise UserError("파일을 모두 저장하지 못했습니다. 저장 공간을 확인해주세요.")
            require(target.commit(), "파일 저장을 완료하지 못했습니다. Excel에서 파일을 닫고 다시 저장해주세요.")
            self.message.setText(f"{len(rows)}건 XLS 저장 완료 · 로젠에서 '{UPLOAD_SHEET}'의 2행부터 불러오세요. 송장 출력은 로젠에서 진행합니다.")
            try:
                self.manager.remember_shipping_export(dialog.sender, dialog.parcels)
            except Exception:
                QMessageBox.warning(self, "파일 저장 완료", "XLS 파일은 저장됐지만 이 PC의 내보내기 이력을 기록하지 못했습니다. 같은 주문을 다시 내보내지 않도록 로젠 접수 내역을 확인해주세요.")
        except (UserError, OSError) as error:
            QMessageBox.warning(self, "출고자료 확인", str(error))
        finally:
            self.timer.start()

    def import_shipping(self):
        if not self.manager or not self.allow_discard():
            return
        path, _ = QFileDialog.getOpenFileName(self, "송장번호 가져오기", "", "송장 파일 (*.xls *.csv)")
        if not path:
            return
        try:
            with Path(path).open("rb") as source:
                parser = parse_tracking_xls if Path(path).suffix.lower() == ".xls" else parse_tracking_csv
                assignments = parser(source.read(2 * 1024 * 1024 + 1), self.data["sales"])
            dialog = QDialog(self)
            dialog.setWindowTitle("송장번호 가져오기 확인")
            dialog.resize(760, 480)
            layout = QVBoxLayout(dialog)
            layout.addWidget(label(f"검증한 {len(assignments)}건을 등록합니다. 기존 번호가 다른 주문은 개별 수정이 필요합니다.", "muted", True))
            preview = table(["주문번호", "로젠 송장번호"])
            fill_table(preview, assignments.items())
            polish_table(preview)
            center_table_columns(preview)
            layout.addWidget(preview)
            actions = QHBoxLayout()
            actions.addWidget(button("취소", dialog.reject))
            actions.addWidget(button("확인한 번호 등록", dialog.accept, True))
            layout.addLayout(actions)
            if dialog.exec() == QDialog.DialogCode.Accepted:
                self.run_job("송장번호를 순서대로 등록 중…", lambda: self.manager.import_tracking(assignments), self.tracking_saved)
        except (UserError, OSError) as error:
            QMessageBox.warning(self, "송장 파일 확인", str(error))

    def print_shipping(self):
        try:
            require(not self.data["pending"], "서버 저장을 확인한 후 출력해주세요.")
            print_document(self, shipping_html(self.selected_shipping()), "나무팜_송장번호_출고확인서")
        except UserError as error:
            QMessageBox.warning(self, "출력 확인", str(error))

    def print_saved_quote(self):
        if self.selected_sale and self.selected_sale["private"].get("quoteDocumentJson") and not self.data["pending"]:
            doc = json.loads(self.selected_sale["private"]["quoteDocumentJson"])
            print_document(self, quote_document_html(doc), "나무팜_" + doc["number"])

    def accept_manager(self, result):
        super().accept_manager(result)
        if self.workspace_ready:
            self.navigate(0 if self.data["paired"] else 6)

    def manual_sync(self):
        if not getattr(self, "demo_mode", False):
            super().manual_sync()


