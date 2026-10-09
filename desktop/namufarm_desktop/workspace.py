"""FHD seller workspace, built on the existing verified sync controller."""
import copy
import json
from datetime import datetime
from pathlib import Path

from PySide6.QtCore import Qt, QUrl, QSaveFile, QIODevice
from PySide6.QtGui import QColor, QDesktopServices, QFont, QKeySequence, QShortcut, QTextDocument, QPageSize
from PySide6.QtPrintSupport import QPrinter, QPrintDialog
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QDialog, QFileDialog, QFormLayout,
    QFrame, QGridLayout, QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QListWidget,
    QMessageBox, QPlainTextEdit, QPushButton, QScrollArea, QSplitter, QStackedWidget,
    QTableWidgetItem, QTextBrowser, QVBoxLayout, QWidget, QSpinBox)

from .app import MainWindow, table, fill_table, money, timestamp
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
QHeaderView::section{background:#edf2e9;color:#647661;border:0;border-bottom:1px solid #dce5d8;padding:11px 9px;font-weight:600;}
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
    """Local draft editor; the parent runs network confirmation off the UI thread."""
    def __init__(self, draft, parent=None):
        super().__init__(parent)
        self.draft, self.mode = copy.deepcopy(draft), ""
        self.setWindowTitle("견적 작업대")
        self.resize(1280, 840)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        head = QHBoxLayout()
        head.addWidget(label("견적 작업대", "pageTitle"))
        head.addStretch()
        head.addWidget(label("PC 초안 · 구매 확정 시 운영 시트에 저장", "muted"))
        layout.addLayout(head)
        split = QSplitter()
        info = QWidget()
        form = QFormLayout(info)
        self.fields = {}
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
        content.addWidget(label("수기 견적 품목으로 등록합니다. 휴대폰 재고 수량은 차감하지 않습니다.", "muted", True))
        self.items = table(["묘목명", "규격", "수량", "판매 단가", "매입 단가", "판매 금액"])
        self.items.setEditTriggers(self.items.EditTrigger.DoubleClicked | self.items.EditTrigger.EditKeyPressed | self.items.EditTrigger.AnyKeyPressed)
        self.items.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        polish_table(self.items)
        self.items.itemChanged.connect(self.recalculate)
        content.addWidget(self.items)
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
        split.setSizes([420, 780])
        layout.addWidget(split, 1)
        footer = QHBoxLayout()
        footer.addWidget(button("발행자 정보", self.edit_issuer))
        footer.addWidget(button("견적안 미리보기 / PDF", self.preview))
        footer.addStretch()
        footer.addWidget(button("닫기", self.reject))
        footer.addWidget(button("초안 저장", lambda: self.finish("save")))
        footer.addWidget(button("구매 확정 → 판매 등록", lambda: self.finish("confirm"), True))
        layout.addLayout(footer)
        for item in draft["items"]:
            self.add_item(item)
        if not draft["items"]:
            self.add_item()
        self.recalculate()
        self.initial = self.raw_state()

    def add_item(self, item=None):
        item = item if isinstance(item, dict) else {"species": "", "specification": "", "quantity": 1, "unitPrice": 0, "unitCost": 0}
        self.items.blockSignals(True)
        index = self.items.rowCount()
        self.items.insertRow(index)
        for column, key in enumerate(("species", "specification", "quantity", "unitPrice", "unitCost")):
            self.items.setItem(index, column, QTableWidgetItem(str(item[key])))
        amount = QTableWidgetItem("")
        amount.setFlags(amount.flags() & ~Qt.ItemFlag.ItemIsEditable)
        self.items.setItem(index, 5, amount)
        self.items.blockSignals(False)
        self.recalculate()

    def remove_item(self):
        index = self.items.currentRow()
        if index >= 0:
            self.items.removeRow(index)
            self.recalculate()

    def raw_state(self):
        return ([field.text() for field in self.fields.values()], self.note.toPlainText(), self.prepaid.isChecked(), self.cost.isChecked(),
                [[self.items.item(r, c).text() for c in range(5)] for r in range(self.items.rowCount())], copy.deepcopy(self.draft["issuer"]))

    def reject(self):
        if hasattr(self, "initial") and self.raw_state() != self.initial:
            if QMessageBox.question(self, "미저장 견적", "저장하지 않은 입력을 버리고 닫을까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
        super().reject()

    def collect(self, validate=True):
        draft = copy.deepcopy(self.draft)
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
            for row, item in enumerate(draft["items"]):
                self.items.item(row, 5).setText(f"{item['quantity'] * item['unitPrice']:,}")
            self.items.blockSignals(False)
            amount = draft_amounts(draft)
            self.total.setText(f"합계 {amount['total']:,}원")
            self.amount_detail.setText(f"{amount['quantity']:,}주   ·   공급가 {amount['supply']:,}원   ·   세액 {amount['tax']:,}원\n매입 원가 {amount['cost']:,}원")
        except UserError:
            self.total.setText("품목·수량·금액을 입력해주세요")
            self.amount_detail.setText("판매 금액은 수량 × 판매 단가로 계산합니다.")

    def edit_issuer(self):
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
        try:
            draft = self.collect()
            print_document(self, quote_html(draft), "나무팜_견적안_" + draft["id"][:8])
        except UserError as error:
            QMessageBox.warning(self, "견적 입력 확인", str(error))

    def finish(self, mode):
        try:
            draft = self.collect()
            if mode == "confirm" and QMessageBox.question(self, "구매 확정", "고객이 이 견적의 구매를 확정했나요?\n품목·수량·금액은 판매 등록 후 변경할 수 없습니다. 입금은 별도로 확인합니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
            self.draft, self.mode = draft, mode
            self.accept()
        except UserError as error:
            QMessageBox.warning(self, "견적 입력 확인", str(error))


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
        side.addWidget(label("    나무팜 판매관리", "navFoot"))
        side.addSpacing(35)
        self.nav = []
        for index, (title, _) in enumerate(self.PAGES):
            widget = button(f"{index+1:02d}   {title}", lambda checked=False, i=index: self.navigate(i))
            widget.setCheckable(True)
            self.nav.append(widget)
            side.addWidget(widget)
        side.addStretch()
        side.addWidget(label("  나무를 파는 일에\n  더 집중할 수 있도록.", "navFoot"))
        side.addSpacing(16)
        side.addWidget(self.lock_button)
        side.addWidget(label("  NAMUFARM DESKTOP  /  0.3", "navFoot"))
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
        self.request_quote_button = button("선택 요청으로 견적 작성", self.quote_request, True)
        old_pages[0].layout().addWidget(self.request_quote_button, 0, Qt.AlignmentFlag.AlignRight)
        for item in old_pages[0].findChildren(QLabel):
            if "수동 견적" in item.text():
                item.setText("요청을 선택해 견적을 작성하세요. 구매 확정된 요청은 판매 관리로 이동합니다.")
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
        for title, subtitle, target in (("새 견적 요청", "온라인 접수함", 1), ("입금 대기", "구매가 확정된 주문", 3), ("출고 대기", "입금 확인 · 배송 확인 전", 4), ("이번 달 완료 매출", "공급가 기준 · 한국 시간", 5)):
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
        for n, title, description in (("01", "견적 작성", "접수 요청에 수량·단가를 채워 견적안을 만드세요."),
                                       ("02", "구매 확정 · 입금 확인", "확정한 금액을 보관하고 실제 입금을 확인하세요."),
                                       ("03", "로젠 송장 · 출고", "배송자료를 넘기고 발급받은 송장번호를 등록하세요."),
                                       ("04", "배송 확인 · 판매 완료", "배송과 원가를 확인해 매출과 이익을 정리하세요.")):
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
        layout.addWidget(label("고객과 이야기하며 견적을 완성하세요.", "sectionTitle"))
        layout.addWidget(label("초안은 이 PC에 암호화해 30일 보관합니다. 구매 확정 후 운영 시트와 휴대폰에서 판매 기록을 조회할 수 있습니다.", "muted", True))
        self.draft_table = table(["작성일", "고객명", "연락처", "품목", "합계", "견적 번호"])
        self.draft_table.itemDoubleClicked.connect(self.open_draft)
        layout.addWidget(self.draft_table)
        bar = QHBoxLayout()
        self.draft_delete = button("선택 초안 삭제", self.remove_draft)
        self.draft_open = button("선택 초안 이어서 작성", self.open_draft, True)
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
            for widget in (self.sync_button, self.inbox_sync_button, self.new_quote_button, self.request_quote_button,
                           self.draft_open, self.draft_delete, self.export_button, self.import_button, self.print_button,
                           self.tracking_save, self.open_button, self.pair_button, self.report_button, self.lock_button,
                           self.connection_file_button, self.copy_button):
                widget.setEnabled(False)

    def apply_view(self, value):
        super().apply_view(value)
        if self.workspace_ready:
            self.refresh_workspace()

    def apply_inbox(self, value):
        super().apply_inbox(value)
        self.data["drafts"] = value.get("drafts", [])
        if self.workspace_ready:
            self.refresh_workspace()

    def refresh_workspace(self):
        sales = self.data["sales"]
        self.metrics[0].setText(f"{len(self.data['requests']):,}건")
        self.metrics[1].setText(f"{sum(r['metadata']['status'] == '구매확정' for r in sales):,}건")
        self.metrics[2].setText(f"{sum(r['metadata']['status'] == '입금확인' for r in sales):,}건")
        month = datetime.now(SEOUL).strftime("%Y-%m")
        completed = [r for r in sales if r["metadata"]["status"] == "판매완료" and timestamp(r["metadata"]["completedAt"]).startswith(month)]
        self.metrics[3].setText(money(sum(r["metadata"]["totalPrice"] for r in completed)))
        self.work_rows = [r for r in sales if r["metadata"]["status"] in ("구매확정", "입금확인")][:12]
        rows = []
        for r in self.work_rows:
            order = r["private"]["order"]
            step = shipping_problem(order) or ("로젠 송장 등록" if not tracking_number(order) else "출고 · 배송 확인")
            rows.append((order.get("customerName") or order["orderNumber"], ", ".join(i["species"] for i in r["metadata"]["items"]), money(order["totalPrice"] + order["taxAmount"]), step))
        fill_table(self.work_table, rows)
        self.draft_rows = self.data.get("drafts", [])
        fill_table(self.draft_table, [(timestamp(d["createdAt"]), d["customerName"], d["customerPhone"], ", ".join(i["species"] for i in d["items"]), money(draft_amounts(d)["total"]), "PCQ_" + d["id"][:8]) for d in self.draft_rows])
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

    def edit_quote(self, checked=False, draft=None):
        if getattr(self, "demo_mode", False) or not self.manager or self.task or not self.allow_discard():
            return
        self.dirty = False
        dialog = QuoteDialog(draft or new_draft(), self)
        # Modal input is protected from the 60-second refresh timer as well.
        self.timer.stop()
        result = dialog.exec()
        self.timer.start()
        if result != QDialog.DialogCode.Accepted:
            return
        draft, mode = dialog.draft, dialog.mode
        if mode == "confirm":
            self.run_job("구매 확정 내역을 운영 시트에 저장 중…", lambda: self.manager.confirm_draft(draft), self.quote_confirmed)
        else:
            self.run_job("견적 초안을 암호화해 저장 중…", lambda: self.manager.save_draft(draft), self.draft_saved)

    def quote_confirmed(self, value):
        self.apply_view(value)
        self.message.setText("구매 확정 내역을 운영 시트에 저장했습니다. 실제 입금을 확인한 후 배송을 준비하세요.")
        self.navigate(3)

    def draft_saved(self, value):
        self.apply_view(value)
        self.message.setText("견적 초안을 이 PC에 저장했습니다. 구매 확정하면 운영 시트에 반영됩니다.")
        self.navigate(2)

    def quote_request(self):
        index = self.request_table.currentRow()
        if 0 <= index < len(self.request_rows):
            request = self.request_rows[index]
            existing = next((d for d in self.data.get("drafts", []) if d["sourceRequestId"] == request["requestId"]), None)
            self.edit_quote(draft=existing or new_draft(request))

    def open_draft(self, *args):
        index = self.draft_table.currentRow()
        if 0 <= index < len(self.draft_rows):
            self.edit_quote(draft=self.draft_rows[index])

    def remove_draft(self):
        index = self.draft_table.currentRow()
        if not self.manager or not 0 <= index < len(self.draft_rows):
            return
        if QMessageBox.question(self, "견적 초안 삭제", "선택한 PC 견적 초안을 삭제할까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) == QMessageBox.StandardButton.Yes:
            identifier = self.draft_rows[index]["id"]
            self.run_job("초안 삭제 중…", lambda: self.manager.delete_draft(identifier), self.draft_saved)

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

    def lock(self):
        super().lock()
        if self.manager is None and self.workspace_ready:
            self.global_search.clear()
            self.shipping_search.clear()
            self.navigate(6)

    def accept_manager(self, result):
        super().accept_manager(result)
        if self.workspace_ready:
            self.navigate(0 if self.data["paired"] else 6)

    def manual_sync(self):
        if not getattr(self, "demo_mode", False):
            super().manual_sync()


