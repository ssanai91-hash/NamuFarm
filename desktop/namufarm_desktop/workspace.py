"""FHD seller workspace, built on the existing verified sync controller."""
import copy
import json
import sys
import tempfile
from datetime import datetime
from importlib.resources import files as package_files
from pathlib import Path
from html import escape as html_escape

from PySide6.QtCore import QSettings, Qt, QUrl, QSaveFile, QIODevice, QTimer, Signal, Slot, QRectF, QSize, QEvent
from PySide6.QtGui import (QColor, QDesktopServices, QFont, QFontMetricsF, QImage, QIntValidator,
    QKeySequence, QPainter, QPdfWriter, QPen, QPixmap, QShortcut, QTextDocument, QTextOption, QPageSize)
from PySide6.QtPrintSupport import QPrinter, QPrintDialog
from PySide6.QtWidgets import (QApplication, QBoxLayout, QButtonGroup, QCheckBox, QComboBox, QDialog, QDoubleSpinBox, QFileDialog, QFormLayout,
    QFrame, QGridLayout, QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QListWidget,
    QMessageBox, QPlainTextEdit, QPushButton, QRadioButton, QScrollArea, QSplitter, QStackedWidget,
    QSizePolicy, QStyle, QSystemTrayIcon, QTableWidget, QTableWidgetItem, QTextBrowser, QVBoxLayout, QWidget, QSpinBox)

from . import __version__
from .ui_theme import (align_table, fit_window, scroll_dialog, scroll_page, workspace_style,
                       CustomerDelegate, StatusDelegate, customer_reference, CompactDateDelegate,
                       NavigationButton, QuoteRemarksDelegate, size_table_to_rows, collapsible, quote_workbench_style)
from .ui_icons import set_button_icon, ui_icon
from .app import MainWindow, Task, table, fill_table, money, timestamp
from .commerce import (ISSUER, new_draft, new_manual_draft, draft_amounts, validate_draft, quote_html, quote_document_html, shipping_html,
                       shipping_problem, parse_tracking_csv, tracking_number, payment_account_text, payment_notice, quote_delivery_fingerprint,
                       payment_confirmation_message, seller_note_draft, customer_note, tracking_fields, shipping_prepared,
                       shipping_ready, TRACKING_PREFIX, sale_number, normalize_sms_recipient, item_specification, validate_working_draft)
from .domain import SEOUL, profit, require
from .errors import UserError
from .shipping_excel import shipping_xls, parse_tracking_xls, export_orders, UPLOAD_SHEET
from .nursery_ui import NurseryPage, DigitalClock
from .nursery import NurseryStore
from .quote_species import SpeciesNameEditor, QuoteSpeciesPicker

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


def quote_amount_detail(draft):
    """The same item/freight breakdown beside totals and before delivery."""
    items = sum(item["quantity"] * item["unitPrice"] for item in draft["items"])
    freight = (f"선불 택배비 {draft['shippingFee']:,}원" if draft["isPrepaid"] else "택배비 후불 · 별도")
    parts = [f"묘목 {items:,}원", freight]
    if draft.get("taxAmount"):
        parts.append(f"세액 {draft['taxAmount']:,}원")
    return " · ".join(parts)


def fit_delivery_message(dialog):
    if not hasattr(dialog, "status") or getattr(dialog, "_delivery_fitting", False):
        return
    large = bool(QApplication.instance().property("namufarmLargeFont"))
    dialog.message.setMinimumHeight(100)
    dialog.status.setFixedHeight(42 if large else 38)
    layout = dialog.layout()
    layout.setSpacing(4)
    layout.setContentsMargins(12, 8, 12, 8)
    if not dialog.isVisible():
        return
    dialog._delivery_fitting = True
    try:
        layout.activate()
        body = dialog.message
        width = max(1, body.viewport().width() - 12)
        height = max(1, body.viewport().height() - 12)
        # Fit ordinary quotes while keeping long messages fully editable and accessible.
        minimum, preferred = (14, 16) if large else (12, 14)
        size = preferred
        document = QTextDocument()
        document.setDocumentMargin(0)
        document.setPlainText(body.toPlainText())
        option = document.defaultTextOption()
        option.setWrapMode(QTextOption.WrapMode.WrapAtWordBoundaryOrAnywhere)
        document.setDefaultTextOption(option)
        while True:
            document.setDefaultFont(_quote_font(size))
            document.setTextWidth(width)
            if document.size().height() <= height or size <= minimum:
                break
            size -= 1
        if body.property("deliveryFontSize") != size:
            body.setProperty("deliveryFontSize", size)
            body.setStyleSheet(f"QPlainTextEdit{{padding:6px;font-size:{size}px;}}")
    finally:
        dialog._delivery_fitting = False


def setup_delivery_reading(dialog, layout):
    """Give the body all remaining space, with one line for the current status."""
    dialog.setMinimumSize(620, 560)
    dialog.message.setLineWrapMode(QPlainTextEdit.LineWrapMode.WidgetWidth)
    dialog.message.setStyleSheet("QPlainTextEdit{padding:6px;font-size:@size@px;}".replace(
        "@size@", "16" if QApplication.instance().property("namufarmLargeFont") else "14"))
    dialog.message.document().setDocumentMargin(0)
    dialog.message.textChanged.connect(lambda: fit_delivery_message(dialog))
    fit_delivery_message(dialog)
    QTimer.singleShot(0, lambda: fit_delivery_message(dialog))



def polish_table(widget):
    large = bool(QApplication.instance().property("namufarmLargeFont"))
    compact = bool(QApplication.instance().property("namufarmCompactLayout"))
    row_height = (56 if large else 48) if compact else (64 if large else 56)
    widget.verticalHeader().setMinimumSectionSize(row_height)
    widget.verticalHeader().setDefaultSectionSize(row_height)
    widget.setShowGrid(False)
    widget.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
    widget.horizontalHeader().setDefaultAlignment(Qt.AlignmentFlag.AlignCenter)
    if widget.property("visibleItemRows"):
        size_table_to_rows(widget, int(widget.property("visibleItemRows")))


def fit_quote_density(dialog):
    """Apply typography to logical window size, without scaling a rendered bitmap."""
    compact = dialog.width() < 1440 or dialog.height() < 850
    if dialog.property("compactLayout") == compact:
        return
    dialog.setProperty("compactLayout", compact)
    # Re-evaluate ancestor property selectors, including the sales dialog overrides.
    dialog.setStyleSheet(dialog.styleSheet())


class UnitPriceEditor(QWidget):
    """Manual unit-price input with 500-won step buttons.

    Keeping the price in a dedicated editor also gives a future Excel price
    lookup one stable place to inject an automatically resolved unit price.
    """
    changed = Signal()
    STEP = 500
    MAX_PRICE = 2147483647

    def __init__(self, value=2000, parent=None, *, compact=False, caption="판매 단가"):
        super().__init__(parent)
        self.setObjectName("unitPriceEditor")
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        control_height = (38 if large else 32) if compact else 44
        control_width = 32 if compact else 44
        row = QHBoxLayout(self)
        row.setContentsMargins(2 if compact else 3, 0, 2 if compact else 3, 0)
        row.setSpacing(6 if compact else 4)

        self.minus = QPushButton("-")
        self.minus.setToolTip(caption + " 500원 내리기")
        self.minus.setFixedSize(control_width, control_height)
        self.minus.setAccessibleName(caption + " 500원 내리기")
        self.minus.clicked.connect(lambda: self.adjust(-self.STEP))
        row.addWidget(self.minus)

        self.input = QLineEdit(str(max(0, int(value or 0))))
        self.input.setValidator(QIntValidator(0, self.MAX_PRICE, self.input))
        self.input.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.input.setPlaceholderText(caption)
        self.input.setAccessibleName(caption + " (원)")
        if compact:
            self.input.setFixedHeight(control_height)
            self.input.setMinimumWidth(120 if large else 104)
        self.input.textChanged.connect(lambda _text: self.changed.emit())
        row.addWidget(self.input, 1)

        self.plus = QPushButton("+")
        self.plus.setToolTip(caption + " 500원 올리기")
        self.plus.setFixedSize(control_width, control_height)
        self.plus.setAccessibleName(caption + " 500원 올리기")
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


def _quote_wrapped_lines(text, width, size):
    """Preserve every character while measuring the same font used for printing."""
    metrics = QFontMetricsF(_quote_font(size))
    result, current = [], ""
    for character in str(text).replace("\t", " "):
        if character == "\n":
            result.append(current)
            current = ""
        elif current and metrics.horizontalAdvance(current + character) > width:
            result.append(current)
            current = character
        else:
            current += character
    result.append(current)
    return result


# Equal name/specification columns; keep the remaining widths and 884-pixel total.
OFFICIAL_QUOTE_COLUMN_WIDTHS = (207.5, 207.5, 64, 78, 151, 52, 124)


def official_quote_page_plan(draft):
    """Paginate item rows and long notes instead of dropping rows or clipping text."""
    validate_draft(draft)
    rows = [(item["species"], item.get("specification", ""), int(item["quantity"]),
             int(item["unitPrice"]), int(item["quantity"]) * int(item["unitPrice"]), "", item.get("remarks", ""))
            for item in draft["items"]]
    if draft.get("isPrepaid") and int(draft.get("shippingFee", 0)) > 0:
        fee = int(draft["shippingFee"])
        rows.append(("운반비", "선불", 1, fee, fee, "", "택배비"))
    pages, current, used = [], [], 0
    for values in rows:
        height = max(32, *[int(len(_quote_wrapped_lines(
                                  values[column], OFFICIAL_QUOTE_COLUMN_WIDTHS[column] - 8, size)) *
                                  QFontMetricsF(_quote_font(size)).height() + 8)
                          for column, size in ((0, 15), (1, 12), (6, 12))])
        require(height <= 743, "한 품목의 이름·규격·비고가 너무 깁니다. 내용을 줄여주세요.")
        if current and used + height > 743:
            pages.append({"rows": current})
            current, used = [], 0
        current.append((values, height))
        used += height
    pages.append({"rows": current})
    note_lines = _quote_wrapped_lines("요청사항 : " + (draft.get("note", "").strip() or "없음"), 664, 12)
    footer_count = max(1, int(56 / QFontMetricsF(_quote_font(12)).height()))
    first_note = "\n".join(note_lines[:footer_count])
    for page in pages:
        page.update(note=first_note, continuation="")
    remaining = note_lines[footer_count:]
    body_count = max(1, int(690 / QFontMetricsF(_quote_font(15)).height()))
    for offset in range(0, len(remaining), body_count):
        pages.append({"rows": [], "note": "요청사항은 위 본문을 확인해주세요.",
                      "continuation": "\n".join(remaining[offset:offset + body_count])})
    for index, page in enumerate(pages):
        page.update(number=index + 1, count=len(pages), noteContinues=bool(remaining))
    return pages


def render_official_quote_images(draft):
    return [render_official_quote_image(draft, _page=page) for page in official_quote_page_plan(draft)]


