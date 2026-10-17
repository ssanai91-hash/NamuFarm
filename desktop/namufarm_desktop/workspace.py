"""FHD seller workspace, built on the existing verified sync controller."""
import copy
import json
import uuid
import tempfile
from datetime import datetime
from importlib.resources import files as package_files
from pathlib import Path
from html import escape as html_escape

from PySide6.QtCore import QSettings, Qt, QUrl, QSaveFile, QIODevice, QTimer, Signal, Slot, QRectF
from PySide6.QtGui import (QColor, QDesktopServices, QFont, QFontMetricsF, QImage, QIntValidator,
    QKeySequence, QPainter, QPdfWriter, QPen, QPixmap, QShortcut, QTextDocument, QPageSize)
from PySide6.QtPrintSupport import QPrinter, QPrintDialog
from PySide6.QtWidgets import (QApplication, QButtonGroup, QCheckBox, QComboBox, QDialog, QDoubleSpinBox, QFileDialog, QFormLayout,
    QFrame, QGridLayout, QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QListWidget,
    QMessageBox, QPlainTextEdit, QPushButton, QRadioButton, QScrollArea, QSplitter, QStackedWidget,
    QSizePolicy, QStyle, QSystemTrayIcon, QTableWidget, QTableWidgetItem, QTextBrowser, QVBoxLayout, QWidget, QSpinBox)

from . import __version__
from .ui_theme import (align_table, fit_window, scroll_dialog, scroll_page, workspace_style,
                       CustomerDelegate, StatusDelegate, customer_reference, CompactDateDelegate,
                       NavigationButton, size_table_to_rows, collapsible)
from .ui_icons import set_button_icon
from .app import MainWindow, Task, table, fill_table, money, timestamp
from .commerce import (ISSUER, new_draft, draft_amounts, validate_draft, quote_html, quote_document_html, shipping_html,
                       shipping_problem, parse_tracking_csv, tracking_number)
from .domain import SEOUL, profit, require
from .errors import UserError
from .shipping_excel import shipping_xls, parse_tracking_xls, export_orders, UPLOAD_SHEET

STYLE = workspace_style()


def label(text, name="", wrap=False):
    widget = QLabel(text)
    widget.setTextFormat(Qt.TextFormat.PlainText)
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
    layout.setContentsMargins(16, 12, 16, 12)
    layout.setSpacing(12)
    layout.addWidget(label(title, "sectionTitle"))
    return frame, layout


def polish_table(widget):
    large = bool(QApplication.instance().property("namufarmLargeFont"))
    widget.verticalHeader().setMinimumSectionSize(64 if large else 56)
    widget.verticalHeader().setDefaultSectionSize(64 if large else 56)
    widget.setShowGrid(False)
    widget.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
    widget.horizontalHeader().setDefaultAlignment(Qt.AlignmentFlag.AlignCenter)
    if widget.property("visibleItemRows"):
        size_table_to_rows(widget, int(widget.property("visibleItemRows")))


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
        row.setContentsMargins(4, 0, 4, 0)
        row.setSpacing(5)

        self.minus = QPushButton("-")
        self.minus.setToolTip("판매 단가 1,000원 내리기")
        self.minus.setFixedSize(44, 44)
        self.minus.setAccessibleName("판매 단가 1,000원 내리기")
        self.minus.clicked.connect(lambda: self.adjust(-self.STEP))
        row.addWidget(self.minus)

        self.input = QLineEdit(str(max(0, int(value or 0))))
        self.input.setValidator(QIntValidator(0, self.MAX_PRICE, self.input))
        self.input.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.input.setPlaceholderText("판매 단가")
        self.input.setAccessibleName("판매 단가 (원)")
        self.input.textChanged.connect(lambda _text: self.changed.emit())
        row.addWidget(self.input, 1)

        self.plus = QPushButton("+")
        self.plus.setToolTip("판매 단가 1,000원 올리기")
        self.plus.setFixedSize(44, 44)
        self.plus.setAccessibleName("판매 단가 1,000원 올리기")
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
    # Kept as a compatibility hook; presentation is now based on column meaning.
    align_table(widget)


def _quote_font(pixel_size, bold=False):
    font = QFont("Malgun Gothic")
    font.setPixelSize(pixel_size)
    font.setBold(bold)
    return font


def _paint_text(painter, rect, text, pixel_size=13, bold=False,
                align=Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                color=QColor("#111111"), wrap=False):
    painter.setPen(color)
    painter.setFont(_quote_font(pixel_size, bold))
    flags = align
    if wrap:
        flags |= Qt.TextFlag.TextWordWrap
    painter.drawText(QRectF(*rect), int(flags), str(text))