def render_official_quote_image(draft, *, _page=None):
    """Render the official quote at the same 955x1342 geometry as the supplied paper form."""
    validate_draft(draft)
    if _page is None:
        pages = official_quote_page_plan(draft)
        require(len(pages) == 1, "여러 페이지 견적은 공식견적서 생성 기능을 사용해주세요.")
        _page = pages[0]
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
                    align=Qt.AlignmentFlag.AlignCenter, color=black, minimum=9, wrap=False):
        x, y, w, h = rect
        text = str(text)
        font = _quote_font(size, bold)
        while not wrap and font.pixelSize() > minimum:
            metrics = QFontMetricsF(font)
            if metrics.horizontalAdvance(text) <= max(2, w - 8) and metrics.height() <= max(2, h - 3):
                break
            font.setPixelSize(font.pixelSize() - 1)
        painter.setPen(color)
        painter.setFont(font)
        flags = int(align) | (int(Qt.TextFlag.TextWrapAnywhere) if wrap else 0)
        painter.drawText(QRectF(x + 4, y + 1, max(1, w - 8), max(1, h - 2)), flags, text)

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

    xs = [left]
    for column_width in OFFICIAL_QUOTE_COLUMN_WIDTHS:
        xs.append(xs[-1] + column_width)
    box(left, header_top, right, notes_top, thick)
    if _page["continuation"]:
        fitted_text((left, header_top, right - left, data_top - header_top), "요청사항 (계속)", 16, True)
        fitted_text((left + 12, data_top + 12, right - left - 24, sum_top - data_top - 24),
                    _page["continuation"], 15, False,
                    Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignTop, minimum=15, wrap=True)
    else:
        headers = ["품   명", "규격", "수량", "단   가", "공급가액", "세   액", "비   고"]
        for x in xs[1:-1]:
            line(x, header_top, x, notes_top)
        line(left, data_top, right, data_top)
        for index, title in enumerate(headers):
            fitted_text((xs[index], header_top, xs[index + 1] - xs[index], data_top - header_top), title, 16, minimum=12)
        y = data_top
        for values, height in _page["rows"]:
            for column, value in enumerate(values):
                numeric = isinstance(value, int)
                text = f"{value:,}" if numeric else value
                align = Qt.AlignmentFlag.AlignVCenter | (Qt.AlignmentFlag.AlignRight if column in (3, 4, 5) else
                         Qt.AlignmentFlag.AlignLeft if column in (0, 6) else Qt.AlignmentFlag.AlignHCenter)
                fitted_text((xs[column], y, xs[column + 1] - xs[column], height), text,
                            15 if column == 0 or numeric else 12, align=align,
                            minimum=12 if column in (1, 6) else 10, wrap=column in (0, 1, 6))
            y += height
            line(left, y, right, y)
        while y + 32 < sum_top:
            y += 32
            line(left, y, right, y)
        line(left, sum_top, right, sum_top)

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

    payment = payment_account_text(draft)
    shipping_note = (
        f"택배비 선불 {int(draft.get('shippingFee', 0) or 0):,}원 (총금액 포함)"
        if draft.get("isPrepaid")
        else f"택배비 후불 / 선불 시 {int(draft.get('shippingFee', 0) or 0):,}원"
    )
    fitted_text((228, 1091, 672, 25), f"1. {payment}", 14,
                align=Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
    fitted_text((228, 1119, 672, 25), "2. 견적후 일정기간후 금액변동 있을수 있음.", 14,
                align=Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
    fitted_text((228, 1146, 672, 28),
                "3. 계산서 또는 현금영수증발행은 정상가격 처리후 7일이내 발행가능함.", 13,
                align=Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, minimum=10)
    fitted_text((228, 1177, 672, 24), f"4. {shipping_note}", 13,
                align=Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, minimum=11)
    fitted_text((228, 1205, 672, 60), _page["note"], 12,
                align=Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignTop, minimum=12, wrap=True)
    if _page["count"] > 1:
        caption = f"{_page['number']} / {_page['count']}쪽"
        if _page["noteContinues"] and not _page["continuation"]:
            caption += " · 긴 요청사항은 뒤쪽에 계속됩니다."
        fitted_text((26, 1280, 884, 28), caption, 13)

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
    images = [image] if isinstance(image, QImage) else list(image)
    require(bool(images), "공식견적서에 출력할 페이지가 없습니다.")
    for index, page_image in enumerate(images):
        if index:
            require(writer.newPage(), "공식견적서의 다음 페이지를 생성하지 못했습니다.")
        printed = page_image.scaled(page_rect.size(), Qt.AspectRatioMode.KeepAspectRatio,
                                    Qt.TransformationMode.SmoothTransformation)
        x = page_rect.x() + (page_rect.width() - printed.width()) // 2
        y = page_rect.y() + (page_rect.height() - printed.height()) // 2
        painter.drawImage(x, y, printed)
    painter.end()

    require(path.is_file() and path.stat().st_size > 0, "공식견적서 PDF를 임시 생성하지 못했습니다.")
    return path


class QuotePagePreview(QLabel):
    """Fit one complete page to the available viewport without scrollbars."""

    def __init__(self):
        super().__init__()
        self.page_image = QImage()
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setContentsMargins(8, 8, 8, 8)
        self.setStyleSheet("background:#dfe3df;border:0;")
        self.setMinimumSize(0, 0)
        self.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Ignored)

    def set_page(self, image):
        self.page_image = image
        self.fit_page()

    def fit_page(self):
        size = self.contentsRect().size()
        if not self.page_image.isNull() and size.width() > 0 and size.height() > 0:
            self.setPixmap(QPixmap.fromImage(self.page_image).scaled(
                size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.fit_page()


def preview_temporary_quote(parent, image, *, pdf_path=None, filename="공식견적서.pdf"):
    """Fit, print or explicitly save the generated A4 quotation."""
    dialog = QDialog(parent)
    dialog.setWindowTitle("공식 견적서 미리보기")
    dialog.setSizeGripEnabled(True)
    screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
    available = screen.availableGeometry() if screen else None
    if available:
        dialog.resize(min(1180, available.width() - 50), min(1000, available.height() - 50))
    else:
        dialog.resize(1120, 940)

    layout = QVBoxLayout(dialog)
    layout.setContentsMargins(12, 12, 12, 10)

    images = [image] if isinstance(image, QImage) else list(image)
    require(bool(images), "공식견적서에 표시할 페이지가 없습니다.")
    page = QuotePagePreview()
    layout.addWidget(page, 1)

    row = QHBoxLayout()
    current_page = 0
    previous_button = button("이전")
    next_button = button("다음")
    page_number = label("")
    for control in (previous_button, page_number, next_button):
        control.setVisible(len(images) > 1)
        row.addWidget(control)

    def show_page(index):
        nonlocal current_page
        current_page = max(0, min(index, len(images) - 1))
        page.set_page(images[current_page])
        page_number.setText(f"{current_page + 1} / {len(images)}")
        previous_button.setEnabled(current_page > 0)
        next_button.setEnabled(current_page + 1 < len(images))

    previous_button.clicked.connect(lambda: show_page(current_page - 1))
    next_button.clicked.connect(lambda: show_page(current_page + 1))
    show_page(0)
    save_status = label("A4 전체 보기", "muted")
    save_status.setMinimumWidth(0)
    save_status.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
    row.addWidget(save_status, 1)

    def save_pdf():
        try:
            base = (Path(sys.executable).resolve().parent if getattr(sys, "frozen", False)
                    else Path(__file__).resolve().parents[1])
            folder = base / "자료" / "공식견적서"
            folder.mkdir(parents=True, exist_ok=True)
            target = folder / Path(filename).name
            original = target
            count = 2
            while target.exists():
                target = original.with_name(f"{original.stem} ({count}){original.suffix}")
                count += 1
            with tempfile.TemporaryDirectory(prefix="namufarm-quote-save-") as temporary:
                source = Path(pdf_path) if pdf_path else None
                if source is None or not source.is_file():
                    source = write_temporary_quote_pdf(images, Path(temporary) / "quote.pdf")
                data = source.read_bytes()
                require(bool(data), "저장할 공식견적서 PDF가 비어 있습니다.")
                output = QSaveFile(str(target))
                require(output.open(QIODevice.OpenModeFlag.WriteOnly), "공식견적서 저장 폴더를 확인해주세요.")
                if output.write(data) != len(data):
                    output.cancelWriting()
                    raise UserError("공식견적서를 저장하지 못했습니다.")
                require(output.commit(), "공식견적서를 저장하지 못했습니다.")
            save_status.setText("저장완료 · " + target.name)
            save_status.setToolTip(str(target))
        except (OSError, UserError) as error:
            QMessageBox.warning(dialog, "공식견적서 저장", str(error))

    def print_only():
        printer = QPrinter(QPrinter.PrinterMode.HighResolution)
        printer.setPageSize(QPageSize(QPageSize.PageSizeId.A4))
        if QPrintDialog(printer, dialog).exec() != QDialog.DialogCode.Accepted:
            return
        painter = QPainter(printer)
        page_rect = printer.pageLayout().paintRectPixels(printer.resolution())
        for index, page_image in enumerate(images):
            if index and not printer.newPage():
                painter.end()
                QMessageBox.warning(dialog, "프린터 출력", "다음 페이지를 출력하지 못했습니다.")
                return
            printed = page_image.scaled(page_rect.size(), Qt.AspectRatioMode.KeepAspectRatio,
                                       Qt.TransformationMode.SmoothTransformation)
            x = page_rect.x() + (page_rect.width() - printed.width()) // 2
            y = page_rect.y() + (page_rect.height() - printed.height()) // 2
            painter.drawImage(x, y, printed)
        painter.end()

    row.addWidget(button("저장하기", save_pdf, True))
    row.addWidget(button("프린터 출력", print_only))
    row.addWidget(button("닫기", dialog.accept))
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


class SidebarTree(QLabel):
    """A scalable library tree symbol that yields space to navigation and the clock."""
    def __init__(self):
        super().__init__()
        self.setObjectName("sidebarTree")
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Ignored)
        self.setMinimumHeight(0)
        self.setMaximumHeight(160)
        self.setAccessibleName("나무팜 나무 그림")

    def resizeEvent(self, event):
        super().resizeEvent(event)
        extent = min(144, self.width() - 24, self.height() - 12)
        if extent < 24:
            self.clear()
        else:
            self.setPixmap(ui_icon("namufarm_tree", "#9dc7ac").pixmap(QSize(extent, extent), self.devicePixelRatioF()))


class CustomerInfoValue(QTextBrowser):
    """Selectable plain customer text that also wraps long e-mail/receipt tokens."""
    def __init__(self, text, object_name):
        super().__init__()
        self.setObjectName(object_name)
        self.setFrameShape(QFrame.Shape.NoFrame)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.setOpenLinks(False)
        self.document().setDocumentMargin(0)
        option = self.document().defaultTextOption()
        option.setWrapMode(QTextOption.WrapMode.WrapAtWordBoundaryOrAnywhere)
        self.document().setDefaultTextOption(option)
        self.setPlainText(text)
        self.setFixedHeight(self.fontMetrics().height() + 2)

    def text(self):
        return self.toPlainText()

    def heightForWidth(self, width):
        self.document().setTextWidth(max(1, width))
        return int(self.document().size().height()) + 2


class DeliveryActionButton(QPushButton):
    """Keyboard-accessible channel action with its status inside the left edge."""
    def __init__(self, title, icon, name, parent=None):
        super().__init__(parent)
        self.setObjectName(name)
        self.setAccessibleName(title)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        row = QHBoxLayout(self)
        row.setContentsMargins(8, 0, 8, 0)
        row.setSpacing(6)
        self.status_badge = label("", "channelStatusBadge")
        self.status_badge.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.status_badge.setFixedHeight(28)
        row.addWidget(self.status_badge)
        row.addStretch(1)
        symbol = QLabel()
        self.symbol = symbol
        self.symbol_name = icon
        symbol.setFixedSize(18, 18)
        symbol.setPixmap(ui_icon(icon, "#ffffff" if name == "smsAction" else "#24313a").pixmap(18, 18))
        row.addWidget(symbol)
        self.title_label = label(title, "deliveryActionTitle")
        row.addWidget(self.title_label)
        row.addStretch(1)
        for child in (self.status_badge, symbol, self.title_label):
            child.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.setMinimumWidth(0)

    def refresh_symbol(self):
        color = "#6a7b71" if not self.isEnabled() else "#ffffff" if self.objectName() == "smsAction" else "#24313a"
        self.symbol.setPixmap(ui_icon(self.symbol_name, color).pixmap(18, 18))

    def changeEvent(self, event):
        super().changeEvent(event)
        if event.type() == QEvent.Type.EnabledChange and hasattr(self, "symbol_name"):
            self.refresh_symbol()


class QuoteDeliveryTools:
    """Shared document and channel actions for editing and confirmed quotations."""

    def build_delivery_tools(self, content, compact=False, document_content=None, delivery_content=None):
        tools = QHBoxLayout()
        tools.setSpacing(12 if compact else 24)
        self.delivery_tools_layout = tools
        document = QVBoxLayout()
        document.setSpacing(8)
        document.addWidget(label("공식견적서", "deliverySectionTitle"))
        if not compact:
            document_hint = label("고객 요청에 따른 공식견적서를 생성합니다.", "deliveryCaption", True)
            document_hint.setFixedHeight(28)
            document.addWidget(document_hint)
        document_buttons = QHBoxLayout()
        self.document_buttons_layout = document_buttons
        document_buttons.setSpacing(12)
        self.generate_quote_button = QPushButton("공식견적서 생성")
        self.generate_quote_button.setObjectName("pdfAction")
        self.generate_quote_button.setProperty("documentGenerate", True)
        self.generate_quote_button.clicked.connect(self.generate_official_quote)
        self.preview_quote_button = QPushButton("미리보기")
        self.preview_quote_button.setObjectName("pdfAction")
        self.preview_quote_button.setEnabled(False)
        self.preview_quote_button.clicked.connect(self.preview_official_quote)
        document_buttons.addWidget(self.generate_quote_button, 1)
        document_buttons.addWidget(self.preview_quote_button, 1)
        document.addLayout(document_buttons)
        self.quote_file_label = label("공식 견적서 미생성", "deliveryPdfName", True)
        self.quote_file_label.setMinimumWidth(0)
        self.quote_file_label.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        self.quote_file_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.quote_file_label.setAccessibleName("생성된 공식견적서 PDF 파일명")
        document.addWidget(self.quote_file_label)
        self.delivery_tools_rule = None
        if document_content is None:
            tools.addLayout(document, 1)
            divider = QFrame()
            self.delivery_tools_rule = divider
            divider.setObjectName("deliveryVerticalRule")
            divider.setFixedWidth(1)
            tools.addWidget(divider)
        else:
            document_content.addLayout(document)
        integrated = compact and document_content is not None
        self.integrated_delivery = integrated
        delivery = QVBoxLayout() if delivery_content is None else None
        if delivery is not None:
            delivery.setSpacing(8)
            self.delivery_heading = label("고객 전달", "deliverySectionTitle")
            delivery.addWidget(self.delivery_heading)
        send_buttons = QHBoxLayout()
        self.delivery_send_layout = send_buttons
        send_buttons.setSpacing(12)
        if integrated:
            send_buttons.addStretch(1)
        if integrated:
            self.sms_button = DeliveryActionButton("문자 보내기", "message", "smsAction")
            self.email_button = DeliveryActionButton("메일 보내기", "mail", "mailAction")
            self.sms_status_badge = self.sms_button.status_badge
            self.email_status_badge = self.email_button.status_badge
        else:
            badges = QHBoxLayout()
            badges.setSpacing(8)
            self.sms_status_badge = QLabel()
            self.email_status_badge = QLabel()
            for badge in (self.sms_status_badge, self.email_status_badge):
                badge.setObjectName("channelStatusBadge")
                badge.setAlignment(Qt.AlignmentFlag.AlignCenter)
                badge.setFixedHeight(28)
                badges.addWidget(badge)
            badges.addStretch()
            delivery.addLayout(badges)
            self.sms_button = QPushButton("문자 보내기")
            self.sms_button.setObjectName("smsAction")
            self.email_button = QPushButton("메일 보내기")
            self.email_button.setObjectName("mailAction")
            set_button_icon(self.sms_button, "message", "#ffffff")
            set_button_icon(self.email_button, "mail")
        self.sms_button.clicked.connect(self.open_sms)
        self.sms_button.setEnabled(bool(self.draft.get("customerPhone")))
        self.email_button.clicked.connect(self.open_email)
        self.email_button.setEnabled(bool(self.draft.get("customerEmail")))
        self.update_delivery_badges()
        send_buttons.addWidget(self.sms_button, 1)
        send_buttons.addWidget(self.email_button, 1)
        if delivery is not None:
            delivery.addLayout(send_buttons)
            if not integrated:
                delivery.addStretch()
            tools.addLayout(delivery, 1)
        else:
            delivery_content.addLayout(send_buttons, 1)
        content.addLayout(tools)

        set_button_icon(self.generate_quote_button, "document")
        set_button_icon(self.preview_quote_button, "document")

    def quote_number(self):
        return (
            self.draft.get("requestReceiptNumber")
            or ("PCQ_" + self.draft["id"][:8])
        )


    def update_delivery_badges(self):
        email_field = getattr(self, "fields", {}).get("customerEmail")
        customer_email = email_field.text().strip() if email_field is not None else self.draft.get("customerEmail")
        sms_state = self.delivery_state.get("smsState", "")
        if sms_state == "success":
            sms_text, sms_style, sms_done = "문자 전송완료", "success", True
        elif sms_state == "failed":
            sms_text, sms_style, sms_done = "문자 전송실패", "failed", False
        elif sms_state == "pending" or self.delivery_state.get("smsMessageId"):
            sms_text, sms_style, sms_done = "문자 결과확인", "pending", False
        else:
            sms_text, sms_style, sms_done = "문자 미전송", "idle", False

        email_done = bool(self.delivery_state.get("emailSentAt"))
        if getattr(self, "integrated_delivery", False):
            try:
                fingerprint = quote_delivery_fingerprint(self.collect())
            except UserError:
                fingerprint = None
            sms_current = fingerprint and self.delivery_state.get("smsQuoteFingerprint") == fingerprint
            email_current = fingerprint and self.delivery_state.get("emailQuoteFingerprint") == fingerprint
            if sms_done and not sms_current:
                sms_text, sms_style, sms_done = "문자 재전송", "pending", False
            if email_done and not email_current:
                email_done = False
                email_text = "메일 재전송"
            else:
                email_text = "메일 전송완료" if email_done else "메일 미전송" if customer_email else "이메일 없음"
        else:
            email_text = "메일 전송완료" if email_done else "메일 미전송" if customer_email else "이메일 없음"
        self.sms_status_badge.setText(sms_text.removeprefix("문자 ") if getattr(self, "integrated_delivery", False) else sms_text)
        self.sms_status_badge.setProperty("done", sms_done)
        self.sms_status_badge.setProperty("state", sms_style)
        self.sms_status_badge.style().unpolish(self.sms_status_badge)
        self.sms_status_badge.style().polish(self.sms_status_badge)

        self.email_status_badge.setText(email_text.removeprefix("메일 ") if getattr(self, "integrated_delivery", False) else email_text)
        self.email_status_badge.setProperty("done", email_done)
        self.email_status_badge.setProperty("state", "success" if email_done else "idle")
        self.email_status_badge.style().unpolish(self.email_status_badge)
        self.email_status_badge.style().polish(self.email_status_badge)
        for action, text in ((self.sms_button, sms_text), (self.email_button, email_text)):
            action.setAccessibleName(action.title_label.text() + ", " + text if isinstance(action, DeliveryActionButton) else action.text())
            action.setToolTip(text)



    def open_sms(self):
        if not self.persist_shipping():
            return
        dialog = QuoteSmsDialog(self.draft, self.manager, self)
        dialog.exec()
        self.delivery_state = dict(dialog.delivery_state or (self.manager.quote_delivery_state(self.draft["id"]) if self.manager else self.delivery_state))
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
        self.generated_quote_images = []
        self.generated_quote_path = None
        self.quote_file_label.setToolTip("")
        if path:
            try:
                Path(path).unlink(missing_ok=True)
            except OSError:
                pass


    def generate_official_quote(self):
        if not self.persist_shipping():
            return
        try:
            images = render_official_quote_images(self.draft)
            filename = self.official_quote_filename()
            temp_dir = Path(tempfile.gettempdir()) / "NamuFarm" / "official_quotes"
            path = write_temporary_quote_pdf(images, temp_dir / filename)
            self.generated_quote_images = images
            self.generated_quote_image = images[0]
            self.generated_quote_path = path
            self.preview_quote_button.setEnabled(True)
            self.generate_quote_button.setText("공식견적서 재생성")
            self.quote_file_label.setText(filename)
            self.quote_file_label.setToolTip(filename)
        except (UserError, OSError) as error:
            self.cleanup_generated_quote()
            self.preview_quote_button.setEnabled(False)
            self.quote_file_label.setText("공식 견적서 생성실패")
            QMessageBox.warning(self, "공식견적서 생성", str(error))


    def preview_official_quote(self):
        if self.generated_quote_image is None or not self.generated_quote_path:
            QMessageBox.information(self, "공식견적서", "먼저 공식견적서를 생성해주세요.")
            return
        preview_temporary_quote(self, self.generated_quote_images or self.generated_quote_image,
                                pdf_path=self.generated_quote_path, filename=self.official_quote_filename())



class QuoteDialog(QuoteDeliveryTools, QDialog):
    """Website and seller-entered quotes share document, item and delivery tools."""
    def __init__(self, draft, parent=None, request_status='', manager=None, *, items_only=False):
        super().__init__(parent)
        draft = seller_note_draft(draft)
        self.draft, self.mode, self.request_status = (copy.deepcopy(draft), '', request_status)
        self._workbench_ready = False
        self.working_save_task = None
        self._workbench_closed = False
        self._workbench_fit_timer = QTimer(self)
        self._workbench_fit_timer.setSingleShot(True)
        self._workbench_fit_timer.timeout.connect(self.fit_workbench_rows)
        self._workbench_notes_timer = QTimer(self)
        self._workbench_notes_timer.setSingleShot(True)
        self._workbench_notes_timer.timeout.connect(self.align_workbench_notes)
        self.items_only = items_only
        self.simple_request = bool(draft.get('sourceRequestId'))
        self.manual_quote = not self.simple_request and draft.get('manualStatus') in ('작성중', '견적확정', '견적발송완료')
        self.workbench = self.simple_request or self.manual_quote or items_only or getattr(self, "confirmed_result", False)
        if self.simple_request:
            self.draft.setdefault('requestNote', self.draft.get('note', ''))
        self.manager = manager
        self.delivery_state = manager.quote_delivery_state(draft["id"]) if manager else {}
        self.generated_quote_image = None
        self.generated_quote_images = []
        self.generated_quote_path = None
        self.setWindowTitle('견적 작업대')
        self.setObjectName('quoteEditor')
        if self.workbench:
            self.build_request_workbench()
            return
        self.resize(1280, 840)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 16, 20, 16)
        layout.setSpacing(8)
        head = QHBoxLayout()
        head.addWidget(label('견적 작업대', 'pageTitle'))
        head.addStretch()
        layout.addLayout(head)
        split = QSplitter()
        self.fields = {}
        info = QWidget()
        form = QFormLayout(info)
        form.setContentsMargins(0, 0, 0, 0)
        form.setVerticalSpacing(4)
        form.setFormAlignment(Qt.AlignmentFlag.AlignTop)
        for key, title in (('customerName', '고객명'), ('customerPhone', '연락처'), ('customerEmail', '이메일'), ('receiverName', '수령인'), ('zipNo', '우편번호'), ('roadAddress', '도로명 주소'), ('detailAddress', '상세 주소'), ('validUntil', '견적 유효기간'), ('paymentAccount', '입금계좌 안내')):
            field = QLineEdit(str(draft.get(key, '')))
            field.setPlaceholderText(title)
            field.setAccessibleName(title)
            field.setToolTip(str(draft.get(key, '')))
            field.setCursorPosition(0)
            self.fields[key] = field
            form.addRow(title, field)
        self.note = QPlainTextEdit(draft.get('note', ''))
        self.note.setMaximumHeight(95)
        form.addRow('고객·배송 메모', self.note)
        self.prepaid = QCheckBox('선불 배송비를 합계에 포함')
        self.prepaid.setChecked(draft['isPrepaid'])
        form.addRow(self.prepaid)
        for key, title in (('shippingFee', '배송비 (원)'), ('taxAmount', '세액 (원)')):
            if key == 'shippingFee':
                self.shipping_fee_editor = UnitPriceEditor(draft[key], compact=True, caption="선불 택배비")
                field = self.shipping_fee_editor.input
                form.addRow(title, self.shipping_fee_editor)
            else:
                field = QLineEdit(str(draft[key]))
                form.addRow(title, field)
            self.fields[key] = field
            field.textChanged.connect(self.recalculate)
        self.cost = QCheckBox('입력한 매입 원가를 확인했습니다')
        self.cost.setChecked(draft['costConfirmed'])
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
        content.addWidget(label('견적 품목', 'sectionTitle'))
        content.addWidget(label('수기 견적 품목으로 등록합니다. 휴대폰 재고 수량은 차감하지 않습니다.', 'muted', True))
        self.items = table(['묘목명', '규격', '수량', '판매 단가', '매입 단가', '판매 금액', '비고'])
        self.items.setEditTriggers(self.items.EditTrigger.DoubleClicked | self.items.EditTrigger.EditKeyPressed | self.items.EditTrigger.AnyKeyPressed)
        self.items.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        polish_table(self.items)
        self.items.set_column_layout(((0, 3), (1, 1.6), (2, 1), (3, 1.5), (4, 1.5), (5, 1.8), (6, 2)))
        self.items.itemChanged.connect(self.recalculate)
        content.addWidget(self.items)
        actions = QHBoxLayout()
        actions.addWidget(button('+ 품목 추가', self.add_item))
        actions.addWidget(button('선택 품목 삭제', self.remove_item))
        actions.addStretch()
        content.addLayout(actions)
        self.total = label('', 'metricValue', True)
        content.addWidget(self.total)
        self.amount_detail = label('', 'muted', True)
        content.addWidget(self.amount_detail)
        split.addWidget(right)
        split.setSizes([420, 780])
        layout.addWidget(split, 1)
        footer = QHBoxLayout()
        footer.addWidget(button('발행자 정보', self.edit_issuer))
        footer.addWidget(button('견적안 미리보기 / PDF', self.preview))
        footer.addStretch()
        footer.addWidget(button('닫기', self.reject))
        footer.addWidget(button('입금확인 · 판매 등록', lambda: self.finish('confirm'), True))
        layout.addLayout(footer)
        scroll_dialog(self, layout)
        for item in draft['items']:
            self.add_item(item)
        if not draft['items']:
            self.add_item()
        self.recalculate()
        self.initial = self.raw_state()

    def build_request_workbench(self):
        """Forest-green customer rail and a wide price-entry workspace."""
        self.setObjectName("quoteWorkbench")
        self.setSizeGripEnabled(True)
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        self.setStyleSheet(quote_workbench_style(large))
        screen = self.parentWidget().screen() if self.parentWidget() else self.screen()
        screen = screen or QApplication.primaryScreen()
        if screen:
            available = screen.availableGeometry()
            width = min(1580, max(480, available.width() - 40))
            height = min(1000, int(available.height() * .95), available.height() - 48)
            self.setMinimumSize(min(960, width), min(600, height))
            self.resize(width, height)
        else:
            self.resize(1580, 980)
            self.setMinimumSize(960, 600)
        self.fields = {}
        self.cost = QCheckBox()
        self.note = QPlainTextEdit(self.draft.get("note", ""))
        self.note.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.note.setFixedHeight(76 if large else 64)
        self.note.setStyleSheet("QPlainTextEdit{padding:4px 6px;font-size:@size@px;}".replace(
            "@size@", "16" if large else "14"))
        self.note.document().setDocumentMargin(0)

        self.workbench_icons = []

        def workbench_icon(name, size=28, color="#193c32"):
            icon = QLabel()
            icon.setObjectName("workbenchIcon")
            icon.setProperty("iconName", name)
            icon.setFixedSize(size, size)
            icon.setPixmap(ui_icon(name, color).pixmap(QSize(size, size), self.devicePixelRatioF()))
            self.workbench_icons.append(icon)
            return icon

        def section_heading(title, icon_name, size=28):
            section = QWidget()
            row = QHBoxLayout(section)
            row.setContentsMargins(0, 0, 0, 0)
            row.setSpacing(10)
            row.addWidget(workbench_icon(icon_name, size))
            row.addWidget(label(title, "workbenchSectionTitle"))
            row.addStretch(1)
            return section

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        header = QFrame()
        header.setObjectName("workbenchHeader")
        header.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        heading = QHBoxLayout(header)
        heading.setContentsMargins(24, 14, 24, 14)
        heading.setSpacing(20)
        heading.addWidget(workbench_icon("leaf", 40, "#91d39d"))
        heading.addWidget(label("나무팜(주)", "workbenchBrand"))
        brand_rule = QFrame()
        brand_rule.setObjectName("workbenchBrandRule")
        brand_rule.setFixedSize(1, 30)
        heading.addWidget(brand_rule)
        heading.addWidget(label("견적 작업대", "workbenchTitle"))
        heading.addStretch(1)
        reference = label("견적번호  " + str(self.draft.get("requestReceiptNumber") or self.draft.get("sourceRequestId", "")),
                          "workbenchReference", True)
        reference.setMinimumWidth(0)
        reference.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        reference.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        reference.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        heading.addWidget(reference, 1)
        root.addWidget(header)

        body = QHBoxLayout()
        body.setContentsMargins(0, 0, 0, 0)
        body.setSpacing(0)
        self.customer_scroll = QScrollArea()
        self.customer_scroll.setObjectName("workbenchCustomerScroll")
        self.customer_scroll.setWidgetResizable(True)
        self.customer_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.customer_scroll.setMinimumWidth(320 if large else 300)
        self.customer_scroll.setMaximumWidth(480 if large else 440)
        rail = QWidget()
        rail.setObjectName("workbenchCustomerRail")
        customer = QVBoxLayout(rail)
        customer.setContentsMargins(16, 12, 16, 10)
        customer.setSpacing(10)
        formal = bool(self.draft.get("formalQuoteRequested"))
        self.workbench_customer_heading = QWidget()
        customer_title = QHBoxLayout(self.workbench_customer_heading)
        customer_title.setContentsMargins(0, 0, 0, 0)
        customer_title.setSpacing(8)
        customer_title.addWidget(workbench_icon("person", 36))
        customer_title.addWidget(label("고객 정보", "workbenchCustomerTitle"))
        customer.addWidget(self.workbench_customer_heading)
        heading_rule = QFrame()
        heading_rule.setObjectName("workbenchCustomerHeadingRule")
        heading_rule.setFixedHeight(2)
        customer.addWidget(heading_rule)
        self.workbench_formal_banner = QFrame()
        self.workbench_formal_banner.setObjectName("workbenchFormal")
        self.workbench_formal_banner.setProperty("requested", formal)
        formal_row = QHBoxLayout(self.workbench_formal_banner)
        formal_row.setContentsMargins(8, 8, 8, 8)
        formal_row.setSpacing(6)
        formal_row.addWidget(workbench_icon("document", 18, "#79521a" if formal else "#52645c"))
        formal_row.addWidget(label("판매자 직접 작성" if self.manual_quote else
                                  "공식 견적서 요청" if formal else "공식 견적서 미요청", "workbenchFormalText"))
        self.customer_values = {}
        self.customer_captions = {}
        identity = QGridLayout()
        identity.setHorizontalSpacing(8)
        identity.setVerticalSpacing(4)
        identity.setColumnStretch(1, 1)
        self.workbench_identity = identity

        def customer_row(row, title, value):
            display_title = {"견적서 수신인": "수신인", "수신 방법": "수신방법"}.get(title, title)
            caption = label(display_title, "workbenchCaption")
            identity.addWidget(caption, row, 0, Qt.AlignmentFlag.AlignVCenter)
            self.customer_captions[title] = caption
            field_key = {"고객명": "customerName", "연락처": "customerPhone", "이메일": "customerEmail", "지역": "requestRegion"}.get(title)
            if self.manual_quote and field_key:
                field = QLineEdit(str(value or ""))
                field.setObjectName("workbenchCustomerInput")
                field.setPlaceholderText({"연락처": "010-1234-5678", "이메일": "메일 받을 주소"}.get(title, title))
                field.setAccessibleName(title)
                field.setMaxLength({"customerName": 100, "customerPhone": 24, "customerEmail": 254, "requestRegion": 120}[field_key])
                field.setMinimumWidth(0)
                field.setMinimumHeight(44)
                field.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Fixed)
                field.setCursorPosition(0)
                self.fields[field_key] = field
                field.textChanged.connect(self.recalculate)
                identity.addWidget(field, row, 1)
                return
            value_style = ("workbenchCustomerName" if title == "고객명" else
                           "workbenchReceipt" if title == "견적번호" else "workbenchInfo")
            text = CustomerInfoValue(str(value or "미입력"), value_style)
            text.setMinimumWidth(0)
            text.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Preferred)
            text_policy = text.sizePolicy()
            text_policy.setHeightForWidth(True)
            text.setSizePolicy(text_policy)
            text.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            text.setAccessibleName(title)
            text.setToolTip(str(value or "미입력"))
            identity.addWidget(text, row, 1, Qt.AlignmentFlag.AlignVCenter)
            self.customer_values[title] = text

        for row, (title, value) in enumerate((("고객명", self.draft.get("customerName")),
                                             ("연락처", self.draft.get("customerPhone")),
                                             ("이메일", self.draft.get("customerEmail")),
                                             ("지역", self.draft.get("requestRegion")))):
            customer_row(row, title, value)
        customer_rule = QFrame()
        customer_rule.setObjectName("workbenchCustomerRule")
        customer_rule.setFixedHeight(1)
        identity.addWidget(customer_rule, 4, 0, 1, 2)
        formal_caption = label("공식견적서", "workbenchCaption")
        self.customer_captions["공식견적서"] = formal_caption
        identity.addWidget(formal_caption, 5, 0, Qt.AlignmentFlag.AlignVCenter)
        identity.addWidget(self.workbench_formal_banner, 5, 1, Qt.AlignmentFlag.AlignLeft)
        customer_row(6, "견적번호", self.draft.get("requestReceiptNumber") or self.draft.get("sourceRequestId"))
        channel = {"sms": "문자", "email": "메일", "both": "문자 / 메일"}.get(self.draft.get("quoteReplyChannel", "sms"), "문자")
        if formal:
            customer_row(7, "견적서 수신인", self.draft.get("quoteRecipientName") or self.draft.get("customerName"))
        customer_row(8 if formal else 7, "수신 방법", "문자 / 메일" if self.manual_quote else channel)
        customer.addLayout(identity)
        self.workbench_document_area = QFrame()
        self.workbench_document_area.setObjectName("workbenchDocumentTools")
        document_content = QVBoxLayout(self.workbench_document_area)
        document_content.setContentsMargins(10, 8, 10, 8)
        document_content.setSpacing(6)
        customer.addWidget(self.workbench_document_area)
        customer.addStretch(1)
        self.customer_scroll.setWidget(rail)
        body.addWidget(self.customer_scroll)

        self.workbench_scroll = QScrollArea()
        self.workbench_scroll.setWidgetResizable(True)
        self.workbench_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        right = QWidget()
        right.setObjectName("workbenchBody")
        content = QVBoxLayout(right)
        self.workbench_content_layout = content
        content.setContentsMargins(16, 14, 16, 8)
        content.setSpacing(6)
        self.workbench_items_panel = QFrame()
        self.workbench_items_panel.setObjectName("workbenchItemsPanel")
        self.workbench_items_panel.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        item_content = QVBoxLayout(self.workbench_items_panel)
        item_content.setContentsMargins(0, 0, 0, 0)
        item_content.setSpacing(8)
        content.addWidget(self.workbench_items_panel, 1)
        item_tools = QHBoxLayout()
        item_tools.addWidget(section_heading("견적 품목", "leaf", 28), 1)
        self.workbench_items_hint = label("묘목명은 직접 입력하거나 초성검색 · 규격·수량은 두 번 클릭해 수정", "deliveryCaption", True)
        self.add_item_button = button("+ 묘목 추가", self.add_item)
        self.remove_item_button = button("선택 묘목 삭제", self.remove_item)
        item_tools.addWidget(self.add_item_button)
        item_tools.addWidget(self.remove_item_button)
        item_content.addLayout(item_tools)
        item_content.addWidget(self.workbench_items_hint)
        # Logical indices stay stable for collection; visual order puts price before amount.
        self.items = table(["묘목명", "수량", "판매 금액", "판매 단가", "순번", "규격"])
        self.items.setEditTriggers(self.items.EditTrigger.DoubleClicked | self.items.EditTrigger.EditKeyPressed | self.items.EditTrigger.AnyKeyPressed)
        self.items.setObjectName("workbenchItems")
        self.items.setToolTip(self.workbench_items_hint.text())
        polish_table(self.items)
        self.items.horizontalHeader().setFixedHeight(40 if large else 36)
        self.items.verticalHeader().setMinimumSectionSize(48 if large else 40)
        self.items.verticalHeader().setDefaultSectionSize(48 if large else 40)
        # Specification is between seedling name and quantity, including on reopening.
        self.items.set_column_layout(((4, 0, 48 if large else 44), (0, 3, 172), (5, 2.5, 120),
                                      (1, 0, 108 if large else 96),
                                      (3, 0, 232 if large else 212), (2, .5, 96)))
        self.items.horizontalHeader().setMinimumSectionSize(32)
        self.items.setItemDelegateForColumn(5, QuoteRemarksDelegate(self.items))
        self.items.fit_columns()
        self.items.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Ignored)
        self.items.itemChanged.connect(self.recalculate)
        self.items.cellClicked.connect(self.edit_specification_cell)
        item_content.addWidget(self.items, 1)

        shipping = QFrame()
        self.workbench_shipping = shipping
        shipping.setObjectName("workbenchShipping")
        shipping_row = QHBoxLayout(shipping)
        shipping_row.setContentsMargins(16, 8, 16, 8)
        shipping_row.setSpacing(12)
        shipping_row.addWidget(workbench_icon("shipping", 28))
        shipping_row.addWidget(label("택배비", "deliverySectionTitle"))
        shipping_choices = QWidget()
        shipping_choices.setObjectName("shippingChoices")
        shipping_choices.setStyleSheet(
            "QWidget#shippingChoices QRadioButton{spacing:8px;margin:0;padding:4px 2px;}"
            "QWidget#shippingChoices QRadioButton::indicator{width:20px;height:20px;}")
        choices = QHBoxLayout(shipping_choices)
        choices.setContentsMargins(6, 0, 6, 0)
        choices.setSpacing(18)
        self.shipping_cod = QRadioButton("후불")
        self.shipping_prepaid = QRadioButton("선불")
        self.shipping_prepaid.setChecked(bool(self.draft.get("isPrepaid")))
        self.shipping_cod.setChecked(not self.shipping_prepaid.isChecked())
        for choice in (self.shipping_cod, self.shipping_prepaid):
            choice.setMinimumSize(80 if large else 72, 38)
            choice.setSizePolicy(QSizePolicy.Policy.Minimum, QSizePolicy.Policy.Fixed)
            choices.addWidget(choice)
        shipping_row.addWidget(shipping_choices)
        self.shipping_fee_editor = UnitPriceEditor(int(self.draft.get("shippingFee") or 6000),
                                                   compact=True, caption="선불 택배비")
        self.shipping_fee_editor.setFixedWidth(232 if large else 212)
        self.shipping_fee = self.shipping_fee_editor.input
        self.shipping_fee.setAccessibleName("선불 택배비 금액")
        shipping_row.addWidget(self.shipping_fee_editor)
        shipping_row.addWidget(label("원"))
        self.shipping_status = label("", "deliveryCaption", True)
        self.shipping_status.setMinimumWidth(0)
        self.shipping_status.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        shipping_row.addWidget(self.shipping_status, 1)
        content.addWidget(shipping)
        self.workbench_total_rule = QFrame()
        self.workbench_total_rule.setObjectName("workbenchTotalRule")
        self.workbench_total_rule.setFixedHeight(3)
        content.addWidget(self.workbench_total_rule)
        self.workbench_summary = QFrame()
        self.workbench_summary.setObjectName("workbenchSummary")
        self.workbench_summary.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        summary = QHBoxLayout(self.workbench_summary)
        summary.setContentsMargins(16, 7, 16, 7)
        summary.setSpacing(12)
        summary.addWidget(workbench_icon("calculator", 40))
        summary.addWidget(label("합계", "workbenchSumTitle"))
        self.amount_detail = label("", "workbenchAmountDetail", True)
        summary.addWidget(self.amount_detail, 1)
        self.total = label("", "workbenchTotal")
        self.total.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        summary.addWidget(self.total)
        content.addWidget(self.workbench_summary)
        self.workbench_delivery_rule = QFrame()
        self.workbench_delivery_rule.setObjectName("workbenchTotalRule")
        self.workbench_delivery_rule.setFixedHeight(3)
        content.addWidget(self.workbench_delivery_rule)
        self.workbench_footer = QFrame()
        self.workbench_footer.setObjectName("workbenchFooter")
        footer = QHBoxLayout(self.workbench_footer)
        footer.setContentsMargins(16, 8, 16, 8)
        footer.setSpacing(12)
        self.build_delivery_tools(content, compact=True, document_content=document_content)
        self.delivery_heading.hide()

        self.workbench_original_area = QFrame()
        self.workbench_original_area.setObjectName("workbenchOriginalRequest")
        self.workbench_original_area.setStyleSheet(
            "QFrame#workbenchOriginalRequest{background:#f7faf8;border:1px solid #b8c9bf;border-radius:6px;}")
        original = QVBoxLayout(self.workbench_original_area)
        original.setContentsMargins(10, 6, 10, 6)
        original.setSpacing(4)
        original.addWidget(label("홈페이지 고객 요청사항", "deliverySectionTitle"))
        self.request_note_view = QPlainTextEdit(self.draft.get("requestNote", ""))
        self.request_note_view.setObjectName("workbenchOriginalText")
        self.request_note_view.setReadOnly(True)
        self.request_note_view.document().setDocumentMargin(0)
        self.request_note_view.setAccessibleName("홈페이지 고객 요청사항 원문")
        self.request_note_view.setPlaceholderText("고객이 남긴 요청사항이 없습니다.")
        self.request_note_view.setStyleSheet("border:0;background:transparent;padding:2px;")
        self.request_note_view.setFixedHeight(64 if large else 52)
        original.addWidget(self.request_note_view, 1)
        # Keep the original customer request visible between totals and delivery actions.
        content.insertWidget(content.indexOf(self.workbench_delivery_rule), self.workbench_original_area)
        self.workbench_original_area.setVisible(self.simple_request)
        self.workbench_scroll.setWidget(right)
        body.addWidget(self.workbench_scroll, 1)
        root.addLayout(body, 1)

        self.workbench_note_area = QWidget()
        notes = QVBoxLayout(self.workbench_note_area)
        notes.setContentsMargins(0, 4, 0, 0)
        notes.setSpacing(4)
        notes.addWidget(label("요청사항", "deliverySectionTitle"))
        self.note.setPlaceholderText("공식견적서 중요사항에 넣을 안내를 입력하세요")
        self.note.setAccessibleName("공식견적서 중요사항 · 판매자 입력")
        self.note.textChanged.connect(self.recalculate)
        self.note.textChanged.connect(lambda: self._workbench_notes_timer.start(0))
        notes.addWidget(self.note)
        document_content.addWidget(self.workbench_note_area)
        self.note.installEventFilter(self)
        self.workbench_original_area.installEventFilter(self)

        self.working_save_status = label("", "deliveryCaption", True)
        footer.addWidget(self.working_save_status, 1)
        self.save_working_button = button("임시저장", self.save_working_draft)
        self.save_working_button.setToolTip("이 PC에 임시저장하고 계속 수정합니다. 문자·메일 발송 후 견적완료까지 진행할 수 있습니다.")
        self.save_working_button.setVisible(not self.items_only and not getattr(self, "confirmed_result", False))
        self.save_working_button.setEnabled(self.manager is not None)
        set_button_icon(self.save_working_button, "save", "#193c32")
        footer.addWidget(self.save_working_button)
        self.close_button = button("닫기", self.reject)
        self.close_button.setMinimumWidth(120)
        footer.addWidget(self.close_button)
        self.confirm_button = button("견적완료",
                                     lambda: self.finish("save_manual" if self.manual_quote else "finalize"), True)
        self.confirm_button.setFixedWidth(198)
        self.confirm_button.setProperty("deliveryCompletion", True)
        self.confirm_button.setEnabled(self.request_status in ("", "신규"))
        set_button_icon(self.confirm_button, "check", "#ffffff")
        self.delivery_send_layout.addWidget(self.confirm_button)
        root.addWidget(self.workbench_footer)
        for item in self.draft["items"]:
            self.add_item(item)
        if self.manual_quote and not self.draft["items"]:
            self.add_item()
        row_height = self.items.verticalHeader().defaultSectionSize()
        table_header = self.items.horizontalHeader().height()
        self.items.setMinimumHeight(table_header + 5 * row_height + 4)
        self.shipping_cod.toggled.connect(self.recalculate)
        self.shipping_prepaid.toggled.connect(self.recalculate)
        self.shipping_fee.textChanged.connect(self.recalculate)
        self._workbench_ready = True
        self.finished.connect(self.finish_workbench)
        self.recalculate()
        self.initial = self.raw_state()

    def save_working_draft(self):
        if not self.manager or self.working_save_task is not None:
            return
        try:
            self._working_snapshot = validate_working_draft(self.collect(False, working=True))
        except UserError as error:
            QMessageBox.warning(self, "임시저장", str(error))
            return
        self.setEnabled(False)
        self.working_save_status.setText("이 PC에 임시저장 중…")
        task = Task(lambda: self.manager.save_working_draft(self._working_snapshot), None, self)
        self.working_save_task = task
        task.completed.connect(self.working_draft_saved)
        task.start()

    @Slot(object, str, bool)
    def working_draft_saved(self, value, error, conflict):
        task = self.working_save_task
        if task is not None:
            task.wait()
            self.working_save_task = None
            task.deleteLater()
        self.setEnabled(True)
        if error:
            self.working_save_status.setText("저장 실패 · " + error)
            return
        self.draft = copy.deepcopy(self._working_snapshot)
        self.draft["workingCopy"] = True
        self.initial = self.raw_state()
        self.working_save_status.setText("임시저장 완료 · 수정·발송 후 견적완료까지 진행할 수 있습니다.")

    def persist_shipping(self):
        """Prepare current inputs for PDF/messages without confirming the request."""
        try:
            self.draft = self.collect()
            return True
        except UserError as error:
            QMessageBox.warning(self, "견적 입력 확인", str(error))
            return False

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if not getattr(self, "_workbench_ready", False) or self._workbench_closed:
            return
        fit_quote_density(self)
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        # Leave room for the table plus both scrollbars in short, narrow windows.
        self.customer_scroll.setMaximumWidth(min(480 if large else 440,
                                                  max(320 if large else 300, self.width() - 900)))
        self._workbench_fit_timer.start(0)
        compact = self.height() < 900
        if compact == getattr(self, "_workbench_compact", None):
            return
        self._workbench_compact = compact
        self.workbench_items_hint.setVisible(not compact)
        self.workbench_footer.layout().setContentsMargins(16, 8, 16, 8)
        customer = self.customer_scroll.widget().layout()
        customer.setContentsMargins(16, 8 if compact else 12, 16, 8 if compact else 10)
        customer.setSpacing(8 if compact else 10)
        self.workbench_identity.setVerticalSpacing(4)

    def fit_workbench_rows(self):
        if not getattr(self, "_workbench_ready", False) or self._workbench_closed or not self.isVisible():
            return
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        # Bound long e-mail/receipt values to the rail and wrap without altering copied text.
        # A shared caption width keeps both groups on the same value-column axis.
        caption_width = max(caption.sizeHint().width() for caption in self.customer_captions.values())
        for caption in self.customer_captions.values():
            caption.setFixedWidth(caption_width)
        margins = self.customer_scroll.widget().layout().contentsMargins()
        document_width = self.customer_scroll.viewport().width() - margins.left() - margins.right() - 26
        self.document_buttons_layout.setSpacing(8)
        self.document_buttons_layout.setDirection(QBoxLayout.Direction.TopToBottom if document_width < (280 if large else 250)
                                                 else QBoxLayout.Direction.LeftToRight)
        value_width = max(60, self.customer_scroll.viewport().width() - margins.left() - margins.right()
                          - caption_width - self.workbench_identity.horizontalSpacing())
        for value in self.customer_values.values():
            value.setMaximumWidth(value_width)
            value.setFixedHeight(value.heightForWidth(value_width))
        narrow = self.workbench_scroll.viewport().width() < 700
        for action in (self.sms_button, self.email_button):
            action.refresh_symbol()
            size = (20 if large else 18) if narrow else (22 if large else 20)
            action.title_label.setStyleSheet(f"font-size:{size}px;font-weight:700;")
            action.status_badge.setStyleSheet("font-size:12px;padding:2px 4px;" if narrow else "")
        available = max(1, self.workbench_scroll.viewport().width() - 32)
        # Two thirds of the former equal-width channel buttons; completion is 1.5x.
        channel_width = max(160, (available - 132 - 24) // 3)
        if available < 700:
            channel_width = max(120, (available - 198 - 24) // 2)
        for action in (self.sms_button, self.email_button):
            action.setFixedWidth(channel_width)
        dense = bool(self.property("compactLayout"))
        row_height = (42 if large else 36) if dense else (48 if large else 40)
        self.items.verticalHeader().setMinimumSectionSize(row_height)
        self.items.verticalHeader().setDefaultSectionSize(row_height)
        self.items.horizontalHeader().setFixedHeight((36 if large else 32) if dense else (40 if large else 36))
        for row in range(self.items.rowCount()):
            self.items.setRowHeight(row, row_height)
        header = self.items.horizontalHeader().height()
        # Reserve a separate 5–8-row workspace even when the quote has only one item.
        # Totals and delivery actions stay outside that workspace.
        layout = self.workbench_content_layout
        margins = layout.contentsMargins()
        other_height, visible_parts = margins.top() + margins.bottom(), 0
        for index in range(layout.count()):
            part = layout.itemAt(index)
            widget = part.widget()
            if widget is self.workbench_items_panel or (widget is not None and widget.isHidden()):
                continue
            other_height += max(0, part.sizeHint().height())
            visible_parts += 1
        other_height += visible_parts * layout.spacing()
        item_layout = self.workbench_items_panel.layout()
        item_parts = 0
        for index in range(item_layout.count()):
            part = item_layout.itemAt(index)
            widget = part.widget()
            if widget is self.items or (widget is not None and widget.isHidden()):
                continue
            other_height += max(0, part.sizeHint().height())
            item_parts += 1
        other_height += item_parts * item_layout.spacing()
        scrollbar = self.items.horizontalScrollBar()
        extra = scrollbar.sizeHint().height() if scrollbar.maximum() > 0 else 0
        room = self.workbench_scroll.viewport().height() - other_height - header - extra - 4
        rows = min(8, max(5, room // row_height))
        height = header + rows * row_height + extra + 4
        self.items.setMinimumHeight(height)
        self.items.setMaximumHeight(16777215)
        self._workbench_notes_timer.start(0)

    def showEvent(self, event):
        super().showEvent(event)
        if getattr(self, "_workbench_ready", False) and not self._workbench_closed:
            fit_quote_density(self)
            if not hasattr(self, "_workbench_compact"):
                self._workbench_compact = self.height() < 900
                self.workbench_items_hint.setVisible(not self._workbench_compact)
            self._workbench_fit_timer.start(0)

    @Slot(int)
    def finish_workbench(self, result):
        self._workbench_closed = True
        self._workbench_ready = False
        self._workbench_fit_timer.stop()
        self._workbench_notes_timer.stop()
        self.note.removeEventFilter(self)
        self.workbench_original_area.removeEventFilter(self)
        self.cleanup_generated_quote()

    def eventFilter(self, watched, event):
        if (getattr(self, "_workbench_ready", False) and not self._workbench_closed and
                event.type() == QEvent.Type.Resize and watched in (
                getattr(self, "note", None), getattr(self, "workbench_original_area", None))):
            self._workbench_notes_timer.start(0)
        return super().eventFilter(watched, event)

    def align_workbench_notes(self):
        if not getattr(self, "_workbench_ready", False) or self._workbench_closed or not self.isVisible():
            return
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        measure = QTextDocument()
        measure.setDefaultFont(_quote_font(16 if large else 14))
        measure.setDocumentMargin(0)
        measure.setPlainText(self.note.toPlainText())
        measure.setTextWidth(max(1, self.note.viewport().width() - 12))
        note_height = max(76 if large else 64,
                          min(132 if large else 112, int(measure.size().height()) + 12))
        if self.note.height() != note_height:
            self.note.setFixedHeight(note_height)
        height = 64 if large else 52
        if self.request_note_view.height() != height:
            self.request_note_view.setFixedHeight(height)

    def edit_specification_cell(self, row, column):
        if column == 5:
            cell = self.items.item(row, column)
            if cell is not None and cell.flags() & Qt.ItemFlag.ItemIsEditable:
                self.items.editItem(cell)

    def add_item(self, item=None):
        is_new = not isinstance(item, dict)
        if is_new and self.items.rowCount() >= (50 if self.workbench else 100):
            QMessageBox.information(self, "묘목 추가", "견적 품목은 최대 50개까지 추가할 수 있습니다." if self.workbench else "견적 품목은 최대 100개까지 추가할 수 있습니다.")
            return
        item = copy.deepcopy(item) if isinstance(item, dict) else {"species": "", "specification": "", "quantity": 1, "unitPrice": 0, "unitCost": 0}
        if self.workbench and (is_new or not self.items_only) and not getattr(self, "confirmed_result", False) and not item.get("unitPrice"):
            item["unitPrice"] = 2000
        self.items.blockSignals(True)
        index = self.items.rowCount()
        self.items.insertRow(index)
        if self.workbench:
            number = QTableWidgetItem(str(index + 1))
            number.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            number.setFlags(number.flags() & ~Qt.ItemFlag.ItemIsEditable)
            number.setData(Qt.ItemDataRole.UserRole, copy.deepcopy(item))
            self.items.setItem(index, 4, number)
            values = (item["species"], item["quantity"], f"{item['quantity'] * item['unitPrice']:,}")
            for column, value in enumerate(values):
                cell = QTableWidgetItem(str(value))
                cell.setTextAlignment((Qt.AlignmentFlag.AlignLeft if column == 0 else Qt.AlignmentFlag.AlignRight) | Qt.AlignmentFlag.AlignVCenter)
                if column == 2:
                    cell.setFlags(cell.flags() & ~Qt.ItemFlag.ItemIsEditable)
                if column == 0:
                    cell.setToolTip(str(value))
                    font = cell.font()
                    font.setBold(False)
                    cell.setFont(font)
                elif column == 1:
                    cell.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                self.items.setItem(index, column, cell)
                if column == 0:
                    species_editor = SpeciesNameEditor(str(value), self.items)
                    species_editor.changed.connect(cell.setText)
                    species_editor.activated.connect(lambda target=cell: self.items.setCurrentItem(target))
                    species_editor.pick_requested.connect(lambda target=cell: self.choose_quote_species(target))
                    self.items.setCellWidget(index, 0, species_editor)
            price_editor = UnitPriceEditor(item["unitPrice"], self.items, compact=True)
            price_editor.changed.connect(self.recalculate)
            self.items.setCellWidget(index, 3, price_editor)
            specification = QTableWidgetItem(item_specification(item))
            specification.setToolTip("홈페이지 희망 규격입니다. 클릭해서 입력하거나 수정할 수 있습니다.")
            self.items.setItem(index, 5, specification)
            self.items.setRowHeight(index, self.items.verticalHeader().defaultSectionSize())
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
            self.items.setItem(index, 6, QTableWidgetItem(item.get("remarks", "")))
        self.items.blockSignals(False)
        self.recalculate()
        if is_new:
            self.items.setCurrentCell(index, 0)
            if self.workbench:
                self.items.cellWidget(index, 0).input.setFocus()
            else:
                self.items.editItem(self.items.item(index, 0))

    def choose_quote_species(self, cell):
        self.items.setCurrentItem(cell)
        names = {self.items.item(row, 0).text().strip() for row in range(self.items.rowCount())}
        notice = "PC에 동기화된 묘목 목록입니다. 묘목명을 직접 입력할 수도 있습니다."
        if self.manager is not None and hasattr(self.manager, "vault"):
            try:
                rows = NurseryStore(self.manager).read().get("species", [])
                names.update(row.get("speciesName", "") for row in rows
                             if not row.get("_deleted") and row.get("isActive", 1) == 1)
            except UserError as error:
                notice = str(error)
        picker = QuoteSpeciesPicker(names, cell.text(), self, notice=notice)
        if picker.exec() == QDialog.DialogCode.Accepted and self.items.row(cell) >= 0:
            cell.setText(picker.selected_name)

    def unit_price_editor(self, row):
        editor = self.items.cellWidget(row, 3) if self.workbench else None
        require(isinstance(editor, UnitPriceEditor), "판매 단가 입력칸을 확인해주세요.")
        return editor

    def set_unit_price(self, row, value):
        """Set one request item's unit price; future Excel lookup can call this."""
        self.unit_price_editor(row).set_value(value)

    def remove_item(self):
        index = self.items.currentRow()
        if index >= 0:
            self.items.removeRow(index)
            if self.workbench:
                self.items.blockSignals(True)
                for row in range(self.items.rowCount()):
                    self.items.item(row, 4).setText(str(row + 1))
                self.items.blockSignals(False)
            self.recalculate()

    def show_request_note(self):
        dialog = QDialog(self)
        dialog.setWindowTitle("고객이 접수한 요청사항 원문")
        dialog.resize(560, 300)
        layout = QVBoxLayout(dialog)
        text = QPlainTextEdit(self.draft.get("requestNote", ""))
        text.setReadOnly(True)
        layout.addWidget(text)
        layout.addWidget(button("닫기", dialog.accept))
        dialog.exec()

    def raw_state(self):
        if self.workbench:
            item_state = [[self.items.item(r, 0).text(), self.items.item(r, 1).text(), self.unit_price_editor(r).text(), self.items.item(r, 5).text()]
                          for r in range(self.items.rowCount())]
        else:
            item_state = [[self.items.item(r, c).text() for c in (0, 1, 2, 3, 4, 6)] for r in range(self.items.rowCount())]
        fields = [field.text() for field in self.fields.values()]
        if self.workbench:
            fields += [self.shipping_fee.text(), self.shipping_prepaid.isChecked()]
        return (fields, self.note.toPlainText(), item_state,
                copy.deepcopy(self.draft["issuer"]))

    def reject(self):
        if self.working_save_task is not None:
            return
        if hasattr(self, "initial") and self.raw_state() != self.initial:
            message = ("견적을 저장하지 않고 작업대를 닫을까요?\n이미 발송한 문자·메일은 취소되지 않습니다."
                       if self.workbench else "저장하지 않은 입력을 버리고 닫을까요?")
            if QMessageBox.question(self, "작업대 닫기", message, QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
        super().reject()

    def collect(self, validate=True, *, working=False):
        draft = copy.deepcopy(self.draft)
        if self.workbench:
            if self.manual_quote:
                for key, field in self.fields.items():
                    draft[key] = field.text().strip()
                draft["customerPhone"] = draft["customerPhone"].replace("-", "").replace(" ", "")
                draft["receiverName"] = draft["customerName"]
            fee = self.shipping_fee.text().strip() or ("0" if working else "")
            prepaid = self.shipping_prepaid.isChecked()
            require(not prepaid or (fee.isascii() and fee.isdigit()), "선불 택배비를 0 이상의 정수로 입력해주세요.")
            draft.update(shippingFee=int(fee) if prepaid else 0, isPrepaid=prepaid,
                         taxAmount=draft.get("taxAmount", 0) if self.items_only else 0,
                         costConfirmed=draft.get("costConfirmed", False) if self.items_only else False,
                         items=[], note=self.note.toPlainText().strip())
            for row in range(self.items.rowCount()):
                species = self.items.item(row, 0).text().strip()
                quantity = self.items.item(row, 1).text().strip() or ("0" if working else "")
                price = self.unit_price_editor(row).text() or ("0" if working else "")
                require(quantity.isascii() and quantity.isdigit() and price.isascii() and price.isdigit(),
                        f"{row+1}번째 품목 수량·판매 단가를 정수로 입력해주세요.")
                original = self.items.item(row, 4).data(Qt.ItemDataRole.UserRole) or {}
                current = {**original, "species": species, "specification": self.items.item(row, 5).text().strip(),
                           "quantity": int(quantity), "unitPrice": int(price), "unitCost": original.get("unitCost", 0),
                           "remarks": ""}
                if original.get("remarks"):
                    current["legacyRemarks"] = original["remarks"]
                draft["items"].append(current)
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
            draft["items"].append(dict(species=values[0], specification=values[1], quantity=int(values[2]), unitPrice=int(values[3]), unitCost=int(values[4]), remarks=self.items.item(row, 6).text().strip()))
        return validate_draft(draft) if validate else draft

    def recalculate(self, *args):
        if not hasattr(self, "items") or not hasattr(self, "total"):
            return
        if (self.workbench and hasattr(self, "working_save_status") and hasattr(self, "initial") and
                self.working_save_task is None and self.raw_state() != self.initial):
            self.working_save_status.setText("수정 중 · 임시저장을 눌러 보관하세요.")
        if self.workbench and hasattr(self, "add_item_button"):
            self.add_item_button.setEnabled(self.items.rowCount() < 50)
        if self.workbench and self.generated_quote_path:
            self.cleanup_generated_quote()
            self.preview_quote_button.setEnabled(False)
            self.generate_quote_button.setText("공식견적서 생성")
            self.quote_file_label.setText("공식 견적서 미생성")
        if self.workbench:
            prepaid = self.shipping_prepaid.isChecked()
            self.shipping_fee_editor.setEnabled(prepaid)
            self.shipping_fee.setEnabled(prepaid)
            self.shipping_status.setText("선불 · 합계 포함" if prepaid else "후불 · 별도")
        elif hasattr(self, "shipping_fee_editor"):
            self.shipping_fee_editor.setEnabled(self.prepaid.isChecked())
        invalid = False
        try:
            draft = self.collect(False)
            self.items.blockSignals(True)
            if self.workbench:
                for row, item in enumerate(draft["items"]):
                    species_editor = self.items.cellWidget(row, 0)
                    raw_species = self.items.item(row, 0).text()
                    if isinstance(species_editor, SpeciesNameEditor) and species_editor.input.text() != raw_species:
                        species_editor.input.blockSignals(True)
                        species_editor.input.setText(raw_species)
                        species_editor.input.blockSignals(False)
                    self.items.item(row, 2).setText(f"{item['quantity'] * item['unitPrice']:,}")
                    self.items.item(row, 0).setToolTip(item['species'])
                    self.items.item(row, 5).setToolTip(
                        (item.get('specification', '') + "\n" if item.get('specification') else "") +
                        "클릭해서 규격을 입력하거나 수정할 수 있습니다.")
            else:
                for row, item in enumerate(draft["items"]):
                    self.items.item(row, 5).setText(f"{item['quantity'] * item['unitPrice']:,}")
            self.items.blockSignals(False)
            amount = draft_amounts(draft)
            self.total.setText(f"{amount['total']:,}원" if self.workbench else f"합계 {amount['total']:,}원")
            if self.workbench:
                self.amount_detail.setText(
                    f"총 수량 {amount['quantity']:,}주 · 묘목 금액 {sum(i['quantity'] * i['unitPrice'] for i in draft['items']):,}원"
                )
            else:
                self.amount_detail.setText(
                    f"{amount['quantity']:,}주   ·   공급가 {amount['supply']:,}원   ·   세액 {amount['tax']:,}원\n"
                    f"매입 원가 {amount['cost']:,}원"
                )
        except UserError:
            invalid = True
            self.total.setText("판매 단가를 입력해주세요")
            self.amount_detail.setText("판매 금액 = 수량 × 판매 단가" if self.workbench else "판매 금액은 수량 × 판매 단가로 계산합니다.")
        if self.workbench and self.total.property("invalid") != invalid:
            self.total.setProperty("invalid", invalid)
            self.total.style().unpolish(self.total)
            self.total.style().polish(self.total)
        if self.workbench and hasattr(self, "sms_status_badge"):
            if self.manual_quote:
                self.sms_button.setEnabled(bool(self.fields["customerPhone"].text().strip()))
                self.email_button.setEnabled(bool(self.fields["customerEmail"].text().strip()))
            self.update_delivery_badges()
            if self.items_only:
                for control in (self.sms_button, self.email_button):
                    control.setEnabled(False)
        if self.workbench and getattr(self, "_workbench_ready", False) and not self._workbench_closed:
            self._workbench_fit_timer.start(0)

    def edit_issuer(self):
        if self.workbench:
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
        if self.workbench:
            return
        try:
            draft = self.collect()
            print_document(self, quote_html(draft), "나무팜_견적안_" + draft["id"][:8])
        except UserError as error:
            QMessageBox.warning(self, "견적 입력 확인", str(error))

    def finish(self, mode):
        try:
            require(mode in ("finalize", "confirm", "save_manual"), "견적 저장·확정 또는 판매 등록을 선택해주세요.")
            require(mode != "save_manual" or self.manual_quote, "직접 작성한 견적만 수기 견적으로 저장할 수 있습니다.")
            draft = self.collect()
            if mode == "save_manual":
                fingerprint = quote_delivery_fingerprint(draft)
                state = self.delivery_state
                delivered = ((state.get("smsState") == "success" and state.get("smsQuoteFingerprint") == fingerprint)
                             or (bool(state.get("emailSentAt")) and state.get("emailQuoteFingerprint") == fingerprint))
                draft["manualStatus"] = "견적발송완료" if delivered else "견적확정"
            if self.workbench and mode in ("finalize", "save_manual"):
                fingerprint = quote_delivery_fingerprint(draft)
                state = self.delivery_state
                delivered = ((state.get("smsState") == "success" and state.get("smsQuoteFingerprint") == fingerprint)
                             or (bool(state.get("emailSentAt")) and state.get("emailQuoteFingerprint") == fingerprint))
                if not delivered:
                    QMessageBox.warning(self, "고객 전달 확인", "문자 또는 메일을 먼저 보내 주세요.\n현재 견적의 전송이 완료되어야 견적완료할 수 있습니다.\n문자 결과확인 중이면 최종 전송 결과를 확인해 주세요.")
                    return
            if mode == "finalize" and QMessageBox.question(self, "견적 확정", "판매 단가와 합계를 확인했나요?\n확정하면 온라인 요청 상태가 '견적확정'으로 바뀌고 확정 견적으로 이동합니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
            if mode == "confirm" and QMessageBox.question(self, "입금확인", "고객의 구매와 입금을 확인했나요?\n품목·수량·금액은 판매 등록 후 변경할 수 없습니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
            self.draft, self.mode = draft, mode
            self.accept()
        except UserError as error:
            QMessageBox.warning(self, "견적 입력 확인", str(error))


class DeliveryStatusLog(QLineEdit):
    """Bordered single-line current status, with the full text available for copying."""

    def __init__(self, initial, parent=None):
        super().__init__(parent)
        self.setObjectName("deliveryStatusLog")
        self.setAccessibleName("현재 전송 상태")
        self.setReadOnly(True)
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        self.setFixedHeight(42 if large else 38)
        self.setStyleSheet("""
QLineEdit#deliveryStatusLog{background:#f5f8f6;color:#314e41;border:2px solid #a6bbaf;border-radius:8px;min-height:0;padding:4px 8px;font-size:@font@px;}
QLineEdit#deliveryStatusLog[state="pending"]{border-color:#c0a463;}
QLineEdit#deliveryStatusLog[state="ready"],QLineEdit#deliveryStatusLog[state="success"]{border-color:#4f916b;}
QLineEdit#deliveryStatusLog[state="failed"]{border-color:#b96155;}
""".replace("@font@", "17" if large else "15"))
        self._last_text = ""
        self.setText(initial)

    def text(self):
        return self._last_text

    def setText(self, text):
        text = str(text).strip()
        if not text or text == self._last_text:
            return
        self._last_text = text
        if text.startswith(("문자 전송 성공", "메일 전송 성공")):
            state = "success"
        elif text.startswith(("문자 전송 준비 실패", "메일 전송 준비 실패", "문자 전송 실패", "메일 전송 실패")):
            state = "failed"
        elif text.startswith(("문자 전송 가능", "메일 전송 가능")):
            state = "ready"
        else:
            state = "pending"
        self.setProperty("state", state)
        self.style().unpolish(self)
        self.style().polish(self)
        stamp = datetime.now(SEOUL).strftime("%H:%M:%S")
        display = f"[{stamp}] " + " ".join(text.splitlines())
        super().setText(display)
        self.setCursorPosition(0)
        self.setToolTip(display)


class QuoteSmsDialog(QDialog):
    """견적 문자 작성, 실제 발송, 최종 성공/실패 확인 팝업."""

    def __init__(self, draft, manager=None, parent=None, *, payment=False):
        super().__init__(parent)
        self.draft = copy.deepcopy(draft)
        self.manager = manager
        self.payment_notice = payment
        self.success_confirmed = False
        self._sender_ready = False
        try:
            self.delivery_state = (manager.payment_delivery_state(self.draft["id"]) if payment else
                                   manager.quote_delivery_state(self.draft["id"])) if manager else {}
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
        self.setWindowTitle("입금확인 문자 보내기" if payment else "견적 문자 보내기")
        self.setSizeGripEnabled(True)

        screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
        available = screen.availableGeometry() if screen else None
        if available:
            large = bool(QApplication.instance().property("namufarmLargeFont"))
            width = min(820 if large else 760, available.width() - 40)
            height = min(1160, max(720, int(available.height() * 0.96)))
            width = min(width, max(620, available.width() - 40))
            height = min(height, max(620, available.height() - 50))
            self.resize(width, height)
        else:
            self.resize(760, 960)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        layout.setSpacing(12)
        layout.addWidget(label("입금확인 문자 보내기" if payment else "견적 문자 보내기", "deliveryTitle"))
        if payment:
            layout.addWidget(label("문자 전송 성공 후 입금확인을 누르면 판매관리에 등록됩니다.", "deliverySubtitle", True))

        address_card = QFrame()
        address_card.setObjectName("emailAddressCard")
        address = QGridLayout(address_card)
        address.setContentsMargins(10, 6, 10, 6)
        address.setHorizontalSpacing(12)
        address.setVerticalSpacing(8)

        self.recipient_name = label(self.draft.get("customerName") or "고객", wrap=True)
        self.recipient_name.setMaximumWidth(160)
        self.recipient_phone = QLineEdit(self.draft.get("customerPhone", ""))
        phone_width = 190 if QApplication.instance().property("namufarmLargeFont") else 170
        self.recipient_name.setMaximumWidth(120)
        self.recipient_phone.setFixedWidth(phone_width)
        self.recipient_phone.setMaxLength(20)
        self.recipient_phone.setAccessibleName("문자 수신 전화번호 · 수정 가능")
        self.recipient_phone.setPlaceholderText("수신 전화번호")
        self.recipient_phone.setToolTip("이번 문자 수신번호입니다. 원래 고객 연락처는 바뀌지 않습니다.")
        self.recipient_phone.textChanged.connect(self.recipient_changed)
        self.sender_phone = QLineEdit("발신 설정 확인 중…")
        self.sender_phone.setReadOnly(True)
        self.sender_phone.setFixedWidth(phone_width)

        address.addWidget(self.recipient_name, 0, 0)
        address.addWidget(label("수신번호"), 0, 1)
        address.addWidget(self.recipient_phone, 0, 2)
        address.addWidget(label("발신"), 0, 3)
        address.addWidget(self.sender_phone, 0, 4)
        address.setColumnStretch(5, 1)
        layout.addWidget(address_card)

        layout.addWidget(label("문자 내용", "emailSectionTitle"))
        self.message = QPlainTextEdit(self.default_message())
        self.message.setMinimumHeight(260)
        layout.addWidget(self.message, 1)

        self.status = DeliveryStatusLog("문자 전송 준비 중", self)
        layout.addWidget(self.status)

        actions = QHBoxLayout()
        self.cancel_button = button("닫기", self.reject)
        self.check_button = QPushButton("결과 확인")
        self.check_button.setEnabled(not payment and bool(self.delivery_state.get("smsMessageId")))
        self.check_button.clicked.connect(self.check_status)
        self.send_button = QPushButton("문자 전송")
        self.send_button.setObjectName("smsSendPrimary")
        self.send_button.setEnabled(False)
        self.send_button.clicked.connect(self.send_sms)
        actions.addStretch()
        actions.addWidget(self.check_button)
        actions.addWidget(self.cancel_button)
        actions.addWidget(self.send_button)
        if payment:
            self.payment_confirm_button = button("입금확인", self.confirm_payment, True)
            self.payment_confirm_button.setEnabled(False)
            actions.addWidget(self.payment_confirm_button)
        layout.addLayout(actions)
        setup_delivery_reading(self, layout)

        QTimer.singleShot(0, self.load_profile)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        fit_delivery_message(self)

    def quote_number(self):
        return self.draft.get("requestReceiptNumber") or ("PCQ_" + self.draft["id"][:8])

    def default_message(self):
        if self.payment_notice:
            return payment_confirmation_message(self.draft)
        customer = self.draft.get("customerName") or "고객"
        lines = [
            f"{customer}님, 안녕하세요.",
            "",
            "나무팜농원입니다.",
            "요청하신 묘목 견적을 안내드립니다.",
            "",
            f"견적번호 : {self.quote_number()}",
            "",
        ]
        for index, item in enumerate(self.draft.get("items", []), 1):
            quantity = int(item["quantity"])
            amount = quantity * int(item["unitPrice"])
            lines.extend([
                f"품목{index} : {item['species']} {quantity:,}주",
                f"판매금액 : {amount:,}원",
            ])
            if item_specification(item):
                lines.append("규격 : " + item_specification(item))
            lines.append("")
        if self.draft.get("note", "").strip():
            lines.extend(["요청사항 : " + self.draft["note"].strip(), ""])
        total = draft_amounts(self.draft)["total"]
        shipping_fee = int(self.draft.get("shippingFee", 0) or 0)
        if self.draft.get("isPrepaid"):
            shipping_line = f"택배비 : 선불 {shipping_fee:,}원"
            total_line = f"총금액 : {total:,}원 (택배비 포함)"
        else:
            shipping_line = f"택배비 : 후불 / 선불 시 {shipping_fee:,}원"
            total_line = f"총금액 : {total:,}원"
        lines.extend([
            shipping_line,
            total_line,
            "",
            payment_notice(self.draft),
            "",
            "감사합니다.",
        ])
        return "\n".join(lines).strip()

    def recipient_changed(self, _text=""):
        if not hasattr(self, "send_button"):
            return
        try:
            normalize_sms_recipient(self.recipient_phone.text())
            valid = True
        except UserError:
            valid = False
        idle = not (self.profile_task or self.send_task or self.status_task)
        self.send_button.setEnabled(bool(self.manager and self._sender_ready and valid and idle))

    def load_profile(self):
        self.status.setText("문자 전송 준비 중")
        if not self.manager:
            self.sender_phone.setText("PC 연결 필요")
            self.status.setText("문자 전송 준비 실패 · PC를 서버에 연결해주세요.")
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
        if self.payment_notice:
            self.check_button.setEnabled(bool(self.delivery_state.get("smsMessageId")))
        if error:
            self.sender_phone.setText("확인 실패")
            self.status.setText("문자 전송 준비 실패 · " + error)
            self.send_button.setEnabled(False)
            return
        if not isinstance(value, dict) or value.get("configured") is not True:
            self.sender_phone.setText("설정 필요")
            self.status.setText("문자 전송 준비 실패 · 발신 설정이 필요합니다. 관리자 설정을 확인해주세요.")
            self.send_button.setEnabled(False)
            return

        sender = str(value.get("senderPhone") or "")
        self.sender_phone.setText(sender)
        self._sender_ready = bool(sender)
        self.recipient_changed()
        ready = self.send_button.isEnabled()
        # A previous send is not replayed as a success when this window opens.
        self.status.setText("문자 전송 가능" if ready else
                            "문자 전송 준비 실패 · 수신번호와 발신번호를 확인해주세요.")

    def send_sms(self):
        message = self.message.toPlainText().strip()
        if not message:
            self.status.setText("문자 전송 준비 실패 · 문자 내용을 입력해주세요.")
            QMessageBox.warning(self, "문자 확인", "문자 내용을 입력해주세요.")
            return
        if not self.manager or self.profile_task or self.send_task or self.status_task:
            return
        try:
            recipient = normalize_sms_recipient(self.recipient_phone.text())
        except UserError as error:
            self.status.setText("문자 전송 준비 실패 · " + str(error))
            self.recipient_phone.setFocus()
            return
        self.status_timer.stop()
        self.poll_count = 0
        self.success_confirmed = False
        if self.payment_notice:
            self.payment_confirm_button.setEnabled(False)
        self.message.setEnabled(False)
        self.recipient_phone.setReadOnly(True)
        self.send_button.setEnabled(False)
        self.check_button.setEnabled(False)
        self.status.setText("문자 전송 중")
        send = self.manager.send_payment_sms if self.payment_notice else self.manager.send_quote_sms
        # The override changes only this dispatch, not the saved customer/quote fingerprint.
        task = Task(lambda: send(self.draft, message, recipient_phone=recipient), None, self)
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
            self.recipient_phone.setReadOnly(False)
            self.recipient_changed()
            self.check_button.setEnabled(bool(self.delivery_state.get("smsMessageId")))
            self.status.setText("문자 전송 실패 · " + error)
            return
        if not isinstance(value, dict):
            self.message.setEnabled(True)
            self.recipient_phone.setReadOnly(False)
            self.recipient_changed()
            self.check_button.setEnabled(bool(self.delivery_state.get("smsMessageId")))
            self.status.setText("문자 전송 결과 확인 필요 · 서버 응답을 확인하지 못했습니다. 결과 확인 후 재전송해주세요.")
            return

        self.delivery_state = dict(value.get("delivery") or {})
        self.sender_phone.setText(str(value.get("senderPhone") or self.sender_phone.text()))
        self.check_button.setEnabled(True)
        self.status.setText("문자 전송 결과 확인 중")
        self.status_timer.start()

    def check_status(self, checked=False):
        if not self.manager or self.profile_task or self.status_task or self.send_task:
            return
        if not self.delivery_state.get("smsMessageId"):
            self.status.setText("문자 전송 결과 확인 필요 · 확인할 발송 기록이 없습니다.")
            return
        self.check_button.setEnabled(False)
        self.status.setText("문자 전송 결과 확인 중")
        refresh = self.manager.refresh_payment_sms_status if self.payment_notice else self.manager.refresh_quote_sms_status
        task = Task(lambda: refresh(self.draft["id"]), None, self)
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
            self.status.setText("문자 전송 결과 확인 실패 · " + error + " · 결과 확인을 다시 눌러주세요.")
            return
        if not isinstance(value, dict):
            self.check_button.setEnabled(True)
            self.status.setText("문자 전송 결과 확인 실패 · 응답을 확인하지 못했습니다. 결과 확인을 다시 눌러주세요.")
            return

        self.delivery_state = dict(value)
        state = self.delivery_state.get("smsState", "")
        if state == "success":
            self.status_timer.stop()
            self.check_button.setEnabled(False)
            if self.payment_notice:
                if self.delivery_state.get("smsQuoteFingerprint") != quote_delivery_fingerprint(self.draft):
                    self.status.setText("문자 전송 성공 · 이전 주문 내용입니다. 현재 주문 금액으로 다시 전송해주세요.")
                    self.message.setEnabled(True)
                    self.send_button.setEnabled(True)
                    return
                self.payment_confirm_button.setEnabled(True)
                self.status.setText("문자 전송 성공 · 입금확인을 누르면 판매관리에 등록됩니다.")
            else:
                self.status.setText("문자 전송 성공")
            return
        if state == "failed":
            self.status_timer.stop()
            self.status.setText("문자 전송 실패" + (" · " + self.delivery_state["smsStatusMessage"] if self.delivery_state.get("smsStatusMessage") else ""))
            self.message.setEnabled(True)
            self.recipient_phone.setReadOnly(False)
            self.recipient_changed()
            self.check_button.setEnabled(True)
            return

        self.poll_count += 1
        self.check_button.setEnabled(True)
        if self.poll_count < self.max_auto_polls:
            self.status.setText("문자 전송 결과 확인 중")
            self.status_timer.start()
        else:
            self.status.setText("문자 전송 결과 확인 중 · 자동 확인을 마쳤습니다. 결과 확인을 다시 눌러주세요.")

    def confirm_payment(self):
        # Delivery success never registers a sale without this explicit click.
        if not self.payment_notice or self.profile_task or self.send_task or self.status_task:
            return
        if (self.delivery_state.get("smsState") != "success" or
                self.delivery_state.get("smsQuoteFingerprint") != quote_delivery_fingerprint(self.draft)):
            return
        self.success_confirmed = True
        self.status_timer.stop()
        self.accept()

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
        self.success_confirmed = False
        self.profile_task = None
        self.send_task = None
        self.setObjectName("quoteEmailDialog")
        self.setWindowTitle("견적 메일 보내기")
        self.setSizeGripEnabled(True)

        screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
        available = screen.availableGeometry() if screen else None
        if available:
            large = bool(QApplication.instance().property("namufarmLargeFont"))
            width = min(920 if large else 860, available.width() - 40)
            height = min(1160, max(720, int(available.height() * 0.96)))
            width = min(width, max(620, available.width() - 40))
            height = min(height, max(560, available.height() - 50))
            self.resize(width, height)
        else:
            self.resize(860, 960)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        layout.setSpacing(12)

        layout.addWidget(label("견적 메일 보내기", "deliveryTitle"))

        address_card = QFrame()
        address_card.setObjectName("emailAddressCard")
        address = QGridLayout(address_card)
        address.setContentsMargins(10, 6, 10, 6)
        address.setHorizontalSpacing(12)
        address.setVerticalSpacing(8)

        # 메일 수신인은 고객명을 기본으로 사용합니다.
        self.recipient_name = label(self.draft.get("customerName") or "고객", wrap=True)
        self.recipient_name.setMaximumWidth(160)
        self.recipient_email = QLineEdit(self.draft.get("customerEmail", ""))
        self.recipient_email.setReadOnly(True)
        self.recipient_email.setMinimumWidth(0)
        self.recipient_email.setMaximumWidth(300)
        self.sender_email = QLineEdit(self.SENDER_EMAIL)
        self.sender_email.setReadOnly(True)
        self.sender_email.setMinimumWidth(0)
        self.sender_email.setMaximumWidth(300)

        address.addWidget(self.recipient_name, 0, 0)
        address.addWidget(label("수신 메일"), 0, 1)
        address.addWidget(self.recipient_email, 0, 2)
        address.addWidget(label("발신"), 0, 3)
        address.addWidget(self.sender_email, 0, 4)
        address.setColumnStretch(2, 1)
        address.setColumnStretch(4, 1)
        layout.addWidget(address_card)

        subject_row = QHBoxLayout()
        subject_row.setSpacing(8)
        subject_row.addWidget(label("제목"))
        self.subject = QLineEdit("묘목 견적 안내")
        self.subject.setMaxLength(160)
        subject_row.addWidget(self.subject, 1)
        address.addLayout(subject_row, 1, 0, 1, 5)

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

        self.status = DeliveryStatusLog("메일 전송 준비 중", self)
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
        setup_delivery_reading(self, layout)

        QTimer.singleShot(0, self.load_profile)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        fit_delivery_message(self)

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
            if item_specification(item):
                item_lines.append("규격 : " + item_specification(item))
            item_lines.append("")
        if self.draft.get("note", "").strip():
            item_lines.extend(["요청사항 : " + self.draft["note"].strip(), ""])
        total = draft_amounts(self.draft)["total"]
        shipping_fee = int(self.draft.get("shippingFee", 0) or 0)
        if self.draft.get("isPrepaid"):
            shipping_line = f"택배비 : 선불 {shipping_fee:,}원"
            total_line = f"총금액 : {total:,}원 (택배비 포함)"
        else:
            shipping_line = f"택배비 : 후불 / 선불 시 {shipping_fee:,}원"
            total_line = f"총금액 : {total:,}원"

        return "\n".join([
            f"{customer}님, 안녕하세요.",
            "",
            "나무팜농원입니다.",
            "요청하신 묘목 견적을 안내드립니다.",
            "",
            f"견적번호 : {self.quote_number()}",
            "",
            *item_lines,
            shipping_line,
            total_line,
            "",
            payment_notice(self.draft),
            "",
            "견적 내용을 확인하시고 궁금하신 사항은 편하게 연락 부탁드립니다.",
            "감사합니다.",
            "",
            "나무팜농원",
            "010-4446-0304 / 043-732-0980",
        ]).strip()

    def load_profile(self):
        self.status.setText("메일 전송 준비 중")
        if not self.manager:
            self.status.setText("메일 전송 준비 실패 · PC를 서버에 연결해주세요.")
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
            self.status.setText("메일 전송 준비 실패 · " + error)
            self.send_button.setEnabled(False)
            return
        if not isinstance(value, dict):
            self.status.setText("메일 전송 준비 실패 · 발신 설정 응답을 확인하지 못했습니다.")
            self.send_button.setEnabled(False)
            return
        sender = str(value.get("senderEmail") or "")
        if sender.lower() != self.SENDER_EMAIL.lower():
            self.status.setText(f"메일 전송 준비 실패 · 발신 계정을 확인해주세요. 현재 계정: {sender or '확인 불가'}")
            self.send_button.setEnabled(False)
            return
        self.sender_email.setToolTip(str(value.get("senderName") or "나무팜농원"))
        self.sender_email.setText(self.SENDER_EMAIL)
        try:
            quota = int(value.get("remainingDailyQuota", 0) or 0)
        except (TypeError, ValueError):
            quota = 0
        ready = bool(self.recipient_email.text().strip()) and quota > 0
        self.send_button.setEnabled(ready)
        if not self.recipient_email.text().strip():
            self.status.setText("메일 전송 준비 실패 · 고객 이메일을 입력해주세요.")
        elif quota <= 0:
            self.status.setText("메일 전송 준비 실패 · 오늘 남은 메일 전송 한도가 없습니다.")
        else:
            self.status.setText(f"메일 전송 가능 · 오늘 남은 전송 한도 {quota:,}건")

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
            self.status.setText("메일 전송 준비 실패 · 메일 제목과 내용을 입력해주세요.")
            QMessageBox.warning(self, "메일 확인", "메일 제목과 내용을 입력해주세요.")
            return
        if not self.manager or self.profile_task or self.send_task or self.success_confirmed:
            return
        self.subject.setEnabled(False)
        self.message.setEnabled(False)
        self.send_button.setEnabled(False)
        self.cancel_button.setEnabled(False)
        self.status.setText("메일 전송 중")
        plain, html = self.plain_body(), self.html_body()
        task = Task(
            lambda: self.manager.send_quote_email(
                self.draft, subject, plain, html,
                attachment_path=self.attachment_path,
            ),
            None, self
        )
        self.send_task = task
        # Keep status-log updates and dialog results on the GUI thread.
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
            self.status.setText("메일 전송 실패 · " + error)
            return
        if not isinstance(value, dict):
            self.subject.setEnabled(True)
            self.message.setEnabled(True)
            self.cancel_button.setEnabled(True)
            self.send_button.setEnabled(True)
            self.status.setText("메일 전송 결과 확인 필요 · 응답을 확인하지 못했습니다. 전송 내역을 확인해주세요.")
            return
        self.delivery_state = dict(value.get("delivery") or {})
        self.sender_email.setText(self.SENDER_EMAIL)
        attached = bool(value.get("attachmentName"))
        self.success_confirmed = True
        self.status.setText("메일 전송 성공" + (" · 공식견적서 첨부 완료" if attached else ""))
        self.subject.setReadOnly(True)
        self.subject.setEnabled(True)
        self.message.setReadOnly(True)
        self.message.setEnabled(True)
        self.cancel_button.setText("닫기")
        self.cancel_button.setEnabled(True)
        # Keep the result log visible; the parent receives Accepted when closed.

    def reject(self):
        if self.profile_task or self.send_task:
            self.status.setText("현재 메일 처리가 끝난 뒤 창을 닫아주세요.")
            return
        if self.success_confirmed:
            self.accept()
        else:
            super().reject()

class QuoteDeliveryDialog(QuoteDialog):
    """Workbench-style review with explicit item editing and a separate payment step."""

    def __init__(self, draft, request_status, manager=None, delivery_state=None, parent=None,
                 payment_order=None):
        snapshot = seller_note_draft(draft)
        self.payment_registered = bool(snapshot.pop("paymentRegistered", False) or
                                       snapshot.get("quoteStatus") == "입금확인" or payment_order)
        self.confirmed_result = True
        self.original_quote = copy.deepcopy(snapshot)
        self.action_task = None
        self.payment_view = None
        self.local_changed = False
        super().__init__(snapshot, parent, request_status=request_status, manager=manager)
        self.setWindowTitle("확정견적 확인")
        self.findChild(QLabel, "workbenchTitle").setText("확정견적 확인")
        self.delivery_state = dict(delivery_state or self.delivery_state)
        for field in self.fields.values():
            field.setReadOnly(True)
        self.note.setReadOnly(self.payment_registered)
        self.note.setAccessibleName("공식견적서 중요사항 · 판매자 입력")
        self.save_note_button = button("요청사항 저장", lambda: self.save_confirmed_note())
        self.workbench_note_area.layout().addWidget(self.save_note_button)
        self.workbench_shipping.hide()
        self.sms_button.hide()
        self.email_button.hide()
        self.delivery_heading.hide()
        self.workbench_items_hint.setText("확정 품목은 읽기 전용입니다. 변경하려면 묘목정보 수정을 눌러주세요.")
        self.add_item_button.clicked.disconnect()
        self.add_item_button.setText("묘목정보 수정")
        self.add_item_button.clicked.connect(self.edit_confirmed_items)
        self.remove_item_button.hide()
        self.confirm_button.clicked.disconnect()
        self.confirm_button.clicked.connect(self.receive_payment)
        self.payment_status = label("", "deliveryCaption", True)
        self.workbench_content_layout.addWidget(self.payment_status)
        self._render_confirmed_items()
        self._set_actions_enabled()
        self.recalculate()
        self.initial = self.raw_state()

    def _render_confirmed_items(self):
        # The final row is an audit of the quoted fee, never an editable order item.
        self.items.blockSignals(True)
        self.items.setRowCount(0)
        for index, item in enumerate(self.original_quote["items"]):
            self.items.insertRow(index)
            values = (item["species"], f"{item['quantity']:,}",
                      f"{item['quantity'] * item['unitPrice']:,}", f"{item['unitPrice']:,}",
                      str(index + 1), item_specification(item))
            for column, value in enumerate(values):
                cell = QTableWidgetItem(value)
                cell.setFlags(cell.flags() & ~Qt.ItemFlag.ItemIsEditable)
                cell.setTextAlignment((Qt.AlignmentFlag.AlignLeft if column in (0, 5) else
                                       Qt.AlignmentFlag.AlignCenter if column in (1, 4) else
                                       Qt.AlignmentFlag.AlignRight) | Qt.AlignmentFlag.AlignVCenter)
                cell.setToolTip(value)
                self.items.setItem(index, column, cell)
        row = self.items.rowCount()
        self.items.insertRow(row)
        prepaid = bool(self.original_quote.get("isPrepaid"))
        fee = int(self.original_quote.get("shippingFee", 0) or 0) if prepaid else 0
        values = ("택배비 (견적 당시)", "", f"{fee:,}", "", "—",
                  "선불 · 합계 포함" if prepaid else "후불 · 별도")
        for column, value in enumerate(values):
            cell = QTableWidgetItem(value)
            cell.setFlags(cell.flags() & ~Qt.ItemFlag.ItemIsEditable)
            cell.setBackground(QColor("#edf5ef"))
            cell.setTextAlignment((Qt.AlignmentFlag.AlignRight if column == 2 else
                                   Qt.AlignmentFlag.AlignLeft) | Qt.AlignmentFlag.AlignVCenter)
            self.items.setItem(row, column, cell)
        self.items.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        # No embedded text fields or +/- buttons in the review table.
        self.items.setItemDelegateForColumn(5, self.items.itemDelegate())
        self.items.set_column_layout(((4, 0, 44), (0, 3, 190), (5, 3, 140), (1, 0, 96),
                                      (3, 0, 116), (2, 0, 126)))
        self.items.blockSignals(False)
        self.fit_workbench_rows()

    def collect(self, validate=True):
        draft = copy.deepcopy(self.draft)
        draft.pop("paymentRegistered", None)
        if hasattr(self, "note"):
            draft["note"] = self.note.toPlainText().strip()
        return validate_draft(draft) if validate else draft

    def raw_state(self):
        return None  # Review is read-only; the separate editor saves explicitly.

    def recalculate(self, *args):
        if not hasattr(self, "shipping_fee") or not hasattr(self, "total"):
            return
        if self.generated_quote_path:
            self.cleanup_generated_quote()
            self.preview_quote_button.setEnabled(False)
            self.generate_quote_button.setText("공식견적서 생성")
            self.quote_file_label.setText("공식 견적서 미생성")
        self.shipping_fee_editor.setEnabled(False)
        self.shipping_fee.setEnabled(False)
        invalid = False
        try:
            current = self.collect(False)
            amount = draft_amounts(current)
            self.total.setText(f"{amount['total']:,}원")
            self.amount_detail.setText(f"총 수량 {amount['quantity']:,}주 · {quote_amount_detail(current)}")
        except UserError:
            invalid = True
            self.total.setText("택배비를 확인해주세요")
        self.total.setProperty("invalid", invalid)
        self.total.style().unpolish(self.total)
        self.total.style().polish(self.total)
        if hasattr(self, "sms_status_badge"):
            self.update_delivery_badges()

    def _set_actions_enabled(self):
        idle = self.action_task is None
        editable = idle and not self.payment_registered and self.manager is not None
        self.add_item_button.setEnabled(editable)
        self.note.setReadOnly(not editable)
        self.save_note_button.setEnabled(editable)
        self.confirm_button.setEnabled(editable)
        self.confirm_button.setText("입금확인 완료" if self.payment_registered else "입금확인")
        self.shipping_cod.setEnabled(False)
        self.shipping_prepaid.setEnabled(False)
        self.shipping_fee_editor.setEnabled(False)
        self.shipping_fee.setEnabled(False)
        self.sms_button.setEnabled(False)
        self.email_button.setEnabled(False)
        self.generate_quote_button.setEnabled(idle)
        self.preview_quote_button.setEnabled(idle and bool(self.generated_quote_path))
        self.close_button.setEnabled(idle)

    def _run_action(self, text, work, completed):
        if self.action_task is not None:
            return
        self.payment_status.setText(text)
        self.action_task = Task(work, None, self)
        self.action_task.completed.connect(completed)
        self._set_actions_enabled()
        self.action_task.start()

    def _finish_action(self, error):
        task = self.action_task
        if task is not None:
            task.wait()
            self.action_task = None
            task.deleteLater()
        self._set_actions_enabled()
        if error:
            self.payment_status.setText(error)
            QMessageBox.warning(self, "확정견적 처리", error)
            return False
        return True

    def save_confirmed_note(self, then_payment=False):
        if self.payment_registered or self.action_task or self.manager is None:
            return
        try:
            self._pending_note_edit = self.collect()
        except UserError as error:
            QMessageBox.warning(self, "요청사항 저장", str(error))
            return
        self._notes_then_payment = then_payment
        updated = copy.deepcopy(self._pending_note_edit)
        self._run_action("판매자 요청사항을 확정견적 엑셀에 저장하고 있습니다…",
                         lambda: self.manager.save_draft(updated), self._note_saved)

    @Slot(object, str, bool)
    def _note_saved(self, value, error, conflict):
        if not self._finish_action(error):
            return
        self.original_quote = copy.deepcopy(self._pending_note_edit)
        self.draft = copy.deepcopy(self.original_quote)
        self.local_changed = True
        self.payment_status.setText("판매자 요청사항을 저장했습니다.")
        if self._notes_then_payment:
            self.receive_payment()

    def edit_confirmed_items(self):
        if self.payment_registered or self.action_task or self.manager is None:
            return
        editor = QuoteDialog(self.collect(False), self, request_status=self.request_status,
                             manager=self.manager, items_only=True)
        editor.setWindowTitle("확정견적 묘목정보 수정")
        editor.findChild(QLabel, "workbenchTitle").setText("묘목정보 수정")
        for field in editor.fields.values():
            field.setReadOnly(True)
        editor.note.setReadOnly(False)
        editor.shipping_fee.setText(str(self.original_quote["shippingFee"]))
        editor.sms_button.hide()
        editor.email_button.hide()
        editor.delivery_heading.hide()
        editor.recalculate()
        editor.confirm_button.clicked.disconnect()
        editor.confirm_button.setText("수정 저장")
        editor.confirm_button.setEnabled(True)

        def save_items():
            try:
                edited = copy.deepcopy(self.original_quote)
                values = editor.collect()
                for key in ("items", "shippingFee", "isPrepaid", "note"):
                    edited[key] = values[key]
                editor.draft = validate_draft(edited)
                editor.accept()
            except UserError as error:
                QMessageBox.warning(editor, "묘목정보 확인", str(error))

        editor.confirm_button.clicked.connect(save_items)
        if editor.exec() != QDialog.DialogCode.Accepted:
            return
        updated = copy.deepcopy(editor.draft)
        self._pending_item_edit = updated
        self._run_action("묘목정보를 확정견적 엑셀에 저장하고 있습니다…",
                         lambda: self.manager.save_draft(updated), self._items_saved)

    @Slot(object, str, bool)
    def _items_saved(self, value, error, conflict):
        if not self._finish_action(error):
            return
        self.original_quote = copy.deepcopy(self._pending_item_edit)
        self.draft = copy.deepcopy(self.original_quote)
        self.local_changed = True
        self.note.blockSignals(True)
        self.note.setPlainText(self.draft.get("note", ""))
        self.note.blockSignals(False)
        self._render_confirmed_items()
        self.recalculate()
        self.payment_status.setText("묘목정보·택배비·판매자 요청사항을 저장했습니다.")

    def receive_payment(self):
        if self.payment_registered or self.action_task or self.manager is None:
            return
        if self.note.toPlainText().strip() != self.draft.get("note", "").strip():
            self.save_confirmed_note(then_payment=True)
            return
        try:
            self._payment_draft = self.collect()
        except UserError as error:
            QMessageBox.warning(self, "입금확인", str(error))
            return
        sms = QuoteSmsDialog(self._payment_draft, self.manager, self, payment=True)
        if sms.exec() != QDialog.DialogCode.Accepted or not sms.success_confirmed:
            sent = (sms.delivery_state.get("smsState") == "success" and
                    sms.delivery_state.get("smsQuoteFingerprint") == quote_delivery_fingerprint(self._payment_draft))
            self.payment_status.setText("문자 발송 완료 · 입금확인을 누르지 않아 판매 등록하지 않았습니다." if sent else
                                        "입금확인 문자 전송이 완료되지 않아 판매 등록하지 않았습니다.")
            return
        self._run_action("입금확인 주문을 판매관리에 저장하고 있습니다…",
                         lambda: self.manager.confirm_received_payment(self._payment_draft, sms_required=True),
                         self._payment_saved)

    @Slot(object, str, bool)
    def _payment_saved(self, value, error, conflict):
        if not self._finish_action(error):
            return
        self.payment_view = value
        self.accept()

    def reject(self):
        if self.action_task is not None:
            return
        if self.note.toPlainText().strip() != self.draft.get("note", "").strip():
            if QMessageBox.question(
                    self, "요청사항 확인", "저장하지 않은 요청사항을 버리고 닫을까요?",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
        QDialog.reject(self)


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
    """판매 품목은 조회하고 배송정보·메모를 로컬에서 관리하는 팝업."""

    def __init__(self, row, parent=None):
        super().__init__(parent)
        self.row = copy.deepcopy(row)
        self.meta = self.row["metadata"]
        self.order = self.row["private"]["order"]
        # The issued quote preserves the customer's words independently of shipping edits.
        try:
            document = json.loads(self.row["private"].get("quoteDocumentJson") or "{}")
        except (ValueError, TypeError):
            document = {}
        request_note = document.get("customerRequestNote", "") if isinstance(document, dict) else ""
        if not isinstance(request_note, str):
            request_note = ""
        source = self.order.get("sourceRequestId")
        if not request_note and source and parent is not None:
            request = next((r for r in getattr(parent, "data", {}).get("requests", [])
                            if r["requestId"] == source), None)
            if request:
                request_note = request.get("personal", {}).get("message", "")
        self.mode = ""
        self.collected_fields = None
        self.setObjectName("quoteWorkbench")
        self.setWindowTitle("판매관리 · 고객·배송 정보")
        self.setSizeGripEnabled(True)
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        identity_height = 48 if large else 44
        self.setStyleSheet(quote_workbench_style(large) + """
QDialog#quoteWorkbench QLabel#workbenchCaption{padding:4px 8px;}
QDialog#quoteWorkbench QLineEdit#workbenchCustomerInput{min-height:@input@px;max-height:@input@px;padding:4px 8px;}
QDialog#quoteWorkbench QLineEdit#workbenchCustomerInput:focus{padding:3px 7px;}
QDialog#quoteWorkbench QPushButton{min-height:36px;max-height:36px;padding:0 12px;}
QDialog#quoteWorkbench QPushButton[deliveryCompletion="true"]{min-height:40px;max-height:40px;font-size:18px;}
QDialog#quoteWorkbench QWidget#workbenchCustomerRail QLineEdit#workbenchCustomerInput{min-height:@identity@px;max-height:@identity@px;padding:6px 8px;}
QDialog#quoteWorkbench QWidget#workbenchCustomerRail QLineEdit#workbenchCustomerInput:focus{padding:5px 7px;}
QDialog#quoteWorkbench QWidget#workbenchCustomerRail QLineEdit#workbenchCustomerInput[customerName="true"]{font-size:24px;font-weight:600;}
QDialog#quoteWorkbench QFrame#workbenchShipping{background:#f5f8f6;border:1px solid #d6e1da;border-radius:10px;}
QDialog#quoteWorkbench QLabel#salesShippingFee{font-size:@freight@px;font-weight:600;color:#193c32;}
QDialog#quoteWorkbench[compactLayout="true"] QFrame#workbenchShipping QLineEdit#workbenchCustomerInput{font-size:@shipfont@px;min-height:22px;max-height:22px;padding:3px 6px;}
QDialog#quoteWorkbench[compactLayout="true"] QFrame#workbenchShipping QLineEdit#workbenchCustomerInput:focus{padding:2px 5px;}
QDialog#quoteWorkbench[compactLayout="true"] QFrame#workbenchShipping QPushButton{font-size:@shipfont@px;min-height:28px;max-height:28px;padding:0 8px;}
QDialog#quoteWorkbench[compactLayout="true"] QFrame#workbenchShipping QLabel#workbenchCaption{font-size:@shipfont@px;padding:3px 6px;}
QDialog#quoteWorkbench[compactLayout="true"] QFrame#workbenchShipping QLabel#workbenchSectionTitle{font-size:16px;}
""".replace("@input@", "28" if large else "26").replace("@identity@", "34" if large else "30").replace("@freight@", "22" if large else "20").replace("@shipfont@", "16" if large else "14"))
        screen = parent.screen() if parent is not None and hasattr(parent, "screen") else QApplication.primaryScreen()
        if screen:
            available = screen.availableGeometry()
            width = min(1580, max(480, available.width() - 40))
            height = min(1000, int(available.height() * .95), available.height() - 48)
            self.setMinimumSize(min(960, width), min(600, height))
            self.resize(width, height)
        else:
            self.resize(1580, 980)
            self.setMinimumSize(960, 600)

        def icon(name, size=26, color="#193c32"):
            widget = QLabel()
            widget.setFixedSize(size, size)
            widget.setPixmap(ui_icon(name, color).pixmap(QSize(size, size), self.devicePixelRatioF()))
            return widget

        def section(title, name):
            widget = QWidget()
            heading = QHBoxLayout(widget)
            heading.setContentsMargins(0, 0, 0, 0)
            heading.setSpacing(10)
            heading.addWidget(icon(name))
            heading.addWidget(label(title, "workbenchSectionTitle"))
            heading.addStretch(1)
            return widget

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        header = QFrame()
        header.setObjectName("workbenchHeader")
        heading = QHBoxLayout(header)
        heading.setContentsMargins(20, 8, 20, 8)
        heading.setSpacing(20)
        heading.addWidget(icon("leaf", 32, "#91d39d"))
        heading.addWidget(label("나무팜(주)", "workbenchBrand"))
        brand_rule = QFrame()
        brand_rule.setObjectName("workbenchBrandRule")
        brand_rule.setFixedSize(1, 30)
        heading.addWidget(brand_rule)
        heading.addWidget(label("판매관리", "workbenchTitle"))
        heading.addStretch(1)
        reference = label("판매번호  " + sale_number(self.row), "workbenchReference", True)
        reference.setMinimumWidth(0)
        reference.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        reference.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        reference.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        heading.addWidget(reference, 1)
        root.addWidget(header)

        body = QHBoxLayout()
        body.setContentsMargins(0, 0, 0, 0)
        body.setSpacing(0)
        customer_scroll = QScrollArea()
        self.customer_scroll = customer_scroll
        customer_scroll.setObjectName("workbenchCustomerScroll")
        customer_scroll.setWidgetResizable(True)
        customer_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        customer_scroll.setMinimumWidth(360 if large else 340)
        customer_scroll.setMaximumWidth(520 if large else 480)
        rail = QWidget()
        rail.setObjectName("workbenchCustomerRail")
        customer = QVBoxLayout(rail)
        customer.setContentsMargins(16, 12, 16, 10)
        customer.setSpacing(10)
        customer_heading = QWidget()
        customer_title = QHBoxLayout(customer_heading)
        customer_title.setContentsMargins(0, 0, 0, 0)
        customer_title.setSpacing(8)
        customer_title.addWidget(icon("person", 36))
        customer_title.addWidget(label("고객 정보", "workbenchCustomerTitle"))
        customer_title.addStretch(1)
        customer.addWidget(customer_heading)
        rule = QFrame()
        rule.setObjectName("workbenchCustomerHeadingRule")
        rule.setFixedHeight(2)
        customer.addWidget(rule)
        self.customer_fields = {}

        def field(name, caption):
            edit = QLineEdit(str(self.order.get(name, "") or ""))
            edit.setObjectName("workbenchCustomerInput")
            edit.setProperty("customerName", name == "customerName")
            edit.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            edit.setAccessibleName(caption)
            edit.setMinimumWidth(0)
            edit.setMaxLength({"customerEmail": 254, "roadAddress": 300, "detailAddress": 300,
                               "receiverName": 100, "customerName": 50, "customerPhone": 20, "zipNo": 10}[name])
            self.customer_fields[name] = edit
            return edit

        identity = QGridLayout()
        identity.setHorizontalSpacing(8)
        identity.setVerticalSpacing(4)
        identity.setColumnStretch(1, 1)
        identity.setColumnMinimumWidth(0, 84 if large else 76)

        def identity_caption(text):
            caption = label(text, "workbenchCaption")
            caption.setFixedHeight(identity_height)
            return caption

        for index, (name, caption) in enumerate((("customerName", "고객명"), ("customerPhone", "연락처"),
                                                  ("customerEmail", "이메일"))):
            identity.addWidget(identity_caption(caption), index, 0)
            edit = field(name, caption)
            edit.setFixedHeight(identity_height)
            identity.addWidget(edit, index, 1)
        if self.row["private"].get("quoteDocumentJson"):
            self.customer_fields["customerPhone"].setReadOnly(True)
            self.customer_fields["customerPhone"].setToolTip("확정 견적서와 연결된 고객 전화번호는 유지합니다.")
        for index, (caption, value) in enumerate((("상태", customer_status_text(self.meta["status"])),
                ("주문일", timestamp(self.meta["orderDate"])), ("입금일", timestamp(self.meta["paidAt"]))), 3):
            identity.addWidget(identity_caption(caption), index, 0)
            display = QLineEdit(value)
            display.setObjectName("workbenchCustomerInput")
            display.setAccessibleName(caption)
            display.setReadOnly(True)
            display.setMinimumWidth(0)
            display.setFixedHeight(identity_height)
            display.setCursorPosition(0)
            display.setToolTip(value)
            identity.addWidget(display, index, 1)
        customer.addLayout(identity)

        self.note = QPlainTextEdit(customer_note(self.order))
        self.note.setAccessibleName("배송 메모")
        self.note.setPlaceholderText("포장·배송 시 확인할 내용을 입력하세요")
        self.note.setMinimumHeight(112 if large else 96)
        self.customer_note_section = collapsible("배송 메모", self.note, expanded=self.height() >= 900)
        customer.addWidget(self.customer_note_section)
        customer.addStretch(1)
        customer_scroll.setWidget(rail)
        body.addWidget(customer_scroll)

        work_scroll = QScrollArea()
        self.work_scroll = work_scroll
        work_scroll.setWidgetResizable(True)
        work_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        workspace = QWidget()
        workspace.setObjectName("workbenchBody")
        self.customer_workspace = workspace
        content = QVBoxLayout(workspace)
        content.setContentsMargins(12, 8, 12, 8)
        content.setSpacing(10)
        self.customer_items_panel = QFrame()
        self.customer_items_panel.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        item_content = QVBoxLayout(self.customer_items_panel)
        item_content.setContentsMargins(0, 0, 0, 0)
        item_content.setSpacing(10)
        self.customer_items_heading = section("판매 품목", "leaf")
        item_content.addWidget(self.customer_items_heading)
        content.addWidget(self.customer_items_panel)
        self.items = table(["묘목명", "규격", "수량", "판매 단가", "판매 금액"])
        self.items.setObjectName("workbenchItems")
        specifications = [parts[3].strip() if len(parts) in (9, 10) else ""
                          for parts in (line.split("|") for line in self.order.get("itemSummary", "").splitlines() if line.strip())]
        fill_table(self.items, [
            (item["species"], (specifications[index] if index < len(specifications) else "") or "—", f"{item['quantity']:,}주",
             money(item["unitPrice"]), money(item["quantity"] * item["unitPrice"]))
            for index, item in enumerate(self.meta.get("items", []))
        ])
        polish_table(self.items)
        center_table_columns(self.items, (1, 2, 3, 4))
        self.items.set_column_layout(((0, 3.5), (1, 1.5), (2, 1), (3, 1.7), (4, 2)))
        self.items.horizontalHeader().setFixedHeight(38 if large else 34)
        # Override the main-list minimum before setting the compact item height.
        roomy = not screen or screen.availableGeometry().height() >= 900
        row_height = (42 if large else 38) if roomy else (36 if large else 32)
        self.items.verticalHeader().setMinimumSectionSize(row_height)
        self.items.verticalHeader().setDefaultSectionSize(row_height)
        self.items.verticalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Fixed)
        self.items.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.items.setFixedHeight(self.items.horizontalHeader().height() + 5 * row_height + 4)
        item_content.addWidget(self.items)
        item_summary = QFrame()
        self.customer_items_summary = item_summary
        item_summary.setObjectName("workbenchSummary")
        item_summary.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        totals = QHBoxLayout(item_summary)
        totals.setContentsMargins(10, 6, 10, 6)
        shipping_text = f"선불 {money(self.meta['shippingFee'])}" if self.meta["isPrepaid"] else "후불"
        totals.addWidget(label("택배비  " + shipping_text, "salesShippingFee"))
        totals.addStretch(1)
        totals.addWidget(label("총금액  " + money(self.meta["totalPrice"] + self.meta.get("taxAmount", 0)), "deliverySectionTitle"))
        item_content.addWidget(item_summary)
        content.addSpacing(6)
        request_area = QFrame()
        request_area.setObjectName("workbenchOriginalRequest")
        request_layout = QVBoxLayout(request_area)
        request_layout.setContentsMargins(0, 0, 0, 0)
        request_layout.setSpacing(8)
        self.request_note_view = QPlainTextEdit(request_note)
        self.request_note_view.setObjectName("workbenchOriginalText")
        self.request_note_view.setAccessibleName("고객의 견적요청 원문")
        self.request_note_view.setReadOnly(True)
        self.request_note_view.setPlaceholderText("저장된 고객 요청사항이 없습니다.")
        self.request_note_view.document().setDocumentMargin(0)
        self.request_note_view.setFixedHeight(88 if large else 76)
        request_layout.addWidget(self.request_note_view)
        self.customer_request_section = collapsible("고객 요청정보", request_area, expanded=self.height() >= 900)

        shipping = QFrame()
        shipping.setObjectName("workbenchShipping")
        shipping_layout = QVBoxLayout(shipping)
        shipping_layout.setContentsMargins(10, 6, 10, 6)
        shipping_layout.setSpacing(4)
        shipping_layout.addWidget(section("배송 정보 · 송장", "shipping"))
        # Keep addresses readable without stretching the fields across the whole table.
        form_area = QWidget()
        form_area.setMaximumWidth(860 if large else 820)
        form_area.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
        form = QGridLayout(form_area)
        form.setContentsMargins(0, 0, 0, 0)
        form.setHorizontalSpacing(12)
        form.setVerticalSpacing(4)
        for r, (name, caption) in enumerate((("receiverName", "수령인"), ("zipNo", "우편번호"),
                ("roadAddress", "도로명 주소"), ("detailAddress", "상세주소"))):
            form.addWidget(label(caption, "workbenchCaption"), r, 0)
            edit = field(name, caption)
            if name == "zipNo":
                edit.setFixedWidth(186 if large else 168)
                edit.setPlaceholderText("우편번호")
                postal_row = QHBoxLayout()
                postal_row.setSpacing(8)
                postal_row.addWidget(edit)
                self.address_button = button("주소찾기", self.find_address)
                postal_row.addWidget(self.address_button)
                postal_row.addStretch(1)
                form.addLayout(postal_row, r, 1)
            elif name == "receiverName":
                edit.setMaximumWidth(360 if large else 320)
                recipient_row = QHBoxLayout()
                recipient_row.setContentsMargins(0, 0, 0, 0)
                recipient_row.addWidget(edit, 1)
                recipient_row.addStretch(1)
                form.addLayout(recipient_row, r, 1)
            else:
                form.addWidget(edit, r, 1)
        self.tracking_number_edit = QLineEdit(tracking_number(self.order))
        self.tracking_number_edit.setObjectName("workbenchCustomerInput")
        self.tracking_number_edit.setAccessibleName("로젠 송장번호")
        self.tracking_number_edit.setPlaceholderText("로젠 송장번호 11자리")
        self.tracking_number_edit.setMaximumWidth(280 if large else 260)
        self.tracking_number_edit.setMaxLength(20)
        form.addWidget(label("송장번호", "workbenchCaption"), 4, 0)
        tracking_row = QHBoxLayout()
        tracking_row.setSpacing(8)
        tracking_row.addWidget(self.tracking_number_edit, 1)
        self.tracking_entry_button = button("송장번호 입력", self.enter_tracking)
        tracking_row.addWidget(self.tracking_entry_button)
        tracking_row.addStretch(1)
        form.addLayout(tracking_row, 4, 1)
        form.setColumnStretch(1, 1)
        form_row = QHBoxLayout()
        form_row.setContentsMargins(0, 0, 0, 0)
        form_row.addWidget(form_area, 1)
        form_row.addStretch(0)
        shipping_layout.addLayout(form_row)
        # Only delivery details scroll; the item table and totals retain their own space.
        delivery_details = QWidget()
        self.customer_delivery_details = delivery_details
        details = QVBoxLayout(delivery_details)
        details.setContentsMargins(0, 0, 0, 0)
        details.setSpacing(10)
        details.addWidget(shipping)
        details.addWidget(self.customer_request_section)
        details.addStretch(1)
        for section_widget in (self.customer_note_section, self.customer_request_section):
            section_widget.findChild(QPushButton).toggled.connect(lambda: self.fit_customer_content())
        work_scroll.setWidget(delivery_details)
        work_scroll.setMinimumHeight(120)
        content.addWidget(work_scroll, 1)
        body.addWidget(workspace, 1)
        root.addLayout(body, 1)

        terminal = self.meta["status"] in ("배송완료", "구매취소", "판매완료", "주문취소")
        if terminal:
            for widget in (*self.customer_fields.values(), shipping):
                widget.setEnabled(False)
        footer = QFrame()
        footer.setObjectName("workbenchFooter")
        footer_layout = QVBoxLayout(footer)
        footer_layout.setContentsMargins(14, 7, 14, 8)
        footer_layout.setSpacing(6)
        self.edit_state = label("송장 등록 · 출고 대기" if shipping_ready(self.order) else
                                "주소 확인완료 · 송장 발급 대기" if shipping_prepared(self.order) else
                                "주소 확인 후 송장 준비를 누르세요. 송장번호는 발급 후 등록할 수 있습니다.", "deliveryCaption", True)
        footer_layout.addWidget(self.edit_state)
        actions = QHBoxLayout()
        self.cancel_order_button = button("구매 취소", lambda: self.finish("cancel"))
        self.cancel_order_button.setObjectName("danger")
        self.cancel_order_button.setEnabled(self.meta["status"] in ("입금확인", "구매확정"))
        actions.addWidget(self.cancel_order_button)
        self.restore_button = button("입금확인으로 복원", lambda: self.finish("restore"))
        self.restore_button.setEnabled(self.meta["status"] in ("구매취소", "주문취소"))
        actions.addWidget(self.restore_button)
        actions.addStretch(1)
        actions.addWidget(button("닫기", self.reject))
        self.save_button = button("수정 저장", lambda: self.finish("save"))
        self.save_button.setEnabled(True)
        self.save_button.setText("메모 저장" if terminal else "수정 저장")
        actions.addWidget(self.save_button)
        self.preparation_button = button("배송준비완료" if tracking_number(self.order) else "주소확인 · 송장 준비",
                                         lambda: self.finish("prepare"), True)
        self.preparation_button.setEnabled(self.meta["status"] == "입금확인")
        self.preparation_button.setProperty("deliveryCompletion", True)
        self.preparation_button.setMinimumWidth(198)
        self.preparation_button.setToolTip("수령인·연락처·주소를 확인하고 배송·송장 목록에 표시합니다. 송장번호 없이도 로젠 XLS를 저장할 수 있습니다.")
        actions.addWidget(self.preparation_button)
        footer_layout.addLayout(actions)
        root.addWidget(footer)
        for edit in (*self.customer_fields.values(), self.tracking_number_edit):
            edit.textChanged.connect(lambda _text: self.edit_state.setText("수정 중 · 저장 필요"))
        self.note.textChanged.connect(lambda: self.edit_state.setText("수정 중 · 저장 필요"))
        for widget, name, color in ((self.cancel_order_button, "trash", "#9c3c2e"), (self.save_button, "save", "#193c32"),
                (self.preparation_button, "shipping", "#ffffff"),
                (self.restore_button, "refresh", "#193c32")):
            set_button_icon(widget, name, color)
        self.fit_customer_rail()
        self.fit_customer_content()
        self._initial_input = self.raw_input_state()

    def raw_input_state(self):
        return (tuple((name, edit.text()) for name, edit in self.customer_fields.items()),
                self.tracking_number_edit.text(), self.note.toPlainText())

    def reject(self):
        if self.raw_input_state() != self._initial_input:
            prompt = QMessageBox(self)
            prompt.setWindowTitle("수정 내용 확인")
            prompt.setText("저장하지 않은 수정 내용이 있습니다. 저장하지 않고 닫을까요?")
            keep = prompt.addButton("계속 작성", QMessageBox.ButtonRole.RejectRole)
            discard = prompt.addButton("저장하지 않고 닫기", QMessageBox.ButtonRole.DestructiveRole)
            prompt.setDefaultButton(keep)
            prompt.setEscapeButton(keep)
            prompt.exec()
            if prompt.clickedButton() is not discard:
                return
        super().reject()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if hasattr(self, "customer_scroll"):
            fit_quote_density(self)
            self.fit_customer_rail()
        if hasattr(self, "customer_request_section"):
            self.fit_customer_content()

    def fit_customer_content(self):
        compact = self.height() < 900
        if compact != getattr(self, "_customer_compact", None):
            self._customer_compact = compact
            for section in (self.customer_note_section, self.customer_request_section):
                section.findChild(QPushButton).setChecked(not compact)
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        dense = bool(self.property("compactLayout"))
        row_height = (32 if large else 28) if dense else (42 if large else 38)
        self.items.verticalHeader().setMinimumSectionSize(row_height)
        self.items.verticalHeader().setDefaultSectionSize(row_height)
        self.items.horizontalHeader().setFixedHeight((34 if large else 30) if dense else (38 if large else 34))
        for row in range(self.items.rowCount()):
            self.items.setRowHeight(row, row_height)
        layout = self.customer_workspace.layout()
        layout.activate()
        margins = layout.contentsMargins()
        item_layout = self.customer_items_panel.layout()
        item_overhead = (self.customer_items_heading.sizeHint().height() +
                         self.customer_items_summary.sizeHint().height() + 2 * item_layout.spacing())
        # Fit the full delivery form first, then reserve 5–8 rows in the remaining space.
        delivery_height = self.customer_delivery_details.minimumSizeHint().height()
        room = (self.customer_workspace.height() - margins.top() - margins.bottom() -
                2 * layout.spacing() - 6 - delivery_height - item_overhead -
                self.items.horizontalHeader().height() - 4)
        rows = max(5, min(8, room // row_height))
        table_height = self.items.horizontalHeader().height() + rows * row_height + 4
        self.items.setFixedHeight(table_height)
        # Reserve the whole group, not just the table, before allocating delivery space.
        panel_height = table_height + item_overhead
        self.customer_items_panel.setFixedHeight(panel_height)
        item_layout.invalidate()

    def showEvent(self, event):
        super().showEvent(event)
        fit_quote_density(self)
        self.fit_customer_rail()
        self.fit_customer_content()
        QTimer.singleShot(0, self.fit_customer_content)

    def fit_customer_rail(self):
        # Give the uniform customer fields a little more width than the quote rail.
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        width = min(520 if large else 480, max(360 if large else 340, self.width() - 860))
        self.customer_scroll.setFixedWidth(width)

    def find_address(self):
        from .address_search import AddressSearchDialog
        try:
            dialog = AddressSearchDialog(self)
        except (OSError, UserError) as error:
            QMessageBox.warning(self, "주소찾기", str(error) if isinstance(error, UserError) else
                                "주소검색 창을 준비하지 못했습니다. 잠시 후 다시 시도해주세요.")
            return
        try:
            if dialog.exec() == QDialog.DialogCode.Accepted and dialog.address:
                data = dialog.address
                changed_address = (self.customer_fields["zipNo"].text() != data["zipNo"] or
                                   self.customer_fields["roadAddress"].text() != data["address"])
                self.customer_fields["zipNo"].setText(data["zipNo"])
                self.customer_fields["roadAddress"].setText(data["address"])
                if changed_address:
                    self.customer_fields["detailAddress"].clear()
                self.customer_fields["detailAddress"].setFocus()
        finally:
            dialog.deleteLater()

    def enter_tracking(self):
        from .tracking_input import TrackingNumberDialog
        dialog = TrackingNumberDialog(self.tracking_number_edit.text(), self)
        try:
            if dialog.exec() == QDialog.DialogCode.Accepted:
                self.tracking_number_edit.setText(dialog.number)
                self.tracking_number_edit.setFocus()
        finally:
            dialog.deleteLater()

    def collect_fields(self):
        terminal = self.meta["status"] in ("배송완료", "구매취소", "판매완료", "주문취소")
        note = self.note.toPlainText().strip()
        if terminal:
            number = tracking_number(self.order)
            if number:
                note = (note + "\n" if note else "") + TRACKING_PREFIX + number
            return {"note": note}
        result = {name: edit.text().strip() for name, edit in self.customer_fields.items()}
        result["note"] = note
        tracking = self.tracking_number_edit.text().strip()
        if tracking:
            result.update(tracking_fields({**self.order, **result}, tracking))
        return result

    def finish(self, mode):
        try:
            fields = self.collect_fields()
            if mode == "prepare":
                order = {**self.order, **fields}
                problem = shipping_problem(order)
                require(not problem, problem)
            elif mode == "cancel":
                answer = QMessageBox.question(
                    self, "구매취소", "이 주문을 구매취소로 처리할까요?",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.No,
                )
                if answer != QMessageBox.StandardButton.Yes:
                    return
            elif mode == "restore":
                answer = QMessageBox.question(
                    self, "입금확인 복원", "구매취소를 되돌리고 입금확인 상태로 복원할까요?",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.No)
                if answer != QMessageBox.StandardButton.Yes:
                    return
            self.collected_fields = fields
            self.mode = mode
            self.accept()
        except UserError as error:
            QMessageBox.warning(self, "입력 확인", str(error))


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
        fill_table(self.parcel_table, [(sale_number(r), r["private"]["order"]["receiverName"],
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
    PAGE_REQUESTS, PAGE_QUOTES, PAGE_SALES, PAGE_SHIPPING, PAGE_REPORTS, PAGE_FARMS, PAGE_SPECIES, PAGE_SETTINGS = range(8)
    PAGES = (("견적요청", "INBOX"), ("견적 작업대", "QUOTATIONS"),
             ("판매관리", "ORDERS"), ("배송 · 송장", "FULFILLMENT"), ("매출 · 이익", "REPORTS"),
             ("농원관리", "FARMS"), ("묘목관리", "SPECIES"), ("연결 · 설정", "SETTINGS"))

    def __init__(self, vault_path=None):
        self.workspace_ready = False
        self._last_storage_transfer_error = ""
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
        sidebar.setFixedWidth(286)
        side = QVBoxLayout(sidebar)
        side.setContentsMargins(12, 16, 12, 14)
        side.setSpacing(8)
        self.brand_panel = QFrame()
        self.brand_panel.setObjectName("brandPanel")
        brand_layout = QVBoxLayout(self.brand_panel)
        brand_layout.setContentsMargins(12, 18, 12, 18)
        self.brand_label = label("나무팜(주)", "brand")
        self.brand_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        brand_layout.addWidget(self.brand_label)
        side.addWidget(self.brand_panel)
        side.addSpacing(6)
        self.nav = []
        for index, (title, _) in enumerate(self.PAGES):
            widget = NavigationButton(title)
            widget.clicked.connect(lambda checked=False, i=index: self.navigate(i))
            icons = ("requests", "confirmed", "orders", "shipping", "reports", "farm", "leaf", "settings")
            set_button_icon(widget, icons[index], "#e0ebe3", 22)
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
        side.addStretch(1)
        self.sidebar_tree = SidebarTree()
        side.addWidget(self.sidebar_tree, 1)
        self.sidebar_clock = QFrame()
        self.sidebar_clock.setObjectName("sidebarClock")
        clock_layout = QVBoxLayout(self.sidebar_clock)
        clock_layout.setContentsMargins(14, 12, 14, 12)
        clock_layout.setSpacing(3)
        self.sidebar_date = label("", "sidebarDate")
        self.sidebar_time = DigitalClock()
        for clock_label in (self.sidebar_date, self.sidebar_time):
            if isinstance(clock_label, QLabel):
                clock_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
            clock_layout.addWidget(clock_label)
        side.addWidget(self.sidebar_clock)
        self.update_sidebar_clock()
        self.sidebar_clock_timer = QTimer(self)
        self.sidebar_clock_timer.setInterval(1000)
        self.sidebar_clock_timer.timeout.connect(self.update_sidebar_clock)
        self.sidebar_clock_timer.start()
        outer.addWidget(sidebar)
        content = QWidget()
        content_layout = QVBoxLayout(content)
        self.page_content_layout = content_layout
        content_layout.setContentsMargins(24, 16, 24, 16)
        content_layout.setSpacing(12)
        self.other_page_title = label("", "pageTitle")
        content_layout.addWidget(self.other_page_title)
        header = QHBoxLayout()
        self.page_header_layout = header
        header.setSpacing(20)
        self.page_summary = QFrame()
        self.page_summary.setObjectName("pageSummary")
        self.page_summary.setFixedSize(226, 144)
        summary_layout = QVBoxLayout(self.page_summary)
        summary_layout.setContentsMargins(16, 14, 16, 14)
        summary_layout.setSpacing(8)
        self.page_title = label("", "summaryTitle")
        summary_layout.addWidget(self.page_title)
        summary_layout.addStretch(1)
        count_row = QHBoxLayout()
        count_row.setSpacing(8)
        count_row.addStretch(1)
        self.page_count_number = label("0", "summaryCount")
        self.page_count_number.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignBottom)
        self.page_count_unit = label("건", "summaryCountUnit")
        count_row.addWidget(self.page_count_number, 0, Qt.AlignmentFlag.AlignBottom)
        count_row.addWidget(self.page_count_unit, 0, Qt.AlignmentFlag.AlignBottom)
        summary_layout.addLayout(count_row)
        header.addWidget(self.page_summary)
        self.sync_button.setText("가져오기")
        set_button_icon(self.sync_button, "refresh")
        self.sync_button.setToolTip("견적요청: 서버 신규 요청 · 판매관리: 로컬 DB · 서버 백업은 판매관리의 서버에 올리기. F5")
        self.sync_button.setObjectName("syncSecondary")
        self.sync_button.setMinimumWidth(138)

        self.server_log_frame = QFrame()
        self.server_log_frame.setObjectName("serverLogFrame")
        self.server_log_frame.setProperty("state", "idle")
        self.server_log_frame.setProperty("pulse", False)
        self.status_pulse_timer = QTimer(self)
        self.status_pulse_timer.setInterval(550)
        self.status_pulse_timer.timeout.connect(self.pulse_status_background)
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
        header.addWidget(self.server_log_frame, 1, Qt.AlignmentFlag.AlignBottom)
        content_layout.addLayout(header)
        self.storage_status = label("PC 자료를 열면 저장 상태가 표시됩니다.", "storageStatus", True)
        self.storage_status.setAccessibleName("PC 저장 및 서버 반영 상태")
        content_layout.addWidget(self.storage_status)

        self.stack = QStackedWidget()
        self.stack.addWidget(old_pages[0])
        self.stack.addWidget(self.build_drafts())
        self.stack.addWidget(self.build_customer_management())
        self.stack.addWidget(self.build_shipping())
        self.stack.addWidget(scroll_page(old_pages[2]))
        self.farms_page = NurseryPage(self, 'farms')
        self.species_page = NurseryPage(self, 'species')
        self.stack.addWidget(self.farms_page)
        self.stack.addWidget(self.species_page)
        self.stack.addWidget(old_pages[3])
        for index in range(self.stack.count()):
            self.stack.widget(index).setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Ignored)
        content_layout.addWidget(self.stack, 1)
        outer.addWidget(content, 1)
        old_root.deleteLater()
        self.manual_quote_button = button("+ 견적", self.new_manual_quote)
        self.manual_quote_button.setObjectName("requestQuote")
        self.manual_quote_button.setMinimumWidth(140)
        self.manual_quote_button.setAccessibleName("새 견적 직접 작성")
        self.manual_quote_button.setToolTip("요청을 선택하지 않고 고객 정보와 묘목을 직접 입력해 견적을 작성합니다.")
        self.request_actions.insertWidget(1, self.manual_quote_button)
        self.request_quote_button = button("선택 요청 견적", self.quote_request)
        self.request_quote_button.setObjectName("requestQuote")
        self.request_quote_button.setMinimumWidth(160)
        self.request_quote_button.setToolTip("목록에서 견적요청을 선택한 뒤 작성하세요. 요청을 두 번 클릭해도 열립니다.")
        self.request_table.itemDoubleClicked.connect(self.quote_request)
        self.request_actions.insertWidget(2, self.request_quote_button)
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
        set_button_icon(self.request_quote_button, "document", "#23593a")
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

    def update_sidebar_clock(self):
        current = datetime.now(SEOUL)
        weekday = ("월", "화", "수", "목", "금", "토", "일")[current.weekday()]
        self.sidebar_date.setText(f"{current:%Y.%m.%d} ({weekday})")
        self.sidebar_time.setText(current.strftime("%H:%M:%S"))

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if not hasattr(self, "ui_settings"):
            return
        self.update_responsive_layout()

    def apply_design_scale(self, large):
        app = QApplication.instance()
        app.setProperty("namufarmLargeFont", bool(large))
        self.ui_settings.setValue("ui/largeFont", bool(large))
        self._responsive_state = None
        self.update_responsive_layout()
        if self.workspace_ready:
            self.refresh_storage_status()

    def update_responsive_layout(self):
        # Qt geometry is in logical pixels: 1920x1200 at 150% is about 1280x800.
        compact = self.width() < 1440 or self.height() < 850
        app = QApplication.instance()
        large = bool(app.property("namufarmLargeFont"))
        stacked_tools = self.width() < 1240
        state = (compact, large, stacked_tools)
        if state == getattr(self, "_responsive_state", None):
            return
        self._responsive_state = state
        app.setProperty("namufarmCompactLayout", compact)
        style = workspace_style(large, compact)
        app.setStyleSheet(style)
        self.setStyleSheet(style)
        self.sidebar.setFixedWidth((236 if large else 214) if compact else 286)
        self.sidebar.layout().setSpacing(6 if compact else 8)
        self.sidebar.layout().setContentsMargins(10 if compact else 12, 12 if compact else 16,
                                                 10 if compact else 12, 12 if compact else 14)
        self.brand_panel.layout().setContentsMargins(10 if compact else 12, 10 if compact else 18,
                                                     10 if compact else 12, 10 if compact else 18)
        self.page_content_layout.setContentsMargins(16 if compact else 24, 12 if compact else 16,
                                                   16 if compact else 24, 12 if compact else 16)
        self.page_content_layout.setSpacing(8 if compact else 12)
        self.page_header_layout.setSpacing(12 if compact else 20)
        self.page_summary.setFixedSize(164 if compact else 226, 88 if compact else 144)
        self.page_summary.layout().setContentsMargins(12 if compact else 16, 8 if compact else 14,
                                                      12 if compact else 16, 8 if compact else 14)
        self.page_summary.layout().setSpacing(2 if compact else 8)
        self.sales_toolbar.setDirection(QBoxLayout.Direction.TopToBottom if stacked_tools
                                        else QBoxLayout.Direction.LeftToRight)
        # Do not restyle a modal editor's deliberately short item rows.
        for widget in self.stack.findChildren(QTableWidget):
            polish_table(widget)
            if hasattr(widget, "fit_columns"):
                widget.fit_columns()
        self.configure_request_columns()
        self.draft_table.set_column_layout(
            ((3, 2, 174), (1, .5, 112), (7, 3, 150), (8, 1, 108), (9, .5, 76)) if compact else
            ((3, 2, 215), (1, .5, 140), (7, 3, 235), (8, 1, 155), (9, .5, 100)))
        self.sale_table.set_column_layout(
            ((3, 2, 174), (4, .6, 116), (8, 3, 144), (9, 1, 112), (1, .5, 92), (2, .5, 106)) if compact else
            ((3, 2, 200), (4, .6, 140), (8, 3, 210), (9, 1, 145), (1, .5, 125), (2, .5, 144)))

    def run_job(self, title, work, on_success=None, refresh_on_error=True):
        starting = self.task is None
        super().run_job(title, work, on_success, refresh_on_error)
        if starting and self.workspace_ready:
            self.set_status_state("working", "처리 중")

    def set_status_state(self, state, title):
        self.status_pulse_timer.stop()
        self.status_title.setText(title)
        self.server_log_frame.setProperty("state", state)
        self.server_log_frame.setProperty("pulse", False)
        self.repaint_status_background()
        if state == "working":
            self.status_pulse_timer.start()

    def pulse_status_background(self):
        if self.server_log_frame.property("state") != "working":
            self.status_pulse_timer.stop()
            return
        self.server_log_frame.setProperty(
            "pulse", not bool(self.server_log_frame.property("pulse")))
        self.repaint_status_background()

    def repaint_status_background(self):
        self.server_log_frame.style().unpolish(self.server_log_frame)
        self.server_log_frame.style().polish(self.server_log_frame)
        self.server_log_frame.update()

    def configure_request_columns(self):
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        if QApplication.instance().property("namufarmCompactLayout"):
            minimum = (180, 124, 166, 116, 88, 158) if large else (174, 116, 156, 106, 74, 152)
        else:
            minimum = (205, 140, 190, 128, 100, 205) if not large else (210, 150, 195, 144, 112, 220)
        self.request_table.set_column_layout(tuple(
            (column, weight, width) for (column, weight), width in zip(
                ((3, 2), (4, .5), (8, 3), (1, .5), (7, .3), (5, 3)), minimum)))

    def set_page_count(self, index, count):
        self.page_counts[index] = count
        if hasattr(self, "stack") and self.stack.currentIndex() == index:
            self.update_page_title(index)

    def update_page_title(self, index):
        count = self.page_counts.get(index, 0)
        featured = index in (self.PAGE_REQUESTS, self.PAGE_QUOTES, self.PAGE_SALES)
        self.page_summary.setVisible(featured)
        self.other_page_title.setVisible(not featured)
        self.page_title.setText(self.PAGES[index][0])
        self.other_page_title.setText(self.PAGES[index][0] +
                                      (f" · {count:,}건" if index in self.page_counts else ""))
        self.page_count_number.setText(f"{count:,}")
        self.page_count_number.setProperty("longCount", count >= 100000)
        self.page_count_number.style().unpolish(self.page_count_number)
        self.page_count_number.style().polish(self.page_count_number)
        self.page_summary.setAccessibleName(f"{self.PAGES[index][0]} {count:,}건")
        self.page_count_number.setToolTip(f"현재 목록 {count:,}건")

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

    def set_sales_filter(self, value):
        self.state_filter.setCurrentText(value)


    def build_drafts(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(10)
        self.draft_search = QLineEdit()
        self.draft_search.setPlaceholderText("고객명 · 견적번호 · 연락처 · 묘목 검색")
        self.draft_search.setToolTip("견적번호·고객명·연락처·메일주소·묘목·규격·판매자 요청사항을 검색합니다.")
        self.draft_search.setClearButtonEnabled(True)
        self.draft_search.textChanged.connect(self.refresh_workspace)
        layout.addWidget(self.draft_search)
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
        self.draft_open = button("견적 결과 확인", self.open_draft, True)
        set_button_icon(self.draft_open, "open", "#ffffff")
        self.draft_selection_status = label("견적을 선택하세요.", "muted", True)
        bar.addWidget(self.draft_selection_status, 1)
        self.draft_delete = button("삭제", self.delete_selected_draft)
        self.draft_delete.setObjectName("danger")
        set_button_icon(self.draft_delete, "trash", "#a33827")
        self.draft_delete.setToolTip("이 PC의 확정견적만 삭제 · Google Sheets 견적확정 원본 유지")
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
        self.sales_toolbar = bar
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
        self.sales_upload_button = button("서버에 올리기", self.upload_sales)
        self.sales_download_button = button("서버로부터 가져오기", self.download_sales)
        self.sales_export_button = button("엑셀 파일 저장", self.export_local_sales)
        self.sales_import_button = button("엑셀 파일 불러오기", self.import_local_sales)
        self.sales_upload_button.setToolTip("로컬 변경 자료만 주문번호·변경 버전을 확인한 뒤 서버에 저장합니다.")
        self.sales_download_button.setToolTip("서버 자료를 합칩니다. 로컬 미전송 수정과 삭제 기록은 유지합니다.")
        self.sales_export_button.setToolTip("확정견적·판매관리 두 엑셀에 복원용 원본과 미전송 변경을 보관합니다.")
        self.sales_import_button.setToolTip("나피에서 내보낸 파일의 보관자료를 합칩니다. 표의 셀을 직접 수정한 내용은 반영하지 않습니다.")
        tools = QHBoxLayout()
        tools.setContentsMargins(0, 0, 0, 0)
        for widget in (self.sales_upload_button, self.sales_download_button,
                       self.sales_export_button, self.sales_import_button):
            tools.addWidget(widget)
        bar.addLayout(tools)
        self.state_filter.hide()
        layout.addLayout(bar)
        filters, self.sales_filters = self.filter_buttons(
            (("전체", "전체 상태"), ("입금확인", "입금확인"), ("배송완료", "배송완료"), ("구매취소", "구매취소")), self.set_sales_filter)
        layout.addLayout(filters)

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
        self.sale_delete_button = button("삭제", self.delete_selected_sale)
        self.sale_delete_button.setObjectName("danger")
        set_button_icon(self.sale_delete_button, "trash", "#a33827")
        bottom.addWidget(self.sale_delete_button)
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
        self.shipping_filter.addItems(["송장·출고 대기", "배송완료", "전체 배송 주문"])
        self.shipping_filter.currentIndexChanged.connect(self.refresh_shipping)
        bar.addWidget(self.shipping_search, 1)
        bar.addWidget(self.shipping_filter)
        bar.addWidget(button("로젠 기업택배 열기 ↗", lambda: QDesktopServices.openUrl(QUrl("https://logis.ilogen.com"))))
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
        self.shipping_complete = button("배송완료 처리", self.complete_shipping, True)
        self.shipping_complete.setEnabled(False)
        self.shipping_complete.setToolTip("실제 배송이 완료된 주문을 선택해 완료 처리합니다.")
        preparation = QHBoxLayout()
        preparation.addWidget(label("송장 준비", "sectionTitle"))
        preparation.addWidget(self.export_button)
        preparation.addStretch()
        preparation.addWidget(button("전체 선택", self.shipping_table.selectAll))
        preparation.addWidget(button("선택 해제", self.shipping_table.clearSelection))
        layout.addLayout(preparation)
        actions.addWidget(self.import_button)
        actions.addStretch()
        actions.addWidget(self.shipping_complete)
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
        if index in (self.PAGE_FARMS, self.PAGE_SPECIES):
            # Do not rebuild the hidden species/inventory page when opening farms.
            page = self.farms_page if index == self.PAGE_FARMS else self.species_page
            page.refresh_local()
            self.set_page_count(index, len(page.rows))
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
        if self.workspace_ready:
            self.refresh_nursery()
        self.set_status_state("error" if error else "success", "처리 실패" if error else "처리 완료")
        title = getattr(task, "operation_title", "")
        server_transfer = any(token in title for token in (
            "동기화", "서버 연결", "판매자료를 서버에 올리는", "서버 판매자료를 합치는"))
        if server_transfer:
            self._last_storage_transfer_error = str(error or "")
        if (not error and isinstance(value, dict) and "requests" in value
                and server_transfer):
            completed = timestamp(datetime.now(SEOUL).timestamp() * 1000)
            self.last_sync_label.setText("성공 · " + completed)
            self.set_status_state("success", "동기화 완료")
        if self._request_arrival_notice:
            current = getattr(self.message, "details", self.message.text()).strip()
            self.message.setText(
                self._request_arrival_notice + ("  |  " + current if current else "")
            )
            self._request_arrival_notice = ""
        self.refresh_storage_status()

    def refresh_storage_status(self):
        if not hasattr(self, "storage_status"):
            return
        pending = len(self.data.get("pending", []))
        color = "#52645c"
        if getattr(self, "demo_mode", False):
            text = "예시 자료 · 실제 저장·서버 반영은 하지 않습니다."
        elif self.manager is None:
            text = "PC 자료를 열면 저장·서버 반영 상태가 표시됩니다."
        else:
            local = "입력 중 · PC 저장 필요" if self.dirty else "PC 저장완료"
            if self.data.get("copyError"):
                local = "로컬 자료 확인 필요"
            transfer = f"서버 반영 대기 {pending:,}건"
            if self.task is not None:
                transfer += " · 처리 중"
            elif self._last_storage_transfer_error:
                transfer += " · 서버 반영 실패, 다시 시도해주세요"
            recent = self.last_sync_label.text().removeprefix("성공 · ")
            text = f"{local} · {transfer} · 최근 서버 동기화: {recent}"
            if pending or self.dirty or self.data.get("copyError") or self._last_storage_transfer_error:
                color = "#805a17"
        self.storage_status.setText(text)
        self.storage_status.setToolTip(
            "PC 저장과 서버 백업은 별도입니다. 판매자료는 ‘서버에 올리기’로 반영합니다. "
            "‘엑셀 파일 저장’은 파일 내보내기이며 서버에 전송하지 않습니다.\n" +
            (self._last_storage_transfer_error or self.data.get("copyError", "")))
        large = bool(QApplication.instance().property("namufarmLargeFont"))
        self.storage_status.setStyleSheet(f"color:{color};font-size:{16 if large else 14}px;padding:0 2px;")

    def update_controls(self):
        super().update_controls()
        if not self.workspace_ready:
            return
        ready = self.manager is not None and self.task is None
        paired = ready
        self.stack.setEnabled(self.task is None)
        for page in (self.farms_page, self.species_page):
            page.update_controls()
        self.manual_quote_button.setEnabled(ready)
        self.request_quote_button.setEnabled(ready and self.request_table.currentRow() >= 0)
        selected_draft = self.draft_table.currentRow()
        self.draft_open.setEnabled(ready and 0 <= selected_draft < len(self.draft_rows))
        self.draft_delete.setEnabled(ready and 0 <= selected_draft < len(self.draft_rows))
        if 0 <= selected_draft < len(self.draft_rows):
            draft = self.draft_rows[selected_draft]
            working = self.draft_statuses[selected_draft] == "작성중"
            self.draft_open.setText("이어서 작성" if working else "견적 결과 확인")
            self.draft_selection_status.setText(f"{draft['customerName']} · " + ("발송 전 작성중 견적" if working else "저장된 견적 결과 확인"))
        else:
            self.draft_selection_status.setText("견적을 선택하면 저장된 결과를 확인할 수 있습니다.")
        selected_shipping = self.selected_shipping()
        exportable = bool(selected_shipping) and all(
            row["metadata"]["status"] == "입금확인" and shipping_prepared(row["private"]["order"])
            and sale_number(row).startswith("SOLD-") for row in selected_shipping)
        self.export_button.setEnabled(paired and exportable)
        self.import_button.setEnabled(paired)
        self.print_button.setEnabled(ready and bool(selected_shipping)
                                     and all(shipping_ready(row["private"]["order"]) for row in selected_shipping))
        self.tracking_save.setEnabled(paired and len(selected_shipping) == 1
                                      and selected_shipping[0]["metadata"]["status"] == "입금확인")
        self.shipping_edit.setEnabled(ready and len(selected_shipping) == 1)
        self.export_button.setToolTip(
            "판매관리의 ‘서버로부터 가져오기’로 SOLD 번호를 발급받아주세요."
            if selected_shipping and any(not sale_number(row).startswith("SOLD-") for row in selected_shipping)
            else "주소를 확인한 입금확인 주문을 선택하세요. 송장 발급 전에도 로젠 XLS로 저장할 수 있습니다.")
        self.shipping_complete.setEnabled(ready and not getattr(self, "demo_mode", False)
                                          and len(selected_shipping) == 1
                                          and selected_shipping[0]["metadata"]["status"] == "입금확인"
                                          and shipping_ready(selected_shipping[0]["private"]["order"]))
        self.customer_open_button.setEnabled(ready and self.selected_sale is not None)
        self.sale_delete_button.setEnabled(ready and self.selected_sale is not None)
        self.sale_delete_button.setToolTip(
            "이 PC의 DB에서만 삭제 · 서버 원본 유지" if self.selected_sale is not None
            else "삭제할 판매 주문을 선택하세요.")
        for widget in (self.sales_upload_button, self.sales_download_button,
                       self.sales_export_button, self.sales_import_button):
            widget.setEnabled(ready and not getattr(self, "demo_mode", False))
        if getattr(self, "demo_mode", False):
            for widget in (self.sync_button, self.manual_quote_button, self.request_quote_button,
                           self.draft_open, self.draft_delete, self.customer_open_button, self.sale_delete_button,
                           self.export_button, self.import_button, self.print_button,
                           self.tracking_save, self.open_button, self.connect_button, self.report_button,
                           self.copy_button):
                widget.setEnabled(False)
        self.refresh_storage_status()

    def apply_view(self, value):
        super().apply_view(value)
        if self.workspace_ready:
            self.refresh_nursery()
            self.refresh_workspace()
            self.refresh_request_notifications()

    def refresh_nursery(self):
        for page in (self.farms_page, self.species_page):
            page.refresh_local()
        self.set_page_count(self.PAGE_FARMS, len(self.farms_page.rows))
        self.set_page_count(self.PAGE_SPECIES, len(self.species_page.rows))

    def refresh_workspace(self):
        # Confirmed quotes, like sales, are loaded from this PC's database.
        draft_pairs = [(draft, "입금확인" if draft.get("paymentRegistered") else "견적확정")
                       for draft in self.data.get("confirmedQuotes", [])]
        confirmed_ids = {d["id"] for d, _ in draft_pairs}
        draft_pairs += [(draft, "작성중") for draft in self.data.get("drafts", [])
                        if draft.get("workingCopy") and draft["id"] not in confirmed_ids]
        draft_pairs.sort(
            key=lambda pair: draft_quote_sort_key(pair[0]),
            reverse=self.draft_sort_descending,
        )
        total_quotes = len(draft_pairs)
        query = self.draft_search.text().strip().casefold()
        phone_query = query.replace("-", "").replace(" ", "")
        if query:
            draft_pairs = [(draft, status) for draft, status in draft_pairs
                           if query in " ".join((
                               draft_quote_number(draft), draft.get("customerName", ""),
                               draft.get("customerPhone", ""), draft.get("customerEmail", ""),
                               draft.get("note", ""),
                               " ".join(str(item.get(key, "")) for item in draft.get("items", [])
                                        for key in ("species", "specification", "remarks")),
                           )).casefold()
                           or (phone_query.isdigit() and phone_query in
                               draft.get("customerPhone", "").replace("-", "").replace(" ", ""))]
        previous_index = self.draft_table.currentRow()
        previous_rows = getattr(self, "draft_rows", [])
        previous_id = previous_rows[previous_index]["id"] if 0 <= previous_index < len(previous_rows) else None
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
            "작성 중" if status == "작성중" else money(draft_amounts(d)["total"]),
            "로컬 DB",
        ) for d, status in draft_pairs])
        self.draft_table.set_empty_message(
            "검색 조건에 맞는 견적이 없습니다.\n검색어를 지워 전체 목록을 확인하세요." if query else
            "표시할 견적이 없습니다.\n작성중 저장한 견적과 확정견적이 표시됩니다.")
        center_table_columns(self.draft_table, (0, 1, 2, 3, 4, 6, 8))
        customer_reference(self.draft_table, 3, 0)
        self.draft_table.clearSelection()
        self.draft_table.setCurrentCell(-1, -1)
        restored = next((i for i, draft in enumerate(self.draft_rows) if draft["id"] == previous_id), None)
        if restored is not None:
            self.draft_table.selectRow(restored)
        self.draft_table.blockSignals(False)
        self.set_page_count(self.PAGE_QUOTES, total_quotes)
        self.draft_table.horizontalHeaderItem(9).setToolTip("이 PC의 로컬 DB에서 불러온 견적")
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
                meta["orderNumber"], sale_number(row), display_status,
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
                sale_number(row),
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
        for index, row in enumerate(self.sale_rows):
            if row["metadata"]["orderNumber"].startswith("PC_"):
                # Keep the internal key for API calls, without drawing it under the name.
                cell = self.sale_table.item(index, 3)
                cell.setData(Qt.ItemDataRole.UserRole, None)
                cell.setToolTip(row["private"]["order"].get("customerName", ""))
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
            number = self.selected_sale["metadata"]["orderNumber"]
            shown_number = sale_number(self.selected_sale)
            self.customer_list_status.setText(
                (f"{shown_number} · " if shown_number else "") +
                f"{order.get('customerName','')} · "
                f"{customer_status_text(self.selected_sale['metadata']['status'])} · "
                + ("다음 작업: 배송정보 · 송장 준비" if customer_status_text(self.selected_sale['metadata']['status']) == "입금확인"
                   else "완료 내역 확인")
            )
        else:
            self.customer_list_status.setText("주문을 선택하세요.")
        self.update_controls()

    def delete_selected_sale(self):
        if getattr(self, "demo_mode", False) or not self.manager or self.task or not self.selected_sale:
            return
        row = self.selected_sale
        order, meta = row["private"]["order"], row["metadata"]
        if QMessageBox.question(
                self, "판매 주문 삭제",
                f"{order.get('customerName', '')} 고객의 주문을 삭제할까요?\n"
                "이 PC의 DB에서만 삭제합니다. 서버 원본은 유지됩니다.\n"
                "이 주문의 미전송 변경도 취소하며, 이후 가져오기에서도 다시 표시하지 않습니다.",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
            return
        self.run_job("로컬 주문 삭제 중…", lambda: self.manager.delete_sale(meta["orderNumber"], meta["revision"]),
                     self.local_sale_deleted)

    def local_sale_deleted(self, value):
        self.apply_view(value)
        self.message.setText("이 PC의 판매자료를 삭제했습니다. 서버 원본은 유지됩니다.")

    def sales_transfer_finished(self, value):
        self.apply_view(value)
        self.message.setText(value.get("transferNotice") or "판매자료 처리를 완료했습니다.")

    def upload_sales(self):
        if self.manager and not self.task and self.allow_discard():
            self.run_job("판매자료를 서버에 올리는 중…", self.manager.upload_sales, self.sales_transfer_finished)

    def download_sales(self):
        if self.manager and not self.task and self.allow_discard():
            self.run_job("서버 판매자료를 합치는 중…", self.manager.download_sales, self.sales_transfer_finished)

    def export_local_sales(self):
        if not self.manager or self.task:
            return
        folder = QFileDialog.getExistingDirectory(self, "확정견적·판매관리 엑셀을 저장할 폴더", str(self.manager.vault.path.parent))
        if not folder:
            return
        targets = [Path(folder) / name for name in ("나무팜-확정견적.xlsx", "나무팜-판매관리.xlsx")]
        if any(path.exists() for path in targets) and QMessageBox.question(
                self, "엑셀 내보내기", "선택한 폴더의 기존 확정견적·판매관리 엑셀을 현재 DB 내용으로 갱신할까요?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
            return
        self.run_job("로컬 엑셀 내보내는 중…", lambda: self.manager.export_local(folder), self.sales_transfer_finished)

    def import_local_sales(self):
        if not self.manager or self.task or not self.allow_discard():
            return
        paths, _ = QFileDialog.getOpenFileNames(self, "나피에서 내보낸 확정견적·판매관리 엑셀 선택",
                                               str(self.manager.vault.path.parent), "나피 엑셀 (*.xlsx)")
        if paths:
            self.run_job("로컬 자료를 주문번호 기준으로 합치는 중…", lambda: self.manager.import_local(paths),
                         self.sales_transfer_finished)

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
        target = {"cancel": "구매취소", "restore": "입금확인"}.get(dialog.mode)
        messages = {
            "save": "고객·배송 정보 저장 중…",
            "prepare": "주소 확인·송장 준비를 저장 중…",
            "cancel": "구매취소 상태 저장 중…",
            "restore": "입금확인 상태로 복원 중…",
        }
        self.run_job(
            messages[dialog.mode],
            lambda: self.manager.save_sale(
                meta["orderNumber"], meta["revision"], fields, target,
                prepare_shipping=dialog.mode == "prepare"
            ),
            lambda value: self.customer_order_saved(value, shipping_prepared=dialog.mode == "prepare"),
        )

    def customer_order_saved(self, value, shipping_prepared=False):
        self.apply_view(value)
        self.message.setText("주소 확인완료 · 배송·송장에서 로젠 XLS 저장 후 발급받은 송장번호를 등록하세요." if shipping_prepared else
                             "판매 · 고객 관리 내용을 로컬 DB에 저장했습니다. ‘서버에 올리기’로 백업하세요.")
        self.navigate(self.PAGE_SHIPPING if shipping_prepared else self.PAGE_SALES)

    def refresh_shipping(self):
        if not hasattr(self, "shipping_table"):
            return
        query = self.shipping_search.text().casefold()
        mode = self.shipping_filter.currentIndex()
        rows = []
        for row in self.data["sales"]:
            order = row["private"]["order"]
            if not shipping_prepared(order) or query not in str(order).casefold():
                continue
            if mode == 0 and order["status"] != "입금확인":
                continue
            if mode == 1 and order["status"] not in ("배송완료", "판매완료"):
                continue
            rows.append(row)
        self.set_page_count(self.PAGE_SHIPPING, len(rows))
        self.shipping_rows = rows
        self.shipping_table.blockSignals(True)
        self.shipping_table.set_empty_message(
            "검색·상태 조건에 맞는 배송 주문이 없습니다." if query or mode else
            "송장을 준비할 주문이 없습니다.\n판매관리에서 주소를 입력하고 ‘주소확인 · 송장 준비’를 눌러주세요. 송장번호는 나중에 등록할 수 있습니다.")
        fill_table(self.shipping_table, [(sale_number(r), r["private"]["order"].get("receiverName", ""), r["private"]["order"]["customerPhone"],
            r["private"]["order"].get("roadAddress", ""), r["metadata"]["totalQty"],
            ("출고 대기" if shipping_ready(r["private"]["order"]) else "송장 발급 대기")
            if r["metadata"]["status"] == "입금확인" else r["metadata"]["status"],
            tracking_number(r["private"]["order"]) or "발급 대기") for r in rows])
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
        guidance = "송장을 준비할 주문을 선택하세요. Ctrl·Shift로 여러 건을 선택할 수 있습니다."
        if selected:
            guidance = "로젠 XLS 저장 → 로젠에서 송장 발급 → 송장번호 반영 → 실제 배송 후 배송완료"
            if any(not sale_number(row).startswith("SOLD-") for row in selected):
                guidance = "SOLD 번호 발급 대기 · 판매관리에서 ‘서버로부터 가져오기’를 먼저 눌러주세요."
        self.shipping_status.setText(f"선택 {len(selected)}건 / 목록 {len(self.shipping_rows)}건 · " + guidance)
        self.update_controls()

    def edit_quote(self, checked=False, draft=None, request_status=""):
        if getattr(self, "demo_mode", False) or not self.manager or self.task or not self.allow_discard():
            return
        self.dirty = False
        dialog = QuoteDialog(draft or new_draft(), self, request_status=request_status, manager=self.manager)
        # Modal input is protected from the 10-minute refresh timer as well.
        was_active = self.timer.isActive()
        self.timer.stop()
        try:
            result = dialog.exec()
        finally:
            if was_active:
                self.timer.start()
        if result != QDialog.DialogCode.Accepted:
            if dialog.workbench:
                self.apply_view(self.manager.view())
            return
        draft, mode = dialog.draft, dialog.mode
        if mode == "save_manual":
            self.run_job("직접 작성한 견적을 로컬 DB에 저장 중…", lambda: self.manager.save_draft(draft),
                         lambda view: self.manual_quote_saved(view, draft["id"]))
        elif mode == "confirm":
            self.run_job("입금확인 내역을 로컬 DB에 저장 중…", lambda: self.manager.confirm_draft(draft), self.quote_confirmed)
        elif mode == "finalize":
            self.run_job("견적확정 상태를 운영 시트에 저장 중…", lambda: self.manager.confirm_quote(draft), self.quote_finalized)

    def quote_confirmed(self, value):
        self.apply_view(value)
        self.message.setText("입금확인으로 판매 등록했습니다. 배송을 준비하세요.")
        self.navigate(self.PAGE_SALES)

    def quote_finalized(self, value):
        self.apply_view(value)
        self.message.setText("견적을 확정하고 로컬 DB에 저장했습니다.")
        self.navigate(self.PAGE_QUOTES)

    def new_manual_quote(self, *args):
        self.edit_quote(draft=new_manual_draft())

    def manual_quote_saved(self, value, identifier):
        self.apply_view(value)
        self.message.setText("직접 작성한 견적을 로컬 DB에 저장했습니다.")
        self.draft_search.clear()
        self.navigate(self.PAGE_QUOTES)
        for index, draft in enumerate(self.draft_rows):
            if draft["id"] == identifier:
                self.draft_table.selectRow(index)
                break

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
        recovery = self.data.get("quoteRecovery")
        existing = recovery["draft"] if recovery and recovery["draft"]["sourceRequestId"] == request["requestId"] else next(
            (d for d in self.data.get("drafts", []) if d["sourceRequestId"] == request["requestId"]), None)
        draft = copy.deepcopy(existing) if existing else new_draft(request)
        draft["requestNote"] = request["personal"].get("message", draft.get("requestNote", ""))
        draft["requestReceiptNumber"] = request.get("receiptNumber") or request["requestId"]
        draft["customerEmail"] = request["personal"].get("customerEmail", "")
        self.edit_quote(draft=draft, request_status=status)

    def open_draft(self, *args):
        index = self.draft_table.currentRow()
        if not 0 <= index < len(self.draft_rows):
            return
        # Preserve the local confirmed snapshot, including its original recipient.
        draft = copy.deepcopy(self.draft_rows[index])
        status = self.draft_statuses[index] if index < len(self.draft_statuses) else ""
        if status == "작성중":
            self.edit_quote(draft=draft, request_status="신규")
        else:
            self.open_delivery_quote(draft, status)

    def delete_selected_draft(self):
        if getattr(self, "demo_mode", False) or not self.manager or self.task:
            return
        index = self.draft_table.currentRow()
        if not 0 <= index < len(self.draft_rows):
            return
        draft = self.draft_rows[index]
        if QMessageBox.question(
                self, "PC 견적 삭제",
                f"{draft_quote_number(draft)} · {draft['customerName']} 견적을 이 PC에서 삭제할까요?\n"
                "작성중 초안 또는 이 PC의 확정견적에서만 삭제합니다.\n"
                "Google Sheets의 견적확정 시트와 판매관리 자료는 그대로 유지합니다.",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
            return
        identifier = draft["id"]
        self.run_job("PC 확정견적 삭제 중…", lambda: self.manager.delete_draft(identifier), self.local_quote_deleted)

    def local_quote_deleted(self, value):
        self.apply_view(value)
        self.message.setText("이 PC의 확정견적을 삭제했습니다. Google Sheets 원본은 유지됩니다.")

    def open_delivery_quote(self, draft, status):
        if getattr(self, "demo_mode", False) or not self.manager or self.task:
            return
        delivery_state = self.manager.quote_delivery_state(draft["id"])
        sale = next((row for row in self.data.get("sales", [])
                     if row["metadata"]["orderNumber"] == "PC_" + draft["id"] or
                     row["private"]["order"].get("sourceDraftId") == draft["id"] or
                     (draft.get("sourceRequestId") and
                      row["private"]["order"].get("sourceRequestId") == draft["sourceRequestId"])), None)
        dialog = QuoteDeliveryDialog(draft, status, manager=self.manager, delivery_state=delivery_state,
                                     parent=self, payment_order=sale["private"]["order"] if sale else None)
        was_active = self.timer.isActive()
        self.timer.stop()
        try:
            result = dialog.exec()
        finally:
            if was_active:
                self.timer.start()
        if result == QDialog.DialogCode.Accepted and dialog.payment_view is not None:
            self.quote_confirmed(dialog.payment_view)
        elif dialog.local_changed:
            try:
                self.apply_view(self.manager.view())
            except UserError as error:
                self.message.setText(str(error))
        dialog.deleteLater()

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

    def complete_shipping(self):
        if not self.manager or self.task or getattr(self, "demo_mode", False) or not self.allow_discard():
            return
        rows = self.selected_shipping()
        if len(rows) != 1:
            QMessageBox.information(self, "주문 선택", "배송완료 처리할 주문을 한 건 선택해주세요.")
            return
        row = rows[0]
        if row["metadata"]["status"] != "입금확인" or not shipping_ready(row["private"]["order"]):
            QMessageBox.information(self, "배송 준비", "배송준비완료된 입금확인 주문만 배송완료로 처리할 수 있습니다.")
            return
        answer = QMessageBox.question(
            self, "배송완료", f"{sale_number(row)} · {row['private']['order'].get('receiverName', '')}\n실제 배송이 완료됐나요?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No)
        if answer != QMessageBox.StandardButton.Yes:
            return
        meta = row["metadata"]
        self.run_job("배송완료 처리 중…",
                     lambda: self.manager.save_sale(meta["orderNumber"], meta["revision"], {}, "배송완료"),
                     self.shipping_completed)

    def shipping_completed(self, value):
        self.apply_view(value)
        self.message.setText("배송완료 상태를 로컬 DB에 저장했습니다. ‘서버에 올리기’로 백업하세요.")

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
        self.message.setText("송장번호를 로컬 DB에 저장했습니다. ‘서버에 올리기’로 백업하세요.")

    def export_shipping(self):
        if not self.manager or self.task or getattr(self, "demo_mode", False) or not self.allow_discard():
            return
        try:
            numbers = [r["metadata"]["orderNumber"] for r in export_orders(self.selected_shipping())]
            self.run_job("로컬 출고자료 확인 중…", self.manager.view,
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
            print_document(self, shipping_html(self.selected_shipping()), "나무팜_송장번호_출고확인서")
        except UserError as error:
            QMessageBox.warning(self, "출력 확인", str(error))

    def print_saved_quote(self):
        if self.selected_sale and self.selected_sale["private"].get("quoteDocumentJson"):
            doc = json.loads(self.selected_sale["private"]["quoteDocumentJson"])
            print_document(self, quote_document_html(doc), "나무팜_" + doc["number"])

    def accept_manager(self, result):
        super().accept_manager(result)
        if self.workspace_ready:
            self.navigate(self.PAGE_REQUESTS)

    def manual_sync(self):
        if getattr(self, "demo_mode", False) or not self.manager or not self.allow_discard():
            return
        if self.stack.currentIndex() in (self.PAGE_FARMS, self.PAGE_SPECIES):
            self.stack.currentWidget().sync()
            return
        if self.stack.currentIndex() in (self.PAGE_SALES, self.PAGE_SHIPPING):
            self.download_sales()
            return
        include_sales = self.stack.currentIndex() in (self.PAGE_SALES, self.PAGE_SHIPPING, self.PAGE_REPORTS)
        self.run_job("SYNC  >  서버 동기화 중…",
                     lambda: self.manager.connect(include_sales=include_sales))

    def auto_sync(self):
        if self.stack.currentIndex() in (self.PAGE_FARMS, self.PAGE_SPECIES, self.PAGE_SALES, self.PAGE_SHIPPING, self.PAGE_REPORTS):
            return
        if self.manager and self.data["paired"] and self.auto_check.isChecked() and not self.task and not self.dirty and not self.conflict:
            pull = self.manager.sync if self.stack.currentIndex() in (self.PAGE_SALES, self.PAGE_SHIPPING, self.PAGE_REPORTS) else self.manager.sync_requests
            self.run_job("AUTO SYNC  >  서버 동기화 중…", pull)