def render_official_quote_image(draft):
    """Render the official quote at the same 955x1342 geometry as the supplied paper form."""
    validate_draft(draft)
    width, height = 955, 1342
    image = QImage(width, height, QImage.Format.Format_ARGB32)
    image.fill(QColor("white"))

    painter = QPainter(image)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
    painter.setRenderHint(QPainter.RenderHint.TextAntialiasing, True)
    black = QColor("#222222")
    thin = QPen(black, 1)
    thick = QPen(black, 2)

    def line(x1, y1, x2, y2, pen=thin):
        painter.setPen(pen)
        painter.drawLine(int(x1), int(y1), int(x2), int(y2))

    def box(x1, y1, x2, y2, pen=thin):
        painter.setPen(pen)
        painter.drawRect(int(x1), int(y1), int(x2 - x1), int(y2 - y1))

    def fitted_text(rect, text, size=16, bold=False,
                    align=Qt.AlignmentFlag.AlignCenter, color=black, minimum=9):
        x, y, w, h = rect
        text = str(text)
        font = _quote_font(size, bold)
        while font.pixelSize() > minimum:
            metrics = QFontMetricsF(font)
            if metrics.horizontalAdvance(text) <= max(2, w - 8) and metrics.height() <= max(2, h - 3):
                break
            font.setPixelSize(font.pixelSize() - 1)
        painter.setPen(color)
        painter.setFont(font)
        painter.drawText(QRectF(x + 4, y + 1, max(1, w - 8), max(1, h - 2)), int(align), text)

    def phone(value):
        digits = "".join(ch for ch in str(value) if ch.isdigit())
        if len(digits) == 11:
            return f"{digits[:3]}-{digits[3:7]}-{digits[7:]}"
        if len(digits) == 10:
            return f"{digits[:3]}-{digits[3:6]}-{digits[6:]}"
        return str(value)

    quote_number = draft.get("requestReceiptNumber") or ("PCQ_" + draft["id"][:8])
    issuer = draft["issuer"]
    issued = datetime.now(SEOUL)
    amounts = draft_amounts(draft)

    # --- Top section: pixel positions measured from the supplied 955x1342 reference form. ---
    fitted_text((26, 13, 205, 24), f"NO. {quote_number}", 12, False,
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
    line(27, 35, 226, 35)

    title_font = _quote_font(39, False)
    title_font.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, 15)
    painter.setFont(title_font)
    painter.setPen(black)
    painter.drawText(QRectF(292, 38, 298, 44), int(Qt.AlignmentFlag.AlignCenter), "견 적 서")
    line(294, 82, 587, 82, QPen(QColor("#555555"), 2))

    # Customer/date block: the original uses two horizontal rules, not boxed fields.
    fitted_text((42, 99, 250, 31), f"{issued.year}년  {issued.month}월  {issued.day}일",
                17, False, Qt.AlignmentFlag.AlignCenter)
    line(27, 132, 293, 132)
    fitted_text((120, 132, 220, 23), draft["customerName"], 16, False,
                Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
    fitted_text((52, 153, 286, 24), f"{phone(draft['customerPhone'])} 귀하", 16, False,
                Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
    line(27, 177, 343, 177)

    fitted_text((27, 184, 355, 31), "아래와같이 견적 합니다.", 16, False,
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)

    # Supplier table exactly follows the reference grid:
    # x = 434,468,587,(712/765/835),910 and y = 109,132,155,177,208,231.
    sx0, sx1, sx2, sx3 = 434, 468, 587, 910
    sy = [109, 132, 155, 177, 208, 231]
    box(sx0, sy[0], sx3, sy[-1], thick)
    line(sx1, sy[0], sx1, sy[-1])
    line(sx2, sy[0], sx2, sy[-1])
    for yy in sy[1:-1]:
        line(sx1, yy, sx3, yy)

    fitted_text((sx0, sy[0], sx1 - sx0, sy[-1] - sy[0]), "공\n급\n자", 17, True,
                Qt.AlignmentFlag.AlignCenter)
    fitted_text((sx1, sy[0], sx2 - sx1, sy[1] - sy[0]), "등록번호", 15, True)
    fitted_text((sx2, sy[0], sx3 - sx2, sy[1] - sy[0]), issuer["businessNumber"], 15, False)

    # 상호 / 성명
    line(765, sy[1], 765, sy[2])
    line(835, sy[1], 835, sy[2])
    fitted_text((sx1, sy[1], sx2 - sx1, sy[2] - sy[1]), "상   호", 15, True)
    fitted_text((sx2, sy[1], 765 - sx2, sy[2] - sy[1]), issuer["businessName"], 15, False)
    fitted_text((765, sy[1], 835 - 765, sy[2] - sy[1]), "성명", 14, True)
    fitted_text((835, sy[1], sx3 - 835, sy[2] - sy[1]), issuer["representative"], 15, False, minimum=11)

    fitted_text((sx1, sy[2], sx2 - sx1, sy[3] - sy[2]), "사업장주소", 15, True)
    fitted_text((sx2, sy[2], sx3 - sx2, sy[3] - sy[2]), issuer["address"], 15, False, minimum=11)

    # 업태 / 종목
    line(712, sy[3], 712, sy[4])
    line(765, sy[3], 765, sy[4])
    fitted_text((sx1, sy[3], sx2 - sx1, sy[4] - sy[3]), "업   태", 15, True)
    fitted_text((sx2, sy[3], 712 - sx2, sy[4] - sy[3]),
                issuer.get("businessType") or "농업,도소매", 15, False, minimum=11)
    fitted_text((712, sy[3], 765 - 712, sy[4] - sy[3]), "종목", 14, True)
    fitted_text((765, sy[3], sx3 - 765, sy[4] - sy[3]),
                issuer.get("category") or "묘목,화훼", 15, False, minimum=11)

    fitted_text((sx1, sy[4], sx2 - sx1, sy[5] - sy[4]), "연락처", 15, True)
    fitted_text((sx2, sy[4], sx3 - sx2, sy[5] - sy[4]),
                "T:(043)732-0980 F:732-0981", 14, False, minimum=11)

    # Same stamp image cropped from the user's original form. Load it through
    # package resources so editable installs and packaged wheels behave the same.
    stamp = QImage()
    try:
        stamp_bytes = package_files("namufarm_desktop").joinpath("assets", "quote_stamp.png").read_bytes()
        stamp = QImage.fromData(stamp_bytes, "PNG")
    except (FileNotFoundError, OSError):
        pass
    if not stamp.isNull():
        painter.drawImage(QRectF(850, 82, 60, 56), stamp)
    else:
        # Visible emergency fallback: never silently omit the representative seal.
        painter.save()
        seal_pen = QPen(QColor("#a71919"), 2)
        painter.setPen(seal_pen)
        painter.drawEllipse(QRectF(854, 84, 52, 52))
        _paint_text(painter, (861, 87, 38, 46), "오\n진\n웅", 10, True,
                    Qt.AlignmentFlag.AlignCenter, QColor("#a71919"), True)
        painter.restore()

    # --- Main form grid: measured directly from the reference. ---
    left, right = 26, 910
    total_top, header_top, data_top, sum_top, notes_top, bottom = 231, 280, 312, 1055, 1087, 1268

    # Total box, split at x=292.
    box(left, total_top, right, header_top, thick)
    line(292, total_top, 292, header_top)
    fitted_text((left + 3, total_top, 292 - left - 3, header_top - total_top),
                "합계금액(공급가액+세액)", 20, True,
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, minimum=14)
    fitted_text((303, total_top, right - 303, header_top - total_top),
                f"₩{amounts['total']:,}원", 19, True,
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)

    # Exact original column boundaries.
    xs = [26, 226, 292, 343, 468, 588, 713, 910]
    headers = ["품   명", "규격", "수량", "단   가", "공급가액", "세   액", "비   고"]
    box(left, header_top, right, notes_top, thick)
    for x in xs[1:-1]:
        line(x, header_top, x, notes_top)
    line(left, header_top, right, header_top)
    line(left, data_top, right, data_top)

    for i, title in enumerate(headers):
        fitted_text((xs[i], header_top, xs[i + 1] - xs[i], data_top - header_top),
                    title, 16, False, Qt.AlignmentFlag.AlignCenter, minimum=12)

    # Original has 23 data rows. The small 32/33px alternation is preserved.
    row_lines = [312, 344, 377, 409, 441, 474, 506, 538, 571, 603, 635, 668,
                 700, 732, 764, 797, 829, 862, 894, 926, 958, 991, 1023, 1055]
    for yy in row_lines[1:]:
        line(left, yy, right, yy)

    items = []
    for item in draft.get("items", []):
        qty = int(item["quantity"])
        unit = int(item["unitPrice"])
        items.append((item["species"], item.get("specification", ""), qty, unit, qty * unit, "", ""))
    if draft.get("isPrepaid") and int(draft.get("shippingFee", 0) or 0) > 0:
        fee = int(draft["shippingFee"])
        items.append(("운반비", "선불", 1, fee, fee, "", "택배비"))

    for row_index in range(min(len(items), 23)):
        y1, y2 = row_lines[row_index], row_lines[row_index + 1]
        values = items[row_index]
        for col_index, value in enumerate(values):
            if isinstance(value, int):
                value = f"{value:,}"
            if col_index == 0:
                align = Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter
                size = 15
            elif col_index in (3, 4, 5):
                align = Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter
                size = 15
            else:
                align = Qt.AlignmentFlag.AlignCenter
                size = 15
            fitted_text((xs[col_index], y1, xs[col_index + 1] - xs[col_index], y2 - y1),
                        value, size, False, align, minimum=10)

    # Sum row y=1055..1087.
    fitted_text((xs[0], sum_top, xs[1] - xs[0], notes_top - sum_top), "계", 16, False)
    fitted_text((xs[4], sum_top, xs[5] - xs[4], notes_top - sum_top),
                f"{amounts['supply']:,}", 15, False,
                Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
    if amounts["tax"]:
        fitted_text((xs[5], sum_top, xs[6] - xs[5], notes_top - sum_top),
                    f"{amounts['tax']:,}", 15, False,
                    Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)

    # Important notes, split exactly at x=226.
    box(left, notes_top, right, bottom, thick)
    line(226, notes_top, 226, bottom)
    fitted_text((left, notes_top, 200, bottom - notes_top), "중요사항", 18, False)

    payment = draft.get("paymentAccount", "").strip() or "농협 328-12-035671 오진웅"
    shipping_note = (
        f"택배비 선불 {int(draft.get('shippingFee', 0) or 0):,}원 (총금액 포함)"
        if draft.get("isPrepaid")
        else f"택배비 후불 / 선불 시 {int(draft.get('shippingFee', 0) or 0):,}원"
    )
    note = draft.get("note", "").strip()
    note4 = shipping_note + (f" / 요청 : {note}" if note else "")

    fitted_text((228, 1093, 672, 30), f"1. {payment}", 14, False,
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
    fitted_text((228, 1130, 672, 31), "2. 견적후 일정기간후 금액변동 있을수 있음.", 14, False,
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
    fitted_text((228, 1196, 672, 31),
                "3. 계산서 또는 현금영수증발행은 정상가격 처리후 7일이내 발행가능함.", 13, False,
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, minimum=10)
    fitted_text((228, 1226, 672, 37), f"4. {note4}", 13, False,
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, minimum=9)

    painter.end()
    return image


def write_temporary_quote_pdf(image, path):
    """Write a temporary A4 PDF without touching the Windows printer subsystem."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        path.unlink()

    writer = QPdfWriter(str(path))
    writer.setPageSize(QPageSize(QPageSize.PageSizeId.A4))
    writer.setResolution(300)

    painter = QPainter(writer)
    if not painter.isActive():
        raise UserError("공식견적서 PDF를 임시 생성하지 못했습니다.")
    page_rect = writer.pageLayout().paintRectPixels(writer.resolution())
    printed = image.scaled(
        page_rect.size(),
        Qt.AspectRatioMode.KeepAspectRatio,
        Qt.TransformationMode.SmoothTransformation,
    )
    x = page_rect.x() + (page_rect.width() - printed.width()) // 2
    y = page_rect.y() + (page_rect.height() - printed.height()) // 2
    painter.drawImage(x, y, printed)
    painter.end()

    require(path.is_file() and path.stat().st_size > 0, "공식견적서 PDF를 임시 생성하지 못했습니다.")
    return path


def preview_temporary_quote(parent, image):
    """Preview/print an in-memory A4 quote image without creating a local file."""
    dialog = QDialog(parent)
    dialog.setWindowTitle("공식 견적서 미리보기")
    screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
    available = screen.availableGeometry() if screen else None
    if available:
        dialog.resize(min(1180, available.width() - 50), min(1000, available.height() - 50))
        max_preview_w = max(520, dialog.width() - 90)
        max_preview_h = max(680, dialog.height() - 125)
    else:
        dialog.resize(1120, 940)
        max_preview_w, max_preview_h = 1000, 820

    layout = QVBoxLayout(dialog)
    layout.setContentsMargins(12, 12, 12, 10)

    page = QLabel()
    page.setAlignment(Qt.AlignmentFlag.AlignCenter)
    page.setStyleSheet("QLabel{background:#dfe3df;border:0;padding:12px;}")
    pixmap = QPixmap.fromImage(image)
    scaled = pixmap.scaled(max_preview_w, max_preview_h,
                           Qt.AspectRatioMode.KeepAspectRatio,
                           Qt.TransformationMode.SmoothTransformation)
    page.setPixmap(scaled)
    layout.addWidget(page, 1)

    row = QHBoxLayout()
    row.addWidget(label("A4 비율 임시 생성 문서 · 별도 파일로 저장하지 않습니다.", "muted"))
    row.addStretch()

    def print_only():
        printer = QPrinter(QPrinter.PrinterMode.HighResolution)
        printer.setPageSize(QPageSize(QPageSize.PageSizeId.A4))
        if QPrintDialog(printer, dialog).exec() != QDialog.DialogCode.Accepted:
            return
        painter = QPainter(printer)
        page_rect = printer.pageLayout().paintRectPixels(printer.resolution())
        printed = image.scaled(page_rect.size(),
                               Qt.AspectRatioMode.KeepAspectRatio,
                               Qt.TransformationMode.SmoothTransformation)
        x = page_rect.x() + (page_rect.width() - printed.width()) // 2
        y = page_rect.y() + (page_rect.height() - printed.height()) // 2
        painter.drawImage(x, y, printed)
        painter.end()

    row.addWidget(button("프린터 출력", print_only))
    row.addWidget(button("닫기", dialog.accept, True))
    layout.addLayout(row)
    dialog.exec()


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
        self.setObjectName("quoteEditor")
        self.resize(1160 if self.simple_request else 1280, 760 if self.simple_request else 840)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 16, 20, 16)
        layout.setSpacing(8)

        head = QHBoxLayout()
        head.addWidget(label("견적 작업대", "pageTitle"))
        head.addStretch()
        layout.addLayout(head)

        split = QSplitter()
        self.fields = {}
        if self.simple_request:
            info = QWidget()
            form = QFormLayout(info)
            form.setContentsMargins(0, 0, 0, 0)
            form.setVerticalSpacing(4)
            form.setFormAlignment(Qt.AlignmentFlag.AlignTop)
            for title, value in (("견적번호", draft.get("requestReceiptNumber", "") or draft.get("sourceRequestId", "")),
                                 ("고객명", draft.get("customerName", "")),
                                 ("연락처", draft.get("customerPhone", "")),
                                 ("이메일", draft.get("customerEmail", "") or "미입력"),
                                 ("지역", draft.get("requestRegion", ""))):
                field = QLineEdit(str(value))
                field.setReadOnly(True)
                field.setAccessibleName(title)
                field.setToolTip(str(value))
                field.setCursorPosition(0)
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
            self.note = QPlainTextEdit(draft.get("note", ""))
            self.note.setReadOnly(True)
            self.note.setFixedHeight(64 if draft.get("note") else 44)
            info_scroll = QScrollArea()
            info_scroll.setWidgetResizable(True)
            info_scroll.setWidget(info)
            info_scroll.setMinimumWidth(330)
            info_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
            split.addWidget(info_scroll)
            self.cost = QCheckBox()
        else:
            info = QWidget()
            form = QFormLayout(info)
            form.setContentsMargins(0, 0, 0, 0)
            form.setVerticalSpacing(4)
            form.setFormAlignment(Qt.AlignmentFlag.AlignTop)
            for key, title in (("customerName", "고객명"), ("customerPhone", "연락처"), ("customerEmail", "이메일"),
                               ("receiverName", "수령인"), ("zipNo", "우편번호"), ("roadAddress", "도로명 주소"), ("detailAddress", "상세 주소"),
                               ("validUntil", "견적 유효기간"), ("paymentAccount", "입금계좌 안내")):
                field = QLineEdit(str(draft.get(key, "")))
                field.setPlaceholderText(title)
                field.setAccessibleName(title)
                field.setToolTip(str(value))
                field.setCursorPosition(0)
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
        content.setContentsMargins(12, 0, 0, 0)
        content.setSpacing(8)
        content.addWidget(label("견적 품목", "sectionTitle"))
        if self.simple_request:
            self.items = table(["묘목명", "수량", "판매 금액", "판매 단가"])
        else:
            content.addWidget(label("수기 견적 품목으로 등록합니다. 휴대폰 재고 수량은 차감하지 않습니다.", "muted", True))
            self.items = table(["묘목명", "규격", "수량", "판매 단가", "매입 단가", "판매 금액"])
        self.items.setEditTriggers(self.items.EditTrigger.DoubleClicked | self.items.EditTrigger.EditKeyPressed | self.items.EditTrigger.AnyKeyPressed)
        self.items.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        polish_table(self.items)
        if self.simple_request:
            self.items.set_column_layout(((0, 3), (1, 1), (3, 2.8), (2, 2)))
        else:
            self.items.set_column_layout(((0, 3), (1, 1.6), (2, 1), (3, 1.5), (4, 1.5), (5, 1.8)))
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

        if self.simple_request:
            request_title = label("요청사항", "sectionTitle")
            layout.addWidget(request_title)
            layout.addWidget(self.note)

        footer = QHBoxLayout()
        if not self.simple_request:
            footer.addWidget(button("발행자 정보", self.edit_issuer))
            footer.addWidget(button("견적안 미리보기 / PDF", self.preview))
        footer.addStretch()
        footer.addWidget(button("닫기", self.reject))
        if self.simple_request:
            self.confirm_button = button("견적 확정", lambda: self.finish("finalize"), True)
            self.confirm_button.setEnabled(request_status in ("", "신규"))
            set_button_icon(self.confirm_button, "check", "#ffffff")
            footer.addWidget(self.confirm_button)
        else:
            footer.addWidget(button("입금확인 · 판매 등록", lambda: self.finish("confirm"), True))
        layout.addLayout(footer)
        scroll_dialog(self, layout)

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
                cell.setTextAlignment((Qt.AlignmentFlag.AlignLeft if column == 0 else Qt.AlignmentFlag.AlignRight) | Qt.AlignmentFlag.AlignVCenter)
                cell.setFlags(cell.flags() & ~Qt.ItemFlag.ItemIsEditable)
                self.items.setItem(index, column, cell)
            price_editor = UnitPriceEditor(item["unitPrice"], self.items)
            price_editor.changed.connect(self.recalculate)
            self.items.setCellWidget(index, 3, price_editor)
            self.items.setRowHeight(index, max(self.items.verticalHeader().defaultSectionSize(), 60))
        else:
            for column, key in enumerate(("species", "specification", "quantity", "unitPrice", "unitCost")):
                cell = QTableWidgetItem(str(item[key]))
                if column >= 2:
                    cell.setTextAlignment((Qt.AlignmentFlag.AlignLeft if column == 0 else Qt.AlignmentFlag.AlignRight) | Qt.AlignmentFlag.AlignVCenter)
                self.items.setItem(index, column, cell)
            amount = QTableWidgetItem("")
            amount.setTextAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
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
        else:
            item_state = [[self.items.item(r, c).text() for c in range(5)] for r in range(self.items.rowCount())]
        return ([field.text() for field in self.fields.values()], self.note.toPlainText(), item_state,
                copy.deepcopy(self.draft["issuer"]))

    def reject(self):
        if hasattr(self, "initial") and self.raw_state() != self.initial:
            if QMessageBox.question(self, "미저장 견적", "저장하지 않은 입력을 버리고 닫을까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
        super().reject()

    def collect(self, validate=True):
        draft = copy.deepcopy(self.draft)
        if self.simple_request:
            draft.update(shippingFee=0, isPrepaid=False, taxAmount=0, costConfirmed=False, items=[])
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
                self.amount_detail.setText(
                    f"총 수량 {amount['quantity']:,}주 · 묘목 금액 {amount['supply']:,}원"
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
            require(mode in ("finalize", "confirm"), "견적 확정 또는 판매 등록을 선택해주세요.")
            draft = self.collect()
            if mode == "finalize" and QMessageBox.question(self, "견적 확정", "판매 단가와 합계를 확인했나요?\n확정하면 온라인 요청 상태가 '견적확정'으로 바뀌고 확정 견적으로 이동합니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
            if mode == "confirm" and QMessageBox.question(self, "입금확인", "고객의 구매와 입금을 확인했나요?\n품목·수량·금액은 판매 등록 후 변경할 수 없습니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
            self.draft, self.mode = draft, mode
            self.accept()
        except UserError as error:
            QMessageBox.warning(self, "견적 입력 확인", str(error))


class QuoteSmsDialog(QDialog):
    """견적 문자 작성, 실제 발송, 최종 성공/실패 확인 팝업."""

    def __init__(self, draft, manager=None, parent=None):
        super().__init__(parent)
        self.draft = copy.deepcopy(draft)
        self.manager = manager
        try:
            self.delivery_state = manager.quote_delivery_state(self.draft["id"]) if manager else {}
        except Exception:
            self.delivery_state = {}
        self.profile_task = None
        self.send_task = None
        self.status_task = None
        self.poll_count = 0
        self.max_auto_polls = 20
        self.status_timer = QTimer(self)
        self.status_timer.setSingleShot(True)
        self.status_timer.setInterval(3000)
        self.status_timer.timeout.connect(self.check_status)

        self.setObjectName("quoteSmsDialog")
        self.setWindowTitle("견적 문자 보내기")
        self.setSizeGripEnabled(True)

        screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
        available = screen.availableGeometry() if screen else None
        if available:
            width = min(860, max(700, int(available.width() * 0.66)))
            height = min(900, max(720, int(available.height() * 0.88)))
            width = min(width, max(620, available.width() - 40))
            height = min(height, max(620, available.height() - 50))
            self.resize(width, height)
        else:
            self.resize(840, 820)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        layout.setSpacing(12)
        layout.addWidget(label("견적 문자 보내기", "deliveryTitle"))
        layout.addWidget(label("고객 번호와 견적 내용을 확인한 뒤 문자를 전송합니다.", "deliverySubtitle", True))

        address_card = QFrame()
        address_card.setObjectName("emailAddressCard")
        address = QGridLayout(address_card)
        address.setContentsMargins(16, 12, 16, 12)
        address.setHorizontalSpacing(12)
        address.setVerticalSpacing(8)

        self.recipient_name = QLineEdit(self.draft.get("customerName", ""))
        self.recipient_name.setReadOnly(True)
        self.recipient_phone = QLineEdit(self.draft.get("customerPhone", ""))
        self.recipient_phone.setReadOnly(True)
        self.sender_phone = QLineEdit("발신 설정 확인 중…")
        self.sender_phone.setReadOnly(True)

        address.addWidget(label("받는 사람"), 0, 0)
        address.addWidget(self.recipient_name, 0, 1)
        address.addWidget(label("수신번호"), 0, 2)
        address.addWidget(self.recipient_phone, 0, 3)
        address.addWidget(label("발신번호"), 1, 0)
        address.addWidget(self.sender_phone, 1, 1, 1, 3)
        address.setColumnStretch(1, 1)
        address.setColumnStretch(3, 2)
        layout.addWidget(address_card)

        layout.addWidget(label("문자 내용", "emailSectionTitle"))
        self.message = QPlainTextEdit(self.default_message())
        self.message.setMinimumHeight(260)
        layout.addWidget(self.message, 1)

        self.status = label("문자 발신 설정을 확인하고 있습니다.", "smsSendStatus", True)
        layout.addWidget(self.status)

        actions = QHBoxLayout()
        self.cancel_button = button("닫기", self.reject)
        self.check_button = QPushButton("결과 확인")
        self.check_button.setEnabled(bool(self.delivery_state.get("smsMessageId")))
        self.check_button.clicked.connect(self.check_status)
        self.send_button = QPushButton("문자 전송")
        self.send_button.setObjectName("smsSendPrimary")
        self.send_button.setEnabled(False)
        self.send_button.clicked.connect(self.send_sms)
        actions.addStretch()
        actions.addWidget(self.check_button)
        actions.addWidget(self.cancel_button)
        actions.addWidget(self.send_button)
        layout.addLayout(actions)
        scroll_dialog(self, layout)

        QTimer.singleShot(0, self.load_profile)

    def quote_number(self):
        return self.draft.get("requestReceiptNumber") or ("PCQ_" + self.draft["id"][:8])

    def default_message(self):
        customer = self.draft.get("customerName") or "고객"
        lines = [
            f"{customer}님, 안녕하세요.",
            "나무팜농원입니다.",
            "",
            "요청하신 묘목 견적을 안내드립니다.",
            f"견적번호 : {self.quote_number()}",
            "",
        ]
        for index, item in enumerate(self.draft.get("items", []), 1):
            quantity = int(item["quantity"])
            amount = quantity * int(item["unitPrice"])
            lines.extend([
                f"품목{index} : {item['species']} {quantity:,}주",
                f"판매금액 : {amount:,}원",
                "",
            ])
        total = draft_amounts(self.draft)["total"]
        shipping = (
            f"택배비 : 선불 {int(self.draft.get('shippingFee', 0) or 0):,}원 (총금액 포함)"
            if self.draft.get("isPrepaid")
            else f"택배비 : 후불 / 선불 시 {int(self.draft.get('shippingFee', 0) or 0):,}원"
        )
        lines.extend([
            f"총금액 : {total:,}원",
            shipping,
            "",
            "견적 확인 후 문의사항이 있으시면 연락 부탁드립니다.",
            "010-4446-0304 / 043-732-0980",
        ])
        return "\n".join(lines).strip()

    def load_profile(self):
        if not self.manager:
            self.sender_phone.setText("PC 연결 필요")
            self.status.setText("문자 전송은 PC를 서버에 연결한 뒤 사용할 수 있습니다.")
            return
        self.send_button.setEnabled(False)
        task = Task(self.manager.sms_profile, None, self)
        self.profile_task = task
        task.completed.connect(self.profile_loaded)
        task.start()

    @Slot(object, str, bool)
    def profile_loaded(self, value, error, conflict):
        task = self.profile_task
        if task is not None:
            task.wait()
            self.profile_task = None
            task.deleteLater()
        if error:
            self.sender_phone.setText("확인 실패")
            self.status.setText(error)
            self.send_button.setEnabled(False)
            return
        if not isinstance(value, dict) or value.get("configured") is not True:
            self.sender_phone.setText("설정 필요")
            self.status.setText("문자 발신 설정이 필요합니다. 관리자 설정을 확인해주세요.")
            self.send_button.setEnabled(False)
            return

        sender = str(value.get("senderPhone") or "")
        self.sender_phone.setText(sender)
        self.send_button.setEnabled(bool(self.recipient_phone.text().strip()) and bool(sender))
        # Opening the popup never replays an earlier result. Only the outcome
        # of a send initiated in this open window is shown as success/failure.
        self.status.setText("문자 발신 준비 완료")

    def send_sms(self):
        message = self.message.toPlainText().strip()
        if not message:
            QMessageBox.warning(self, "문자 확인", "문자 내용을 입력해주세요.")
            return
        if not self.manager or self.send_task or self.status_task:
            return
        self.status_timer.stop()
        self.poll_count = 0
        self.message.setEnabled(False)
        self.send_button.setEnabled(False)
        self.check_button.setEnabled(False)
        self.status.setText("문자를 전송하고 있습니다…")
        task = Task(lambda: self.manager.send_quote_sms(self.draft, message), None, self)
        self.send_task = task
        task.completed.connect(self.sms_sent)
        task.start()

    @Slot(object, str, bool)
    def sms_sent(self, value, error, conflict):
        task = self.send_task
        if task is not None:
            task.wait()
            self.send_task = None
            task.deleteLater()
        if error:
            self.message.setEnabled(True)
            self.send_button.setEnabled(True)
            self.check_button.setEnabled(bool(self.delivery_state.get("smsMessageId")))
            self.status.setText("문자전송 실패")
            return
        if not isinstance(value, dict):
            self.message.setEnabled(True)
            self.send_button.setEnabled(True)
            self.status.setText("문자 전송 결과를 확인하지 못했습니다. 다시 시도해주세요.")
            return

        self.delivery_state = dict(value.get("delivery") or {})
        self.sender_phone.setText(str(value.get("senderPhone") or self.sender_phone.text()))
        self.check_button.setEnabled(True)
        self.status.setText("문자전송 결과 확인중…")
        self.status_timer.start()

    def check_status(self, checked=False):
        if not self.manager or self.status_task or self.send_task:
            return
        if not self.delivery_state.get("smsMessageId"):
            self.status.setText("확인할 문자 발송 기록이 없습니다.")
            return
        self.check_button.setEnabled(False)
        task = Task(lambda: self.manager.refresh_quote_sms_status(self.draft["id"]), None, self)
        self.status_task = task
        task.completed.connect(self.sms_status_checked)
        task.start()

    @Slot(object, str, bool)
    def sms_status_checked(self, value, error, conflict):
        task = self.status_task
        if task is not None:
            task.wait()
            self.status_task = None
            task.deleteLater()
        if error:
            self.check_button.setEnabled(True)
            self.status.setText("문자전송 결과 확인중…")
            return
        if not isinstance(value, dict):
            self.check_button.setEnabled(True)
            self.status.setText("문자전송 결과 확인중…")
            return

        self.delivery_state = dict(value)
        state = self.delivery_state.get("smsState", "")
        if state == "success":
            self.status_timer.stop()
            self.status.setText("문자전송 성공")
            self.check_button.setEnabled(False)
            return
        if state == "failed":
            self.status_timer.stop()
            self.status.setText("문자전송 실패")
            self.message.setEnabled(True)
            self.send_button.setEnabled(True)
            self.check_button.setEnabled(True)
            return

        self.poll_count += 1
        self.check_button.setEnabled(True)
        if self.poll_count < self.max_auto_polls:
            self.status.setText("문자전송 결과 확인중…")
            self.status_timer.start()
        else:
            self.status.setText("문자전송 결과 확인중…")

    def reject(self):
        if self.profile_task or self.send_task or self.status_task:
            self.status.setText("현재 문자 처리가 끝난 뒤 창을 닫아주세요.")
            return
        self.status_timer.stop()
        super().reject()


class QuoteEmailDialog(QDialog):
    """메일 내용 확인과 실제 전송에 집중한 간결한 견적 메일 창."""

    SENDER_EMAIL = "ssanai91@gmail.com"

    def __init__(self, draft, manager=None, parent=None, attachment_path=None):
        super().__init__(parent)
        self.draft = copy.deepcopy(draft)
        self.manager = manager
        self.attachment_path = Path(attachment_path) if attachment_path else None
        self.delivery_state = {}
        self.profile_task = None
        self.send_task = None
        self.setObjectName("quoteEmailDialog")
        self.setWindowTitle("견적 메일 보내기")
        self.setSizeGripEnabled(True)

        screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
        available = screen.availableGeometry() if screen else None
        if available:
            width = min(1000, max(760, int(available.width() * 0.78)))
            height = min(900, max(700, int(available.height() * 0.90)))
            width = min(width, max(620, available.width() - 40))
            height = min(height, max(560, available.height() - 50))
            self.resize(width, height)
        else:
            self.resize(980, 860)

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

        if self.attachment_path is not None:
            attachment_row = QHBoxLayout()
            attachment_row.setSpacing(12)
            attachment_row.addWidget(label("첨부파일", "emailSectionTitle"))
            self.attachment_label = QLabel(self.attachment_path.name)
            self.attachment_label.setObjectName("quoteFileName")
            attachment_row.addWidget(self.attachment_label, 1)
            layout.addLayout(attachment_row)

        layout.addWidget(label("메일 내용", "emailSectionTitle"))

        self.message = QPlainTextEdit(self.default_message())
        self.message.setMinimumHeight(420)
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
        scroll_dialog(self, layout)

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
        # Connect directly to a QObject slot. A lambda here can run in the worker
        # thread in PySide, which makes subsequent widget access unsafe.
        task.completed.connect(self.profile_loaded)
        task.start()

    @Slot(object, str, bool)
    def profile_loaded(self, value, error, conflict):
        task = self.profile_task
        if task is not None:
            task.wait()
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
        task = Task(
            lambda: self.manager.send_quote_email(
                self.draft, subject, plain, html,
                attachment_path=self.attachment_path,
            ),
            None, self
        )
        self.send_task = task
        # Keep all dialog/widget work on the GUI thread. In particular, the
        # success QMessageBox and accept() must never be called by the worker.
        task.completed.connect(self.email_sent)
        task.start()

    @Slot(object, str, bool)
    def email_sent(self, value, error, conflict):
        task = self.send_task
        if task is not None:
            task.wait()
            self.send_task = None
            task.deleteLater()
        if error:
            self.subject.setEnabled(True)
            self.message.setEnabled(True)
            self.cancel_button.setEnabled(True)
            self.send_button.setEnabled(True)
            self.status.setText(error)
            return
        if not isinstance(value, dict):
            self.subject.setEnabled(True)
            self.message.setEnabled(True)
            self.cancel_button.setEnabled(True)
            self.send_button.setEnabled(True)
            self.status.setText("메일 전송 결과를 확인하지 못했습니다. 다시 시도해주세요.")
            return
        self.delivery_state = dict(value.get("delivery") or {})
        self.sender_email.setText(self.SENDER_EMAIL)
        attached = bool(value.get("attachmentName"))
        self.status.setText("메일 전송완료" + (" · 공식견적서 첨부완료" if attached else ""))
        detail = f"{self.recipient_email.text()} 주소로 견적 메일을 전송했습니다."
        if attached:
            detail += f"\n\n첨부파일: {value['attachmentName']}"
        QMessageBox.information(self, "메일 전송완료", detail)
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
        if self.draft.get("sourceRequestId") and int(self.draft.get("shippingFee", 0) or 0) <= 0:
            self.draft["shippingFee"] = 6000
        self.manager = manager
        self.delivery_state = dict(delivery_state or {})
        self.generated_quote_image = None
        self.generated_quote_path = None
        self.request_status = (
            request_status
            or ("수기견적" if not draft.get("sourceRequestId") else "상태확인")
        )
        self.mode = ""

        self.setObjectName("quoteDeliveryDialog")
        self.setWindowTitle("견적 전달")
        self.setSizeGripEnabled(True)

        screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
        available = screen.availableGeometry() if screen else None
        if available:
            width = min(1240, max(1080, int(available.width() * 0.84)))
            height = min(980, max(860, int(available.height() * 0.94)))
            width = min(width, max(900, available.width() - 30))
            height = min(height, max(760, available.height() - 30))
            self.resize(width, height)
        else:
            self.resize(1180, 940)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 16, 20, 16)
        layout.setSpacing(12)

        # ─────────────────────────────
        # Header
        # ─────────────────────────────
        head = QHBoxLayout()
        head.setSpacing(12)

        title_box = QVBoxLayout()
        title_box.setSpacing(3)

        title = label(self.draft.get("customerName") or "고객명 미입력", "deliveryTitle", True)

        subtitle = label("견적 " + self.quote_number(), "deliverySubtitle", True)

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
            badge.setMinimumWidth(110)
            badge.setFixedHeight(30)
            badge.setAlignment(Qt.AlignmentFlag.AlignCenter)
            delivery_badges.addWidget(badge)
        self.update_delivery_badges()
        head.addLayout(delivery_badges)

        status = QLabel(self.request_status)
        status.setObjectName("quoteStatusBadge")
        status.setAlignment(Qt.AlignmentFlag.AlignCenter)
        status.setMinimumWidth(140)
        status.setFixedHeight(32)

        summary_head = QVBoxLayout()
        summary_head.addWidget(status)
        head.addLayout(summary_head)
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
            column = (index % 2) * 2
            row = index // 2

            caption_label = QLabel(caption)
            caption_label.setObjectName("infoCaption")

            value_label = label(str(value), "infoValue", True)
            value_label.setMinimumWidth(0)
            value_label.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
            value_label.setObjectName("infoValue")
            value_label.setTextInteractionFlags(
                Qt.TextInteractionFlag.TextSelectableByMouse
            )

            info.addWidget(caption_label, row, column)
            info.addWidget(value_label, row, column + 1)

        info.setColumnStretch(1, 1)
        info.setColumnStretch(3, 1)

        layout.addWidget(info_card)

        # ─────────────────────────────
        # Quote items — main area
        # ─────────────────────────────
        items_card = QFrame()
        items_card.setObjectName("quoteItemsCard")

        items_layout = QVBoxLayout(items_card)
        items_layout.setContentsMargins(16, 12, 16, 12)
        items_layout.setSpacing(10)

        items_head = QHBoxLayout()

        items_title_box = QVBoxLayout()
        items_title_box.setSpacing(2)

        items_title = QLabel("견적 품목")
        items_title.setObjectName("quoteItemsTitle")

        items_title_box.addWidget(items_title)

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
        polish_table(self.items)

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

        size_table_to_rows(self.items, 4)
        items_layout.addWidget(self.items)

        # Shipping decision belongs to the delivery stage, after item prices are confirmed.
        shipping_card = QFrame()
        shipping_card.setObjectName("deliveryShippingCard")
        shipping_layout = QHBoxLayout(shipping_card)
        shipping_layout.setContentsMargins(16, 10, 16, 10)
        shipping_layout.setSpacing(12)

        shipping_title = QLabel("택배비용")
        shipping_title.setObjectName("deliveryShippingTitle")
        shipping_layout.addWidget(shipping_title)

        self.shipping_cod = QRadioButton("후불")
        self.shipping_prepaid = QRadioButton("선불")
        self.shipping_prepaid.setChecked(bool(self.draft.get("isPrepaid")))
        self.shipping_cod.setChecked(not bool(self.draft.get("isPrepaid")))
        shipping_layout.addWidget(self.shipping_cod)
        shipping_layout.addWidget(self.shipping_prepaid)

        shipping_layout.addSpacing(12)
        self.shipping_fee_label = QLabel("선불 금액")
        self.shipping_fee_label.setObjectName("deliveryShippingFeeLabel")
        shipping_layout.addWidget(self.shipping_fee_label)

        self.shipping_fee = QLineEdit(str(int(self.draft.get("shippingFee", 0) or 6000)))
        self.shipping_fee.setObjectName("deliveryShippingFee")
        self.shipping_fee.setValidator(QIntValidator(0, 2147483647, self.shipping_fee))
        self.shipping_fee.setAlignment(Qt.AlignmentFlag.AlignRight)
        shipping_layout.addWidget(self.shipping_fee)

        self.shipping_status = QLabel()
        self.shipping_status.setObjectName("deliveryShippingStatus")
        shipping_layout.addWidget(self.shipping_status, 1)

        items_layout.addWidget(shipping_card)

        # Total amount band
        total_band = QFrame()
        total_band.setObjectName("quoteTotalBand")

        total_layout = QHBoxLayout(total_band)
        total_layout.setContentsMargins(20, 12, 20, 12)

        total_caption = QLabel("총금액")
        total_caption.setObjectName("quoteTotalCaption")

        self.total_value = QLabel()
        self.total_value.setObjectName("quoteTotalValue")

        total_layout.addWidget(total_caption)
        total_layout.addStretch()
        total_layout.addWidget(self.total_value)

        items_layout.addWidget(total_band)

        self.shipping_cod.toggled.connect(self.recalculate_shipping)
        self.shipping_prepaid.toggled.connect(self.recalculate_shipping)
        self.shipping_fee.textChanged.connect(self.recalculate_shipping)
        self.recalculate_shipping()

        layout.addWidget(items_card)

        details = QWidget()
        details_layout = QVBoxLayout(details)
        details_layout.setContentsMargins(0, 0, 0, 0)
        if self.draft.get("note"):
            details_layout.addWidget(label(self.draft["note"], "infoValue", True))

        # Delivery notice
        # ─────────────────────────────
        if self.request_status == "견적발송완료":
            notice_text = (
                "견적 전달이 완료된 건입니다. 실제 고객 입금을 확인하면 입금확인을 눌러 "
                "판매 · 고객 관리로 이동하세요. 입금 전에는 견적 작업대에 그대로 유지됩니다."
            )
        elif self.draft.get("formalQuoteRequested"):
            notice_text = (
                "공식 견적서 요청 건입니다. "
                "견적서를 확인한 뒤 문자 또는 메일로 전달하세요. "
                "실제 발송이 끝난 후 견적발송완료를 눌러주세요."
            )
        else:
            notice_text = (
                "고객에게 실제 견적 전달을 마친 뒤 견적발송완료를 눌러주세요. "
                "그 다음 고객 입금을 확인하면 판매 · 고객 관리 단계로 이동합니다."
            )

        notice = QLabel(notice_text)
        notice.setObjectName("deliveryNotice")
        notice.setWordWrap(True)

        details_layout.addWidget(notice)
        layout.addWidget(collapsible("요청사항 · 전달 안내", details))

        # ─────────────────────────────
        # Action buttons
        # ─────────────────────────────
        actions = QHBoxLayout()
        actions.setSpacing(8)

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

        self.generate_quote_button = QPushButton("공식견적서 생성")
        self.generate_quote_button.setObjectName("pdfAction")
        self.generate_quote_button.clicked.connect(self.generate_official_quote)

        self.preview_quote_button = QPushButton("미리보기")
        self.preview_quote_button.setObjectName("pdfAction")
        self.preview_quote_button.setEnabled(False)
        self.preview_quote_button.clicked.connect(self.preview_official_quote)

        self.quote_file_label = QLabel("공식 견적서 미생성")
        self.quote_file_label.setObjectName("quoteFileName")

        close_button = QPushButton("닫기")
        close_button.setObjectName("closeAction")
        close_button.clicked.connect(self.reject)

        self.sent_button = QPushButton("견적발송완료")
        self.sent_button.setObjectName("sentAction")
        self.sent_button.clicked.connect(self.mark_sent)
        self.sent_button.setVisible(self.request_status == "견적확정")
        self.sent_button.setEnabled(self.request_status == "견적확정")

        self.payment_button = QPushButton("입금확인")
        self.payment_button.setObjectName("sentAction")
        self.payment_button.clicked.connect(self.confirm_payment)
        self.payment_button.setVisible(self.request_status == "견적발송완료")
        self.payment_button.setEnabled(self.request_status == "견적발송완료")

        actions.addWidget(label("고객 전달", "muted"))
        actions.addWidget(self.sms_button)
        actions.addWidget(self.email_button)
        actions.addWidget(self.generate_quote_button)
        actions.addWidget(self.preview_quote_button)
        actions.addStretch()
        finish_actions = QHBoxLayout()
        finish_actions.setSpacing(12)
        self.quote_file_label.setWordWrap(True)
        self.quote_file_label.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        finish_actions.addWidget(self.quote_file_label, 1)
        finish_actions.addWidget(close_button)
        finish_actions.addWidget(self.sent_button)
        finish_actions.addWidget(self.payment_button)
        footer = QVBoxLayout()
        footer.setSpacing(8)
        footer.addLayout(actions)
        footer.addLayout(finish_actions)
        layout.addLayout(footer)
        self.items.set_column_layout(((0, 3.6), (1, 1.2), (2, 1.8), (3, 2)))
        set_button_icon(self.sms_button, "message")
        set_button_icon(self.email_button, "mail")
        set_button_icon(self.generate_quote_button, "document")
        set_button_icon(self.sent_button, "check", "#ffffff")
        set_button_icon(self.payment_button, "check", "#ffffff")
        scroll_dialog(self, layout)

    def quote_number(self):
        return (
            self.draft.get("requestReceiptNumber")
            or ("PCQ_" + self.draft["id"][:8])
        )

    def update_delivery_badges(self):
        sms_state = self.delivery_state.get("smsState", "")
        if sms_state == "success":
            sms_text, sms_style, sms_done = "문자 전송완료", "success", True
        elif sms_state == "failed":
            sms_text, sms_style, sms_done = "문자 전송실패", "failed", False
        elif sms_state == "pending" or self.delivery_state.get("smsMessageId"):
            sms_text, sms_style, sms_done = "문자 결과확인", "pending", False
        else:
            sms_text, sms_style, sms_done = "문자 미전송", "idle", False

        self.sms_status_badge.setText(sms_text)
        self.sms_status_badge.setProperty("done", sms_done)
        self.sms_status_badge.setProperty("state", sms_style)
        self.sms_status_badge.style().unpolish(self.sms_status_badge)
        self.sms_status_badge.style().polish(self.sms_status_badge)

        email_done = bool(self.delivery_state.get("emailSentAt"))
        self.email_status_badge.setText("메일 전송완료" if email_done else "메일 미전송")
        self.email_status_badge.setProperty("done", email_done)
        self.email_status_badge.setProperty("state", "success" if email_done else "idle")
        self.email_status_badge.style().unpolish(self.email_status_badge)
        self.email_status_badge.style().polish(self.email_status_badge)

    def apply_shipping(self, validate=True):
        text = self.shipping_fee.text().strip()
        if validate:
            require(text.isascii() and text.isdigit(), "선불 택배비를 0 이상의 정수로 입력해주세요.")
        fee = int(text) if text.isascii() and text.isdigit() else 0
        self.draft["shippingFee"] = fee
        self.draft["isPrepaid"] = self.shipping_prepaid.isChecked()
        return self.draft

    def recalculate_shipping(self, *args):
        if not hasattr(self, "total_value"):
            return
        if hasattr(self, "preview_quote_button") and self.generated_quote_image is not None:
            self.cleanup_generated_quote()
            self.preview_quote_button.setEnabled(False)
            self.generate_quote_button.setText("공식견적서 생성")
            self.quote_file_label.setText("공식 견적서 미생성")
        prepaid = self.shipping_prepaid.isChecked()
        self.shipping_fee.setEnabled(prepaid)
        self.shipping_fee_label.setEnabled(prepaid)
        try:
            self.apply_shipping(False)
            item_total = sum(int(item["quantity"]) * int(item["unitPrice"]) for item in self.draft.get("items", []))
            amounts = draft_amounts(self.draft)
            self.total_value.setText(f"{amounts['total']:,}원")
            if self.draft["isPrepaid"]:
                self.shipping_status.setText(
                    f"선불 {self.draft['shippingFee']:,}원 · 묘목금액 {item_total:,}원에 포함"
                )
            else:
                self.shipping_status.setText(
                    f"후불 · 총금액 미포함 · 선불 시 {self.draft['shippingFee']:,}원"
                )
        except UserError:
            self.total_value.setText("금액 확인 필요")
            self.shipping_status.setText("택배비 금액을 확인해주세요.")

    def persist_shipping(self):
        try:
            self.apply_shipping(True)
            validate_draft(self.draft)
            if self.manager:
                self.manager.save_draft(self.draft)
            return True
        except UserError as error:
            QMessageBox.warning(self, "택배비 확인", str(error))
            return False

    def delivery_text(self):
        if not self.persist_shipping():
            return ""
        amount = draft_amounts(self.draft)["total"]

        items = ", ".join(
            f"{item['species']} {item['quantity']:,}주"
            for item in self.draft["items"]
        )

        shipping = (
            f"택배비: 선불 {self.draft['shippingFee']:,}원 (총금액 포함)"
            if self.draft["isPrepaid"]
            else f"택배비: 후불 / 선불 시 {self.draft['shippingFee']:,}원"
        )

        return "\n".join(
            [
                "[나무팜 견적 안내]",
                "",
                f"{self.draft['customerName']}님, 요청하신 묘목 견적을 안내드립니다.",
                "",
                f"견적번호: {self.quote_number()}",
                f"품목: {items}",
                f"총금액: {amount:,}원",
                shipping,
                "",
                "확인 후 문의사항이 있으시면 연락 부탁드립니다.",
            ]
        )

    def open_sms(self):
        if not self.persist_shipping():
            return
        dialog = QuoteSmsDialog(self.draft, self.manager, self)
        dialog.exec()
        self.delivery_state = dict(dialog.delivery_state or self.manager.quote_delivery_state(self.draft["id"]))
        self.update_delivery_badges()

    def open_email(self):
        if not self.persist_shipping():
            return
        email = self.draft.get("customerEmail", "").strip()
        if not email:
            QMessageBox.information(self, "메일 주소 확인", "고객 이메일이 입력되지 않았습니다.")
            return

        attachment_path = None
        if self.generated_quote_path and Path(self.generated_quote_path).is_file():
            answer = QMessageBox.question(
                self,
                "공식견적서 첨부",
                (
                    "공식견적서가 생성되어 있습니다.\n"
                    f"{Path(self.generated_quote_path).name}\n\n"
                    "메일에 첨부해서 보내시겠습니까?"
                ),
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.Yes,
            )
            if answer == QMessageBox.StandardButton.Yes:
                attachment_path = self.generated_quote_path

        dialog = QuoteEmailDialog(
            self.draft, self.manager, self,
            attachment_path=attachment_path,
        )
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.delivery_state = dict(dialog.delivery_state or self.delivery_state)
            self.update_delivery_badges()

    def official_quote_filename(self):
        safe_number = self.quote_number().replace("/", "_").replace("\\", "_")
        return f"{safe_number}_공식견적서.pdf"

    def cleanup_generated_quote(self):
        path = self.generated_quote_path
        self.generated_quote_image = None
        self.generated_quote_path = None
        if path:
            try:
                Path(path).unlink(missing_ok=True)
            except OSError:
                pass

    def generate_official_quote(self):
        if not self.persist_shipping():
            return
        try:
            image = render_official_quote_image(self.draft)
            filename = self.official_quote_filename()
            temp_dir = Path(tempfile.gettempdir()) / "NamuFarm" / "official_quotes"
            path = write_temporary_quote_pdf(image, temp_dir / filename)
            self.generated_quote_image = image
            self.generated_quote_path = path
            self.preview_quote_button.setEnabled(True)
            self.generate_quote_button.setText("공식견적서 재생성")
            self.quote_file_label.setText(f"공식 견적서 생성완료 : {filename}")
        except (UserError, OSError) as error:
            self.cleanup_generated_quote()
            self.preview_quote_button.setEnabled(False)
            self.quote_file_label.setText("공식 견적서 생성실패")
            QMessageBox.warning(self, "공식견적서 생성", str(error))

    def preview_official_quote(self):
        if self.generated_quote_image is None or not self.generated_quote_path:
            QMessageBox.information(self, "공식견적서", "먼저 공식견적서를 생성해주세요.")
            return
        preview_temporary_quote(self, self.generated_quote_image)

    def mark_sent(self):
        if not self.persist_shipping():
            return
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
        self.cleanup_generated_quote()
        self.accept()

    def confirm_payment(self):
        if self.request_status != "견적발송완료" or not self.persist_shipping():
            return
        answer = QMessageBox.question(
            self,
            "입금확인",
            (
                "실제 고객 입금을 확인했나요?\n\n"
                "확인하면 이 견적을 입금확인 판매로 등록하고 "
                "판매 · 고객 관리 목록으로 이동합니다."
            ),
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.mode = "paid"
        self.cleanup_generated_quote()
        self.accept()

    def reject(self):
        if not self.persist_shipping():
            return
        self.cleanup_generated_quote()
        super().reject()


def customer_status_text(status):
    return {
        "구매확정": "입금확인",
        "배송확인": "입금확인",
        "판매완료": "배송완료",
        "주문취소": "구매취소",
    }.get(status, status)


def draft_quote_number(draft):
    return draft.get("requestReceiptNumber") or ("PCQ_" + draft["id"][:8])


def draft_quote_sort_key(draft):
    """Stable quote-number ordering; created/id are deterministic tie breakers."""
    return (
        draft_quote_number(draft).casefold(),
        int(draft.get("createdAt", 0) or 0),
        str(draft.get("id", "")),
    )


class CustomerOrderDialog(QDialog):
    """판매 단계의 고객·배송·원가를 한 화면에서 확인하고 수정하는 팝업."""

    def __init__(self, row, parent=None):
        super().__init__(parent)
        self.row = copy.deepcopy(row)
        self.meta = self.row["metadata"]
        self.order = self.row["private"]["order"]
        self.mode = ""
        self.collected_fields = None
        self.setObjectName("customerOrderDialog")
        self.setWindowTitle("고객 관리")
        self.setSizeGripEnabled(True)

        screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
        available = screen.availableGeometry() if screen else None
        if available:
            width = min(1180, max(980, int(available.width() * 0.80)))
            height = min(930, max(780, int(available.height() * 0.90)))
            width = min(width, max(900, available.width() - 40))
            height = min(height, max(700, available.height() - 50))
            self.resize(width, height)
        else:
            self.resize(1120, 880)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 16, 20, 16)
        layout.setSpacing(12)

        header = QHBoxLayout()
        title_box = QVBoxLayout()
        title_box.addWidget(label(self.order.get("customerName") or "고객명 미입력", "deliveryTitle", True))
        title_box.addWidget(label("주문 " + self.meta["orderNumber"], "deliverySubtitle", True))
        header.addLayout(title_box)
        header.addStretch()
        status = QLabel(customer_status_text(self.meta["status"]))
        status.setObjectName("customerStatusBadge")
        status.setFixedHeight(32)
        status.setAlignment(Qt.AlignmentFlag.AlignCenter)
        record_status = QHBoxLayout()
        record_status.addWidget(status, 0, Qt.AlignmentFlag.AlignVCenter)
        record_status.addWidget(label(money(self.meta["totalPrice"] + self.meta.get("taxAmount", 0)), "customerAmountValue"))
        header.addLayout(record_status)
        layout.addLayout(header)

        summary = QFrame()
        summary.setObjectName("customerSummaryCard")
        metrics = QGridLayout(summary)
        metrics.setContentsMargins(16, 10, 16, 10)
        metrics.setHorizontalSpacing(24)
        metrics.setVerticalSpacing(5)
        tracking = tracking_number(self.order) or "미등록"
        summary_values = (
            ("주문일", timestamp(self.meta["orderDate"])),
            ("입금일", timestamp(self.meta["paidAt"])),
            ("송장번호", tracking),
        )
        for index, (caption, value) in enumerate(summary_values):
            metrics.addWidget(label(caption + " · " + str(value), "customerMetricCaption", True), 0, index)
            metrics.setColumnStretch(index, 1)
        layout.addWidget(summary)

        body = QHBoxLayout()
        body.setSpacing(16)

        info_card = QFrame()
        info_card.setObjectName("customerInfoCard")
        info_layout = QVBoxLayout(info_card)
        info_layout.setContentsMargins(16, 12, 16, 12)
        info_layout.addWidget(label("고객 · 배송 정보", "sectionTitle"))
        form = QGridLayout()
        form.setHorizontalSpacing(12)
        form.setVerticalSpacing(8)
        self.customer_fields = {}
        fields = (
            ("customerName", "고객명"),
            ("customerPhone", "연락처"),
            ("customerEmail", "이메일"),
            ("receiverName", "배송 수령인"),
            ("zipNo", "우편번호"),
            ("roadAddress", "도로명 주소"),
            ("detailAddress", "상세 주소"),
        )
        for index, (name, caption) in enumerate(fields):
            edit = QLineEdit(str(self.order.get(name, "") or ""))
            edit.setAccessibleName(caption)
            edit.setMaxLength({"customerEmail":254, "roadAddress":300, "detailAddress":300,
                               "receiverName":100, "customerName":50, "customerPhone":20, "zipNo":10}.get(name, 300))
            if name == "customerPhone" and self.row["private"].get("quoteDocumentJson"):
                edit.setReadOnly(True)
                edit.setToolTip("확정 견적서와 연결된 고객 전화번호는 유지합니다.")
            self.customer_fields[name] = edit
            positions = {"customerName": (0, 0, 1), "customerPhone": (0, 2, 1),
                         "customerEmail": (1, 0, 3), "receiverName": (2, 0, 1),
                         "zipNo": (2, 2, 1), "roadAddress": (3, 0, 3), "detailAddress": (4, 0, 3)}
            r, c, span = positions[name]
            form.addWidget(label(caption, "infoCaption"), r, c)
            form.addWidget(edit, r, c + 1, 1, span)
        form.setColumnStretch(1, 1)
        form.setColumnStretch(3, 1)
        info_layout.addLayout(form)

        self.note = QPlainTextEdit(str(self.order.get("note", "") or ""))
        self.note.setAccessibleName("배송 메모")
        self.note.setFixedHeight(64)
        info_layout.addWidget(collapsible("배송 메모", self.note))
        info_layout.addStretch()
        body.addWidget(info_card, 3)

        finance_card = QFrame()
        finance_card.setObjectName("customerFinanceCard")
        finance_layout = QVBoxLayout(finance_card)
        finance_layout.setContentsMargins(16, 12, 16, 12)
        finance_layout.addWidget(label("판매 · 원가 확인", "sectionTitle"))

        finance_form = QFormLayout()
        self.profit_mode = QComboBox()
        self.profit_mode.addItem("매입 후 판매", "resale")
        self.profit_mode.addItem("중개 수수료", "commission")
        self.profit_mode.setCurrentIndex(1 if self.order.get("profitMode") == "commission" else 0)
        profit_options = QWidget()
        profit_form = QFormLayout(profit_options)
        profit_form.setContentsMargins(0, 0, 0, 0)
        profit_form.addRow("수익 방식", self.profit_mode)

        self.commission = QDoubleSpinBox()
        self.commission.setRange(0, 100)
        self.commission.setDecimals(2)
        self.commission.setSuffix(" %")
        self.commission.setValue(self.order.get("commissionBps", 3000) / 100)
        profit_form.addRow("수수료율", self.commission)

        self.cost_total = QLineEdit(str(int(self.order.get("costTotal", 0) or 0)))
        self.cost_total.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.cost_total.setValidator(QIntValidator(0, 999999999, self.cost_total))
        finance_form.addRow("원가 합계 (원)", self.cost_total)

        self.expense_total = QLineEdit(str(int(self.order.get("expenseTotal", 0) or 0)))
        self.expense_total.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.expense_total.setValidator(QIntValidator(0, 999999999, self.expense_total))
        finance_form.addRow("기타 비용 (원)", self.expense_total)

        shipping_text = (
            f"선불 {int(self.order.get('shippingFee', 0) or 0):,}원"
            if self.order.get("isPrepaid")
            else f"후불 · 선불 기준 {int(self.order.get('shippingFee', 0) or 0):,}원"
        )
        finance_form.addRow("택배비", label(shipping_text, "customerMetricValue"))
        finance_layout.addLayout(finance_form)
        finance_layout.addWidget(collapsible("수익 방식 · 수수료", profit_options, self.order.get("profitMode") == "commission"))

        self.cost_confirmed = QCheckBox("원가와 비용을 확인했습니다")
        self.cost_confirmed.setChecked(bool(self.order.get("costConfirmed")))
        finance_layout.addWidget(self.cost_confirmed)

        profit_value = label(money(profit(self.meta)), "infoValue")
        finance_layout.addWidget(label("현재 저장 기준 매출이익", "customerMetricCaption"))
        finance_layout.addWidget(profit_value)

        self.quote_print = button("확정 견적서 출력", self.print_quote)
        self.quote_print.setEnabled(bool(self.row["private"].get("quoteDocumentJson")))
        quote_info = QWidget()
        quote_layout = QVBoxLayout(quote_info)
        quote_layout.setContentsMargins(0, 0, 0, 0)
        quote_number = self.order.get("sourceQuoteNumber") or "번호 없음"
        shown_number = quote_number[:12] + "…" if quote_number.startswith("PCQ_") and len(quote_number) > 20 else quote_number
        reference = label(shown_number, "customerMetricCaption", True)
        reference.setToolTip(quote_number)
        quote_layout.addWidget(reference)
        if not self.row["private"].get("quoteDocumentJson"):
            self.quote_print.setToolTip("이 주문에 보관된 확정 견적서가 없습니다.")
        quote_layout.addWidget(self.quote_print)
        finance_layout.addWidget(collapsible("연결 견적서", quote_info))
        finance_layout.addStretch()
        body.addWidget(finance_card, 2)
        layout.addLayout(body)

        layout.addWidget(label("판매 품목", "sectionTitle"))
        self.items = table(["묘목명", "규격", "수량", "판매 단가", "판매 금액"])
        fill_table(self.items, [
            (item["species"], item.get("specification") or "—", f"{item['quantity']:,}주",
             money(item["unitPrice"]), money(item["quantity"] * item["unitPrice"]))
            for item in self.meta.get("items", [])
        ])
        polish_table(self.items)
        center_table_columns(self.items, (1, 2, 3, 4))
        self.items.set_column_layout(((0, 3.5), (1, 2), (2, 1), (3, 1.7), (4, 2)))
        size_table_to_rows(self.items, 3)
        layout.addWidget(self.items)

        terminal = self.meta["status"] in ("배송완료", "구매취소", "판매완료", "주문취소")
        if terminal:
            info_card.setEnabled(False)
            finance_card.setEnabled(False)

        actions = QHBoxLayout()
        self.cancel_order_button = QPushButton("구매 취소")
        self.cancel_order_button.setObjectName("danger")
        self.cancel_order_button.setEnabled(self.meta["status"] in ("입금확인", "구매확정"))
        self.cancel_order_button.clicked.connect(lambda: self.finish("cancel"))

        close_button = button("닫기", self.reject)
        self.save_button = button("수정 저장", lambda: self.finish("save"))
        self.save_button.setEnabled(not terminal)
        self.delivery_button = button("배송 완료", lambda: self.finish("deliver"), True)
        self.delivery_button.setEnabled(self.meta["status"] in ("입금확인", "구매확정", "배송확인"))

        actions.addWidget(self.cancel_order_button)
        self.edit_state = label("PC에 불러온 기록", "muted")
        actions.addWidget(self.edit_state, 1)
        actions.addWidget(close_button)
        actions.addWidget(self.save_button)
        actions.addWidget(self.delivery_button)
        layout.addLayout(actions)
        for field in (*self.customer_fields.values(), self.cost_total, self.expense_total):
            field.textChanged.connect(lambda _text: self.edit_state.setText("수정 중 · 저장 필요"))
        self.note.textChanged.connect(lambda: self.edit_state.setText("수정 중 · 저장 필요"))
        self.commission.valueChanged.connect(lambda _value: self.edit_state.setText("수정 중 · 저장 필요"))
        self.cost_confirmed.toggled.connect(lambda _value: self.edit_state.setText("수정 중 · 저장 필요"))
        self.profit_mode.currentIndexChanged.connect(lambda _value: self.edit_state.setText("수정 중 · 저장 필요"))
        set_button_icon(self.cancel_order_button, "trash", "#9c3c2e")
        set_button_icon(self.save_button, "save")
        set_button_icon(self.delivery_button, "check", "#ffffff")
        set_button_icon(self.quote_print, "document")
        scroll_dialog(self, layout)

    def collect_fields(self):
        result = {name: edit.text().strip() for name, edit in self.customer_fields.items()}
        result["note"] = self.note.toPlainText().strip()
        for edit, caption in ((self.cost_total, "원가 합계"), (self.expense_total, "기타 비용")):
            require(edit.text().isascii() and edit.text().isdigit(), f"{caption}를 숫자로 입력해주세요.")
        result.update(
            costTotal=int(self.cost_total.text()),
            expenseTotal=int(self.expense_total.text()),
            costConfirmed=self.cost_confirmed.isChecked(),
            profitMode=self.profit_mode.currentData(),
            commissionBps=round(self.commission.value() * 100),
        )
        return result

    def finish(self, mode):
        try:
            fields = self.collect_fields()
            if mode == "deliver":
                require(fields["costConfirmed"], "배송완료 전에 원가와 비용을 확인해주세요.")
                answer = QMessageBox.question(
                    self, "배송완료", "실제 배송과 원가·비용 확인을 마쳤나요?",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.No,
                )
                if answer != QMessageBox.StandardButton.Yes:
                    return
            elif mode == "cancel":
                answer = QMessageBox.question(
                    self, "구매취소", "이 주문을 구매취소로 처리할까요?",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.No,
                )
                if answer != QMessageBox.StandardButton.Yes:
                    return
            self.collected_fields = fields
            self.mode = mode
            self.accept()
        except UserError as error:
            QMessageBox.warning(self, "입력 확인", str(error))

    def print_quote(self):
        raw = self.row["private"].get("quoteDocumentJson")
        if not raw:
            return
        try:
            doc = json.loads(raw)
            print_document(self, quote_document_html(doc), "나무팜_" + doc["number"])
        except (ValueError, KeyError, TypeError):
            QMessageBox.warning(self, "견적서 확인", "저장된 확정 견적서를 읽지 못했습니다.")


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
        scroll_dialog(self, layout)

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
    PAGE_REQUESTS, PAGE_QUOTES, PAGE_SALES, PAGE_SHIPPING, PAGE_REPORTS, PAGE_SETTINGS = range(6)
    PAGES = (("견적요청", "INBOX"), ("확정 견적", "QUOTATIONS"),
             ("판매관리", "ORDERS"), ("배송 · 송장", "FULFILLMENT"), ("매출 · 이익", "REPORTS"), ("연결 · 설정", "SETTINGS"))

    def __init__(self, vault_path=None):
        self.workspace_ready = False
        self._request_arrival_notice = ""
        self.request_unread_count = 0
        self.request_nav_badge = None
        self.tray_icon = None
        self.page_counts = {}
        super().__init__(vault_path)
        self.setWindowTitle(f"나무팜 판매관리 | Ver {__version__}")
        self.setMinimumSize(1000, 620)
        fit_window(self, 1920, 1080)
        self.setStyleSheet(STYLE)
        old_root = self.takeCentralWidget()
        old_pages = []
        while self.tabs.count():
            page = self.tabs.widget(0)
            self.tabs.removeTab(0)
            page.setParent(None)
            old_pages.append(page)
        # Keep the hidden legacy editor alive for inherited control updates.
        self._legacy_sales_page = old_pages[1]
        self._legacy_sales_page.setParent(self)
        self._legacy_sales_page.hide()
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
        self.sidebar = sidebar
        sidebar.setFixedWidth(220)
        side = QVBoxLayout(sidebar)
        side.setContentsMargins(12, 20, 12, 16)
        side.setSpacing(8)
        side.addWidget(label("  namufarm", "brand"))
        side.addSpacing(6)
        self.nav = []
        for index, (title, _) in enumerate(self.PAGES):
            if index in (self.PAGE_REQUESTS, self.PAGE_REPORTS):
                side.addWidget(label("견적 · 주문" if index == self.PAGE_REQUESTS else "운영", "navGroup"))
            widget = NavigationButton(title)
            widget.clicked.connect(lambda checked=False, i=index: self.navigate(i))
            icons = ("requests", "confirmed", "orders", "shipping", "reports", "settings")
            set_button_icon(widget, icons[index], "#e0ebe3", 20)
            widget.setCheckable(True)
            self.nav.append(widget)
            if index == self.PAGE_REQUESTS:
                nav_row = QWidget()
                nav_row.setObjectName("navRow")
                nav_layout = QHBoxLayout(nav_row)
                nav_layout.setContentsMargins(0, 0, 0, 0)
                nav_layout.setSpacing(6)
                nav_layout.addWidget(widget, 1)
                self.request_nav_badge = QLabel("")
                self.request_nav_badge.setObjectName("navBadge")
                self.request_nav_badge.hide()
                nav_layout.addWidget(self.request_nav_badge, 0, Qt.AlignmentFlag.AlignVCenter)
                side.addWidget(nav_row)
            else:
                side.addWidget(widget)
        side.addStretch()
        outer.addWidget(sidebar)
        content = QWidget()
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(24, 16, 24, 16)
        content_layout.setSpacing(12)
        header = QHBoxLayout()
        heading = QVBoxLayout()
        self.page_title = label("", "pageTitle")
        heading.addWidget(self.page_title)
        header.addLayout(heading)
        header.addStretch()
        self.sync_button.setText("가져오기")
        set_button_icon(self.sync_button, "refresh")
        self.sync_button.setToolTip("최신 자료를 가져오고 전송 대기 중인 변경사항도 서버에 반영합니다. F5")
        self.sync_button.setObjectName("syncSecondary")
        self.sync_button.setMinimumWidth(138)

        content_layout.addLayout(header)

        self.server_log_frame = QFrame()
        self.server_log_frame.setObjectName("serverLogFrame")
        self.server_log_frame.setProperty("state", "idle")
        log_layout = QHBoxLayout(self.server_log_frame)
        log_layout.setContentsMargins(12, 8, 12, 8)
        log_layout.setSpacing(12)
        self.status_title = label("연결 안내", "serverLogTitle")
        log_layout.addWidget(self.status_title)
        self.message.setObjectName("serverLogText")
        self.message.setWordWrap(True)
        self.message.setMinimumWidth(0)
        self.message.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        self.message.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        self.message.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.message.setAccessibleName("연결 및 작업 상태")
        log_layout.addWidget(self.message, 1)
        log_layout.addWidget(self.sync_button)
        content_layout.addWidget(self.server_log_frame)

        self.stack = QStackedWidget()
        self.stack.addWidget(old_pages[0])
        self.stack.addWidget(self.build_drafts())
        self.stack.addWidget(self.build_customer_management())
        self.stack.addWidget(self.build_shipping())
        self.stack.addWidget(scroll_page(old_pages[2]))
        self.stack.addWidget(old_pages[3])
        for index in range(self.stack.count()):
            self.stack.widget(index).setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Ignored)
        content_layout.addWidget(self.stack, 1)
        outer.addWidget(content, 1)
        old_root.deleteLater()
        self.request_quote_button = button("견적", self.quote_request)
        self.request_quote_button.setObjectName("requestQuote")
        self.request_quote_button.setMinimumWidth(140)
        self.request_table.itemDoubleClicked.connect(self.quote_request)
        self.request_actions.insertWidget(1, self.request_quote_button)
        self.request_table.setWordWrap(False)
        self.request_table.setTextElideMode(Qt.TextElideMode.ElideRight)
        self.request_table.verticalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Fixed)
        self.configure_request_columns()
        self.request_table.setItemDelegateForColumn(3, CustomerDelegate(self.request_table))
        request_layout = old_pages[0].layout()
        request_layout.setContentsMargins(0, 0, 0, 0)
        request_layout.setSpacing(8)
        self.request_table.setItemDelegateForColumn(1, CompactDateDelegate(self.request_table))
        self.request_table.horizontalHeaderItem(1).setToolTip("접수일 · 전체 날짜와 시간은 셀 도움말에서 확인합니다.")
        set_button_icon(self.request_quote_button, "plus", "#23593a")
        self.ui_settings = QSettings("NamuFarm", "NamuFarmDesktop")
        self.large_font_check.setChecked(self.ui_settings.value("ui/largeFont", False, type=bool))
        self.large_font_check.toggled.connect(self.apply_design_scale)
        self.apply_design_scale(self.large_font_check.isChecked())
        for widget in self.findChildren(type(self.request_table)):
            polish_table(widget)
        self.save_button.setObjectName("primary")
        self.next_button.setObjectName("primary")
        # 판매·고객 관리는 목록 + 전용 팝업으로 동작합니다. 기존 내장 편집기는
        # MainWindow 호환을 위해 객체만 유지하고 실제 작업 화면에는 노출하지 않습니다.

        if QSystemTrayIcon.isSystemTrayAvailable():
            self.tray_icon = QSystemTrayIcon(self)
            icon = self.windowIcon()
            if icon.isNull():
                icon = self.style().standardIcon(QStyle.StandardPixmap.SP_ComputerIcon)
            self.tray_icon.setIcon(icon)
            self.tray_icon.setToolTip("나무팜 판매관리")
            self.tray_icon.messageClicked.connect(self.open_new_requests_from_notification)
            self.tray_icon.show()

        self.workspace_ready = True
        self.navigate(self.PAGE_REQUESTS)
        QShortcut(QKeySequence("F5"), self, activated=self.manual_sync)
        for widget, action in ((self.request_table, self.quote_request),
                               (self.draft_table, self.open_draft),
                               (self.sale_table, self.open_customer_order)):
            shortcut = QShortcut(QKeySequence("Return"), widget, activated=action)
            shortcut.setContext(Qt.ShortcutContext.WidgetWithChildrenShortcut)
        self.refresh_workspace()
        self.update_controls()

    def apply_design_scale(self, large):
        app = QApplication.instance()
        app.setProperty("namufarmLargeFont", bool(large))
        style = workspace_style(bool(large))
        app.setStyleSheet(style)
        self.setStyleSheet(style)
        self.ui_settings.setValue("ui/largeFont", bool(large))
        for widget in self.findChildren(QTableWidget):
            polish_table(widget)
            if hasattr(widget, "fit_columns"):
                widget.fit_columns()
        self.configure_request_columns()

    def run_job(self, title, work, on_success=None, refresh_on_error=True):
        starting = self.task is None
        super().run_job(title, work, on_success, refresh_on_error)
        if starting and self.workspace_ready:
            self.set_status_state("working", "처리 중")

    def set_status_state(self, state, title):
        self.status_title.setText(title)
        self.server_log_frame.setProperty("state", state)
        self.server_log_frame.style().unpolish(self.server_log_frame)
        self.server_log_frame.style().polish(self.server_log_frame)
        self.server_log_frame.update()

    def configure_request_columns(self):
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        minimum = (205, 140, 190, 128, 100, 205) if not large else (210, 150, 195, 144, 112, 220)
        self.request_table.set_column_layout(tuple(
            (column, weight, width) for (column, weight), width in zip(
                ((3, 2), (4, .5), (8, 3), (1, .5), (7, .3), (5, 3)), minimum)))

    def set_page_count(self, index, count):
        self.page_counts[index] = count
        if hasattr(self, "stack") and self.stack.currentIndex() == index:
            self.update_page_title(index)

    def update_page_title(self, index):
        count = self.page_counts.get(index)
        self.page_title.setText(self.PAGES[index][0] + (f" · {count:,}건" if count is not None else ""))

    def refresh_requests(self):
        super().refresh_requests()
        if hasattr(self, "page_title"):
            self.set_page_count(self.PAGE_REQUESTS, len(self.request_rows))
            customer_reference(self.request_table, 3, 0)

    def filter_buttons(self, choices, callback):
        row = QHBoxLayout()
        group = QButtonGroup(self)
        group.setExclusive(True)
        buttons = {}
        for title, value in choices:
            item = button(title, lambda checked=False, v=value: callback(v))
            item.setObjectName("filterTab")
            item.setCheckable(True)
            group.addButton(item)
            buttons[value] = item
            row.addWidget(item)
        next(iter(buttons.values())).setChecked(True)
        row.addStretch()
        return row, buttons

    def set_draft_filter(self, value):
        self.draft_filter = value
        self.refresh_workspace()

    def set_sales_filter(self, value):
        self.state_filter.setCurrentText(value)


    def build_drafts(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(10)
        self.draft_filter = "전체"
        filters, self.draft_filters = self.filter_buttons(
            (("전체", "전체"), ("전달 대기", "견적확정"), ("발송 완료", "견적발송완료")), self.set_draft_filter)
        layout.addLayout(filters)
        self.draft_table = table(["견적번호", "상태", "작성일", "고객명", "연락처", "메일", "공식견적서", "품목", "판매금액", "자료 위치"])
        self.draft_sort_descending = False
        header = self.draft_table.horizontalHeader()
        header.setStretchLastSection(False)
        header.setSortIndicatorShown(True)
        header.setSortIndicator(3, Qt.SortOrder.AscendingOrder)
        self.draft_table.horizontalHeaderItem(3).setText("고객 / 견적번호 ↕")
        self.draft_table.horizontalHeaderItem(3).setToolTip("클릭하면 견적번호 순서를 바꿉니다.")
        header.sectionClicked.connect(self.sort_drafts_by_header)
        header.setMinimumSectionSize(70)
        for column, width in enumerate((190, 115, 145, 100, 135, 220, 105, 320, 120)):
            header.setSectionResizeMode(column, QHeaderView.ResizeMode.Interactive)
            self.draft_table.setColumnWidth(column, width)
        header.setSectionResizeMode(7, QHeaderView.ResizeMode.Stretch)
        self.draft_table.set_column_layout(((3, 2, 215), (1, .5, 140), (7, 3, 235), (8, 1, 155), (9, .5, 100)))
        self.draft_table.setItemDelegateForColumn(3, CustomerDelegate(self.draft_table))
        self.draft_table.setItemDelegateForColumn(1, StatusDelegate(self.draft_table))
        self.draft_table.itemSelectionChanged.connect(self.update_controls)
        self.draft_table.itemDoubleClicked.connect(self.open_draft)
        layout.addWidget(self.draft_table)
        bar = QHBoxLayout()
        self.draft_delete = button("선택 견적 삭제", self.remove_draft)
        self.draft_delete.setObjectName("danger")
        self.draft_open = button("선택 견적 열기", self.open_draft, True)
        set_button_icon(self.draft_open, "open", "#ffffff")
        set_button_icon(self.draft_delete, "trash", "#9c3c2e")
        self.draft_selection_status = label("견적을 선택하세요.", "muted", True)
        bar.addWidget(self.draft_selection_status, 1)
        bar.addWidget(self.draft_delete)
        bar.addWidget(self.draft_open)
        layout.addLayout(bar)
        return page

    def sort_drafts_by_header(self, section):
        if section not in (0, 3):
            return
        self.draft_sort_descending = not self.draft_sort_descending
        self.draft_table.horizontalHeader().setSortIndicator(
            3,
            Qt.SortOrder.DescendingOrder if self.draft_sort_descending else Qt.SortOrder.AscendingOrder,
        )
        self.refresh_workspace()

    def build_customer_management(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(12)

        bar = QHBoxLayout()
        self.sale_search = QLineEdit()
        self.sale_search.setPlaceholderText("고객 · 주문번호 · 연락처 검색")
        self.sale_search.setToolTip("주문번호·고객명·연락처·이메일·수령인·주소·품목을 검색합니다.")
        self.sale_search.setMinimumWidth(180)
        self.sale_search.textChanged.connect(self.refresh_sales)
        self.state_filter = QComboBox()
        self.state_filter.setParent(page)
        self.state_filter.addItems(["전체 상태", "입금확인", "배송완료", "구매취소"])
        self.state_filter.currentIndexChanged.connect(self.refresh_sales)
        bar.addWidget(self.sale_search, 1)
        self.state_filter.hide()
        layout.addLayout(bar)
        filters, self.sales_filters = self.filter_buttons(
            (("전체", "전체 상태"), ("입금확인", "입금확인"), ("배송완료", "배송완료"), ("구매취소", "구매취소")), self.set_sales_filter)
        bar.addLayout(filters)

        self.sale_table = table([
            "주문번호", "상태", "주문일", "고객명", "연락처", "이메일",
            "수령인", "배송주소", "품목", "결제금액"
        ])
        header = self.sale_table.horizontalHeader()
        header.setStretchLastSection(False)
        header.setMinimumSectionSize(70)
        for column, width in enumerate((185, 105, 145, 110, 135, 205, 110, 260, 300, 120)):
            header.setSectionResizeMode(column, QHeaderView.ResizeMode.Interactive)
            self.sale_table.setColumnWidth(column, width)
        header.setSectionResizeMode(8, QHeaderView.ResizeMode.Stretch)
        self.sale_table.setWordWrap(False)
        self.sale_table.setTextElideMode(Qt.TextElideMode.ElideRight)
        self.sale_table.verticalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Fixed)
        self.sale_table.set_column_layout(((3, 2, 200), (4, .6, 140), (8, 3, 210), (9, 1, 145), (1, .5, 125), (2, .5, 144)))
        self.sale_table.setItemDelegateForColumn(2, CompactDateDelegate(self.sale_table))
        self.sale_table.setItemDelegateForColumn(3, CustomerDelegate(self.sale_table))
        self.sale_table.setItemDelegateForColumn(1, StatusDelegate(self.sale_table))
        self.sale_table.itemSelectionChanged.connect(self.select_sale)
        self.sale_table.itemDoubleClicked.connect(self.open_customer_order)
        layout.addWidget(self.sale_table, 1)

        bottom = QHBoxLayout()
        self.customer_list_status = label("주문을 선택하세요.", "muted", True)
        bottom.addWidget(self.customer_list_status, 1)
        self.customer_open_button = button("고객 관리 열기", self.open_customer_order, True)
        set_button_icon(self.customer_open_button, "orders", "#ffffff")
        bottom.addWidget(self.customer_open_button)
        layout.addLayout(bottom)
        return page

    def build_shipping(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(8)
        bar = QHBoxLayout()
        bar.addWidget(label("주문 선택", "sectionTitle"))
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
        self.shipping_table.set_column_layout(((0, 1.8), (1, 1.4), (3, 2.8), (4, 1), (5, 1.8), (6, 1.8)))
        layout.addWidget(self.shipping_table, 1)
        self.shipping_status = label("주문을 선택하세요. Ctrl 또는 Shift로 여러 주문을 선택할 수 있습니다.", "muted", True)
        layout.addWidget(self.shipping_status)
        panel, box = card("송장번호 반영 · 확인")
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
        actions = QHBoxLayout()
        self.export_button = button("선택 주문 로젠 XLS 저장", self.export_shipping, True)
        self.import_button = button("송장 XLS / CSV 가져오기", self.import_shipping)
        self.print_button = button("송장번호 · 출고 확인서 출력", self.print_shipping)
        preparation = QHBoxLayout()
        preparation.addWidget(label("송장 준비", "sectionTitle"))
        preparation.addWidget(self.export_button)
        preparation.addStretch()
        preparation.addWidget(button("전체 선택", self.shipping_table.selectAll))
        preparation.addWidget(button("선택 해제", self.shipping_table.clearSelection))
        layout.addLayout(preparation)
        actions.addWidget(self.import_button)
        actions.addStretch()
        actions.addWidget(self.print_button)
        box.addLayout(actions)
        layout.addWidget(panel)
        self.export_button.setToolTip("선택 주문을 로젠 XLS로 저장하고 로젠 프로그램에 불러와 운송장을 출력하세요.")
        set_button_icon(self.export_button, "document", "#ffffff")
        set_button_icon(self.import_button, "folder")
        set_button_icon(self.tracking_save, "save", "#ffffff")
        return page

    def navigate(self, index):
        self.stack.setCurrentIndex(index)
        self.update_page_title(index)
        for i, widget in enumerate(self.nav):
            widget.setChecked(i == index)
        if index == self.PAGE_REQUESTS and self.workspace_ready:
            self.mark_requests_seen()

    def request_sequence_max(self):
        return max(
            (int(row.get("sequence", 0) or 0) for row in self.data.get("requests", [])),
            default=0,
        )

    def update_request_badge(self, state=None):
        if self.request_nav_badge is None:
            return
        if state is None and self.manager and hasattr(self.manager, "request_notification_state"):
            state = self.manager.request_notification_state()
        seen = int((state or {}).get("seenSequence", 0) or 0)
        count = sum(
            row.get("status", "신규") == "신규"
            and int(row.get("sequence", 0) or 0) > seen
            for row in self.data.get("requests", [])
        )
        self.request_unread_count = count
        if count:
            self.request_nav_badge.setText("99+" if count > 99 else str(count))
            self.request_nav_badge.setToolTip(f"마지막 확인 이후 신규 견적요청 {count}건")
            self.request_nav_badge.show()
        else:
            self.request_nav_badge.clear()
            self.request_nav_badge.setToolTip("")
            self.request_nav_badge.hide()

    def mark_requests_seen(self):
        if (
            getattr(self, "demo_mode", False)
            or not self.manager
            or not self.data.get("paired")
            or not hasattr(self.manager, "request_notification_state")
        ):
            self.update_request_badge({"seenSequence": self.request_sequence_max()})
            return
        state = self.manager.request_notification_state()
        current = self.request_sequence_max()
        if state is None:
            state = self.manager.save_request_notification_state(current, current)
        else:
            state = self.manager.save_request_notification_state(
                max(int(state.get("seenSequence", 0) or 0), current),
                max(int(state.get("notifiedSequence", 0) or 0), current),
            )
        self.update_request_badge(state)

    def refresh_request_notifications(self):
        if (
            getattr(self, "demo_mode", False)
            or not self.workspace_ready
            or not self.manager
            or not self.data.get("paired")
            or not hasattr(self.manager, "request_notification_state")
        ):
            self.update_request_badge({"seenSequence": self.request_sequence_max()})
            return

        current = self.request_sequence_max()
        state = self.manager.request_notification_state()
        if state is None:
            # First run on an already paired PC: use the existing local snapshot as
            # the baseline so old requests are not announced as newly arrived.
            state = self.manager.save_request_notification_state(current, current)
            self.update_request_badge(state)
            return

        notified = int(state.get("notifiedSequence", 0) or 0)
        arrivals = [
            row for row in self.data.get("requests", [])
            if row.get("status", "신규") == "신규"
            and int(row.get("sequence", 0) or 0) > notified
        ]
        if current > notified:
            state = self.manager.save_request_notification_state(
                int(state.get("seenSequence", 0) or 0),
                current,
            )

        if arrivals:
            count = len(arrivals)
            self._request_arrival_notice = f"NEW RFQ  >  신규 견적요청 {count}건 도착"
            if self.tray_icon is not None:
                self.tray_icon.showMessage(
                    "나무팜 · 신규 견적 요청",
                    f"새 견적 요청 {count}건이 들어왔습니다.",
                    QSystemTrayIcon.MessageIcon.Information,
                    6000,
                )
            if not self.isActiveWindow():
                QApplication.alert(self, 5000)

        if self.stack.currentIndex() == self.PAGE_REQUESTS:
            self.mark_requests_seen()
        else:
            self.update_request_badge(state)

    def open_new_requests_from_notification(self):
        self.showNormal()
        self.raise_()
        self.activateWindow()
        self.navigate(self.PAGE_REQUESTS)

    def job_finished(self, task, value, error, conflict, on_success):
        super().job_finished(task, value, error, conflict, on_success)
        self.set_status_state("error" if error else "success", "처리 실패" if error else "처리 완료")
        title = getattr(task, "operation_title", "")
        if (not error and isinstance(value, dict) and "requests" in value
                and ("동기화" in title or "서버 연결" in title)):
            completed = timestamp(datetime.now(SEOUL).timestamp() * 1000)
            self.last_sync_label.setText("성공 · " + completed)
            self.set_status_state("success", "동기화 완료")
        if self._request_arrival_notice:
            current = getattr(self.message, "details", self.message.text()).strip()
            self.message.setText(
                self._request_arrival_notice + ("  |  " + current if current else "")
            )
            self._request_arrival_notice = ""

    def update_controls(self):
        super().update_controls()
        if not self.workspace_ready:
            return
        ready = self.manager is not None and self.task is None
        paired = ready and self.data["paired"] and not self.data["pending"]
        self.stack.setEnabled(self.task is None)
        self.request_quote_button.setEnabled(ready and self.request_table.currentRow() >= 0)
        selected_draft = self.draft_table.currentRow()
        self.draft_open.setEnabled(ready and 0 <= selected_draft < len(self.draft_rows))
        self.draft_delete.setEnabled(
            ready and 0 <= selected_draft < len(self.draft_rows)
            and self.draft_rows[selected_draft]["id"] not in getattr(self, "cloud_quote_ids", set())
        )
        if 0 <= selected_draft < len(self.draft_rows):
            draft = self.draft_rows[selected_draft]
            status = self.draft_statuses[selected_draft]
            server_copy = draft["id"] in getattr(self, "cloud_quote_ids", set())
            action = "문자·메일 전달" if status == "견적확정" else "입금 확인" if status == "견적발송완료" else "견적 확인"
            self.draft_selection_status.setText(
                f"{draft['customerName']} · 다음 작업: {action}" +
                (" · 서버 조회 자료는 여기서 삭제할 수 없습니다." if server_copy else ""))
            self.draft_delete.setToolTip("서버 조회 자료는 삭제 대상이 아닙니다." if server_copy else "선택한 PC 견적 삭제")
        else:
            self.draft_selection_status.setText("견적을 선택하면 다음 작업을 확인할 수 있습니다.")
            self.draft_delete.setToolTip("삭제할 PC 견적을 선택하세요.")
        self.export_button.setEnabled(paired)
        self.import_button.setEnabled(paired)
        self.print_button.setEnabled(ready and not self.data["pending"])
        self.tracking_save.setEnabled(paired)
        self.shipping_edit.setEnabled(ready)
        self.customer_open_button.setEnabled(ready and self.selected_sale is not None)
        if getattr(self, "demo_mode", False):
            for widget in (self.sync_button, self.request_quote_button,
                           self.draft_open, self.draft_delete, self.customer_open_button,
                           self.export_button, self.import_button, self.print_button,
                           self.tracking_save, self.open_button, self.connect_button, self.report_button,
                           self.copy_button):
                widget.setEnabled(False)

    def apply_view(self, value):
        super().apply_view(value)
        if self.workspace_ready:
            self.refresh_workspace()
            self.refresh_request_notifications()

    def refresh_workspace(self):
        request_status = {r["requestId"]: r.get("status", "신규") for r in self.data["requests"]}
        draft_pairs = []
        local_sources = set()
        for draft in self.data.get("drafts", []):
            source = draft.get("sourceRequestId", "")
            status = request_status.get(source, "상태확인") if source else draft.get("manualStatus", "수기견적")
            if source and status not in ("견적확정", "견적발송완료"):
                continue
            draft_pairs.append((draft, status))
            if source:
                local_sources.add(source)
        # The server's confirmed tab is authoritative even when a quote was
        # finalized on Android and this PC never created a local draft.
        # Build a display-only copy; do not save or replace existing PC drafts.
        self.cloud_quote_ids = set()
        for request in self.data["requests"]:
            status = request.get("status", "신규")
            if status not in ("견적확정", "견적발송완료") or request["requestId"] in local_sources:
                continue
            draft = new_draft(request)
            draft["id"] = str(uuid.uuid5(uuid.NAMESPACE_URL, "namufarm:quote:" + request["requestId"]))
            draft["createdAt"] = int(datetime.fromisoformat(
                request["createdAt"].replace("Z", "+00:00")).timestamp() * 1000)
            self.cloud_quote_ids.add(draft["id"])
            draft_pairs.append((draft, status))
        draft_pairs.sort(
            key=lambda pair: draft_quote_sort_key(pair[0]),
            reverse=self.draft_sort_descending,
        )
        for value, widget in self.draft_filters.items():
            title = {"전체": "전체", "견적확정": "전달 대기", "견적발송완료": "발송 완료"}[value]
            count = len(draft_pairs) if value == "전체" else sum(status == value for _draft, status in draft_pairs)
            widget.setText(f"{title} {count:,}")
            widget.setChecked(value == self.draft_filter)
        previous_index = self.draft_table.currentRow()
        previous_rows = getattr(self, "draft_rows", [])
        previous_id = previous_rows[previous_index]["id"] if 0 <= previous_index < len(previous_rows) else None
        draft_pairs = [(draft, status) for draft, status in draft_pairs
                       if self.draft_filter == "전체" or status == self.draft_filter]
        self.draft_rows = [draft for draft, _status in draft_pairs]
        self.draft_statuses = [status for _draft, status in draft_pairs]
        self.draft_table.blockSignals(True)
        fill_table(self.draft_table, [(
            draft_quote_number(d),
            status,
            timestamp(d["createdAt"]),
            d["customerName"],
            d["customerPhone"],
            d.get("customerEmail") or "—",
            "요청" if d.get("formalQuoteRequested") else "—",
            ", ".join(f"{i['species']} {i['quantity']:,}주" for i in d["items"]),
            money(draft_amounts(d)["total"]),
            "서버 조회" if d["id"] in self.cloud_quote_ids else "PC 보관",
        ) for d, status in draft_pairs])
        self.draft_table.set_empty_message("선택한 상태의 견적이 없습니다.\n전체 목록 또는 가져오기를 확인하세요.")
        center_table_columns(self.draft_table, (0, 1, 2, 3, 4, 6, 8))
        customer_reference(self.draft_table, 3, 0)
        self.draft_table.clearSelection()
        self.draft_table.setCurrentCell(-1, -1)
        restored = next((i for i, draft in enumerate(self.draft_rows) if draft["id"] == previous_id), None)
        if restored is not None:
            self.draft_table.selectRow(restored)
        self.draft_table.blockSignals(False)
        self.set_page_count(self.PAGE_QUOTES, len(draft_pairs))
        self.draft_table.horizontalHeaderItem(9).setToolTip("서버 조회: 이 PC에 저장한 견적이 없어 서버 자료로 표시 · PC 보관: 이 PC에 저장된 견적")
        self.refresh_sales()
        self.refresh_shipping()

    def refresh_sales(self):
        if not getattr(self, "workspace_ready", False):
            return super().refresh_sales()

        selected_number = (
            self.selected_sale["metadata"]["orderNumber"]
            if self.selected_sale else None
        )
        query = self.sale_search.text().strip().casefold()
        wanted = self.state_filter.currentText()
        for value, widget in self.sales_filters.items():
            count = sum(value == "전체 상태" or customer_status_text(row["metadata"]["status"]) == value
                        for row in self.data.get("sales", []))
            widget.setText(f"{'전체' if value == '전체 상태' else value} {count:,}")
            widget.setChecked(value == wanted)

        status_priority = {"입금확인": 0, "배송완료": 1, "구매취소": 2}
        rows = []
        for row in self.data.get("sales", []):
            meta = row["metadata"]
            order = row["private"]["order"]
            display_status = customer_status_text(meta["status"])
            if wanted != "전체 상태" and display_status != wanted:
                continue
            address = " ".join(filter(None, [
                f"({order.get('zipNo')})" if order.get("zipNo") else "",
                order.get("roadAddress", ""),
                order.get("detailAddress", ""),
            ])).strip()
            items_text = ", ".join(
                f"{item['species']} {item['quantity']:,}주"
                for item in meta.get("items", [])
            )
            searchable = " ".join([
                meta["orderNumber"], display_status,
                order.get("customerName", ""), order.get("customerPhone", ""),
                order.get("customerEmail", ""), order.get("receiverName", ""),
                address, items_text, tracking_number(order),
            ]).casefold()
            if query and query not in searchable:
                continue
            rows.append(row)

        rows.sort(
            key=lambda row: (
                status_priority.get(customer_status_text(row["metadata"]["status"]), 9),
                -int(row["metadata"].get("purchasedAt", 0) or 0),
                row["metadata"]["orderNumber"],
            )
        )
        self.sale_rows = rows
        table_rows = []
        for row in rows:
            meta = row["metadata"]
            order = row["private"]["order"]
            address = " ".join(filter(None, [
                f"({order.get('zipNo')})" if order.get("zipNo") else "",
                order.get("roadAddress", ""),
                order.get("detailAddress", ""),
            ])).strip() or "미입력"
            items_text = ", ".join(
                f"{item['species']} {item['quantity']:,}주"
                for item in meta.get("items", [])
            ) or "—"
            table_rows.append((
                meta["orderNumber"],
                customer_status_text(meta["status"]),
                timestamp(meta["orderDate"]),
                order.get("customerName", ""),
                order.get("customerPhone", ""),
                order.get("customerEmail") or "—",
                order.get("receiverName") or "—",
                address,
                items_text,
                money(meta["totalPrice"] + meta.get("taxAmount", 0)),
            ))

        self.sale_table.blockSignals(True)
        self.sale_table.set_empty_message("조건에 맞는 판매 기록이 없습니다.\n검색 조건이나 상태를 바꿔 확인하세요.")
        fill_table(self.sale_table, table_rows)
        center_table_columns(self.sale_table, (0, 1, 2, 3, 4, 6, 9))
        customer_reference(self.sale_table, 3, 0)
        self.sale_table.clearSelection()
        self.sale_table.setCurrentCell(-1, -1)
        self.sale_table.blockSignals(False)
        self.selected_sale = None

        if selected_number:
            index = next(
                (i for i, row in enumerate(self.sale_rows)
                 if row["metadata"]["orderNumber"] == selected_number),
                None,
            )
            if index is not None:
                self.sale_table.selectRow(index)
                self.selected_sale = self.sale_rows[index]

        self.customer_list_status.setText(
            f"목록 {len(self.sale_rows):,}건 · 입금확인 주문을 우선 표시합니다."
            if self.sale_rows else "조건에 맞는 판매·고객 기록이 없습니다."
        )
        self.set_page_count(self.PAGE_SALES, len(self.sale_rows))
        if self.selected_sale:
            self.select_sale()
        self.update_controls()

    def select_sale(self):
        if not getattr(self, "workspace_ready", False):
            return super().select_sale()
        index = self.sale_table.currentRow()
        self.selected_sale = self.sale_rows[index] if 0 <= index < len(self.sale_rows) else None
        if self.selected_sale:
            order = self.selected_sale["private"]["order"]
            self.customer_list_status.setText(
                f"{self.selected_sale['metadata']['orderNumber']} · "
                f"{order.get('customerName','')} · "
                f"{customer_status_text(self.selected_sale['metadata']['status'])} · "
                + ("다음 작업: 배송정보 · 송장 준비" if customer_status_text(self.selected_sale['metadata']['status']) == "입금확인"
                   else "완료 내역 확인")
            )
        else:
            self.customer_list_status.setText("주문을 선택하세요.")
        self.update_controls()

    def open_customer_order(self, *args):
        if getattr(self, "demo_mode", False) or not self.manager or self.task:
            return
        index = self.sale_table.currentRow()
        if not 0 <= index < len(self.sale_rows):
            QMessageBox.information(self, "고객 관리", "관리할 주문을 선택해주세요.")
            return
        row = self.sale_rows[index]
        dialog = CustomerOrderDialog(row, self)
        self.timer.stop()
        result = dialog.exec()
        self.timer.start()
        if result != QDialog.DialogCode.Accepted or not dialog.mode:
            return

        fields = dialog.collected_fields
        meta = row["metadata"]
        target = {"deliver": "배송완료", "cancel": "구매취소"}.get(dialog.mode)
        messages = {
            "save": "고객·배송 정보 저장 중…",
            "deliver": "배송완료 상태 저장 중…",
            "cancel": "구매취소 상태 저장 중…",
        }
        self.run_job(
            messages[dialog.mode],
            lambda: self.manager.save_sale(
                meta["orderNumber"], meta["revision"], fields, target
            ),
            self.customer_order_saved,
        )

    def customer_order_saved(self, value):
        self.apply_view(value)
        self.message.setText("판매 · 고객 관리 내용을 운영 시트에 저장했습니다.")
        self.navigate(self.PAGE_SALES)

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
        self.set_page_count(self.PAGE_SHIPPING, len(rows))
        self.shipping_rows = rows
        self.shipping_table.blockSignals(True)
        self.shipping_table.set_empty_message("조건에 맞는 배송 주문이 없습니다.\n상태 필터 또는 가져오기를 확인하세요.")
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
        self.shipping_status.setText(f"선택 {len(selected)}건 / 목록 {len(self.shipping_rows)}건 · " +
                                     ("로젠 XLS 저장 또는 송장번호 반영을 진행하세요." if selected else "송장을 준비할 주문을 선택하세요. Ctrl·Shift로 여러 건을 선택할 수 있습니다."))

    def edit_quote(self, checked=False, draft=None, request_status=""):
        if getattr(self, "demo_mode", False) or not self.manager or self.task or not self.allow_discard():
            return
        self.dirty = False
        dialog = QuoteDialog(draft or new_draft(), self, request_status=request_status)
        # Modal input is protected from the 10-minute refresh timer as well.
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

    def quote_confirmed(self, value):
        self.apply_view(value)
        self.message.setText("입금확인으로 판매 등록했습니다. 배송을 준비하세요.")
        self.navigate(self.PAGE_SALES)

    def quote_finalized(self, value):
        self.apply_view(value)
        self.message.setText("견적을 확정했습니다. 확정 견적에서 문자·메일 등 후속 작업을 진행하세요.")
        self.navigate(self.PAGE_QUOTES)

    def draft_deleted(self, value):
        self.apply_view(value)
        self.message.setText("선택한 견적을 삭제했습니다.")
        self.navigate(self.PAGE_QUOTES)

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
        if source or status in ("견적확정", "견적발송완료"):
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
        if result != QDialog.DialogCode.Accepted or not dialog.mode:
            self.apply_view(self.manager.view())
            return

        delivery_draft = copy.deepcopy(dialog.draft)
        if dialog.mode == "sent":
            self.run_job(
                "견적발송완료 상태를 운영 시트에 저장 중…",
                lambda: self.manager.mark_quote_sent(delivery_draft),
                self.quote_sent,
            )
        elif dialog.mode == "paid":
            self.run_job(
                "입금확인 판매를 운영 시트에 저장 중…",
                lambda: self.manager.confirm_draft(delivery_draft),
                self.quote_confirmed,
            )
    def quote_sent(self, value):
        self.apply_view(value)
        self.message.setText("견적발송완료로 저장했습니다. 고객 회신과 입금 여부를 확인하세요.")
        self.navigate(self.PAGE_QUOTES)

    def remove_draft(self):
        index = self.draft_table.currentRow()
        if not self.manager or not 0 <= index < len(self.draft_rows):
            return
        if self.draft_rows[index]["id"] in getattr(self, "cloud_quote_ids", set()):
            QMessageBox.information(self, "서버 견적", "서버에서 불러온 견적은 PC 임시 견적 삭제 대상이 아닙니다.")
            return
        if QMessageBox.question(self, "견적 삭제", "선택한 PC 견적을 삭제할까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) == QMessageBox.StandardButton.Yes:
            identifier = self.draft_rows[index]["id"]
            self.run_job("견적 삭제 중…", lambda: self.manager.delete_draft(identifier), self.draft_deleted)

    def show_order(self, number):
        if not self.allow_discard():
            return
        self.dirty = False
        self.state_filter.setCurrentIndex(0)
        self.sale_search.setText(number)
        self.refresh_sales()
        if self.sale_rows:
            self.sale_table.selectRow(0)
        self.navigate(self.PAGE_SALES)

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
            self.navigate(self.PAGE_REQUESTS)

    def manual_sync(self):
        if not getattr(self, "demo_mode", False):
            super().manual_sync()




