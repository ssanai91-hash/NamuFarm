"""FHD seller workspace, built on the existing verified sync controller."""
import copy
import json
import tempfile
from datetime import datetime
from importlib.resources import files as package_files
from pathlib import Path
from html import escape as html_escape

from PySide6.QtCore import Qt, QUrl, QSaveFile, QIODevice, QTimer, Signal, Slot, QRectF
from PySide6.QtGui import (QColor, QDesktopServices, QFont, QFontMetricsF, QImage, QIntValidator,
    QKeySequence, QPainter, QPdfWriter, QPen, QPixmap, QShortcut, QTextDocument, QPageSize)
from PySide6.QtPrintSupport import QPrinter, QPrintDialog
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QDialog, QDoubleSpinBox, QFileDialog, QFormLayout,
    QFrame, QGridLayout, QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QListWidget,
    QMessageBox, QPlainTextEdit, QPushButton, QRadioButton, QScrollArea, QSplitter, QStackedWidget,
    QStyle, QSystemTrayIcon, QTableWidgetItem, QTextBrowser, QVBoxLayout, QWidget, QSpinBox)

from . import __version__
from .app import MainWindow, Task, table, fill_table, money, timestamp
from .commerce import (ISSUER, new_draft, new_manual_draft, draft_amounts, validate_draft, quote_html, quote_document_html, shipping_html,
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
#sidebar QPushButton:pressed{background:#1d4939;color:#fff;padding:15px 17px 13px 19px;}
#sidebar QWidget#navRow{background:transparent;}
#sidebar QLabel#navBadge{
    background:#9cf36f;
    color:#12351e;
    border:1px solid #c2ffa0;
    border-radius:10px;
    min-width:22px;
    max-height:20px;
    padding:1px 6px;
    font-family:'Consolas','Malgun Gothic',monospace;
    font-size:11px;
    font-weight:900;
    qproperty-alignment:AlignCenter;
}
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

QFrame#serverLogFrame{
    background:#070b08;
    border:1px solid #31573a;
    border-radius:0;
    min-height:44px;
}
QLabel#serverLogTitle{
    background:#101812;
    color:#72cf6e;
    border:0;
    border-right:1px solid #31573a;
    border-radius:0;
    padding:0 10px;
    font-family:'Consolas','Malgun Gothic',monospace;
    font-size:10px;
    font-weight:900;
}
QLabel#serverLogText{
    background:transparent;
    color:#a7ff8b;
    border:0;
    border-radius:0;
    padding:0 12px;
    font-family:'Consolas','Malgun Gothic',monospace;
    font-size:12px;
    font-weight:800;
}
QPushButton#syncPrimary{
    min-height:42px;
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #2d8054,stop:1 #1b633f);
    color:#ffffff;
    border:1px solid #155333;
    border-bottom:4px solid #0b3b23;
    border-radius:7px;
    padding:8px 17px 9px 17px;
    font-weight:900;
}
QPushButton#syncPrimary:hover{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #34915f,stop:1 #207149);
    border-color:#174f33;
    border-bottom-color:#082f1b;
}
QPushButton#syncPrimary:pressed{
    background:#164f33;
    border:1px solid #0f472b;
    border-top:4px solid #082f1b;
    border-bottom:1px solid #2d8054;
    padding:11px 17px 6px 17px;
}
QPushButton#syncPrimary:disabled{
    background:#a3b4a7;
    color:#edf2ed;
    border:1px solid #98aa9c;
    border-bottom:2px solid #879a8b;
}
QPushButton{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #ffffff,stop:1 #f4f8f3);
    border:1px solid #c9d6cb;
    border-bottom:3px solid #aebfb2;
    border-radius:8px;
    padding:8px 15px 9px 15px;
    font-weight:700;
}
QPushButton:hover{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #ffffff,stop:1 #e8f1e6);
    border-color:#94ad99;
    border-bottom:3px solid #78967f;
}
QPushButton:pressed{
    background:#d8e6d6;
    border:1px solid #8da392;
    border-top:3px solid #708c76;
    border-bottom:1px solid #a8b8aa;
    padding:10px 15px 7px 15px;
}
QPushButton:disabled{
    background:#ecefea;
    color:#9aa69d;
    border:1px solid #e1e6df;
    border-bottom:2px solid #d9dfd9;
}
QPushButton#primary{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #347756,stop:1 #286547);
    color:#fff;
    border:1px solid #235b40;
    border-bottom:3px solid #16442e;
}
QPushButton#primary:hover{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #3b805e,stop:1 #1f5a3d);
    border-color:#1b5036;
    border-bottom-color:#103823;
}
QPushButton#primary:pressed{
    background:#1b5036;
    border:1px solid #16442e;
    border-top:3px solid #0f3522;
    border-bottom:1px solid #286547;
    padding:10px 15px 7px 15px;
}
QPushButton#primary:disabled{
    background:#a3b7a7;
    border:1px solid #a3b7a7;
    border-bottom:2px solid #91a796;
    color:#eef2ed;
}
QPushButton#danger{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #fffdfc,stop:1 #fbeeea);
    color:#9f4935;
    border:1px solid #e6b9ad;
    border-bottom:3px solid #c98c7d;
}
QPushButton#danger:hover{
    background:#f8e4de;
    color:#8e3827;
    border-color:#d79c8d;
    border-bottom-color:#b56f5d;
}
QPushButton#danger:pressed{
    background:#f1d4cc;
    border:1px solid #c98675;
    border-top:3px solid #ae6756;
    border-bottom:1px solid #d9a397;
    padding:10px 15px 7px 15px;
}
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
QRadioButton{spacing:8px;padding:4px 8px;font-weight:600;}
QRadioButton::indicator{width:18px;height:18px;}
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

QFrame#deliveryShippingCard{
    background:#f7faf6;
    border:1px solid #d5e2d4;
    border-radius:10px;
}
QLabel#deliveryShippingTitle{
    color:#244f36;
    font-size:15px;
    font-weight:800;
}
QLabel#deliveryShippingStatus{
    color:#607467;
    font-size:12px;
    font-weight:600;
}
QLineEdit#deliveryShippingFee{
    min-width:110px;
    max-width:150px;
    font-weight:700;
    text-align:right;
}
QLineEdit#deliveryShippingFee:disabled{
    background:#edf0ec;
    color:#9aa49c;
    border-color:#dde3dc;
}
QLabel#deliveryShippingFeeLabel:disabled{
    color:#a0aaa2;
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
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #4a8a5d,stop:1 #3e7a50);
    color:#ffffff;
    border:1px solid #356d46;
    border-bottom:3px solid #285a38;
    border-radius:8px;
    padding:10px 20px 11px 20px;
    font-weight:800;
}

QPushButton#smsAction:hover{
    background:#357048;
    border-color:#2d623e;
    border-bottom-color:#214d30;
}
QPushButton#smsAction:pressed{
    background:#2b5e3c;
    border:1px solid #285a38;
    border-top:3px solid #1f492e;
    border-bottom:1px solid #3e7a50;
    padding:12px 20px 9px 20px;
}

QPushButton#mailAction{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #527faa,stop:1 #416f9d);
    color:#ffffff;
    border:1px solid #39658f;
    border-bottom:3px solid #2c5278;
    border-radius:8px;
    padding:10px 20px 11px 20px;
    font-weight:800;
}

QPushButton#mailAction:hover{
    background:#38658f;
    border-color:#315a81;
    border-bottom-color:#24496d;
}
QPushButton#mailAction:pressed{
    background:#2f567d;
    border:1px solid #2c5278;
    border-top:3px solid #214361;
    border-bottom:1px solid #416f9d;
    padding:12px 20px 9px 20px;
}

QPushButton#pdfAction{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #ffffff,stop:1 #f0f5ef);
    color:#3c5546;
    border:1px solid #9caf9f;
    border-bottom:3px solid #7f9783;
    border-radius:8px;
    padding:10px 20px 11px 20px;
    font-weight:800;
}

QPushButton#pdfAction:hover{
    background:#e8f0e6;
    border-color:#8ca191;
    border-bottom-color:#6f8974;
}
QPushButton#pdfAction:pressed{
    background:#dce8da;
    border:1px solid #829987;
    border-top:3px solid #6b846f;
    border-bottom:1px solid #9caf9f;
    padding:12px 20px 9px 20px;
}
QLabel#quoteFileName{
    background:#101512;
    color:#8fe3a8;
    border:1px solid #29372f;
    border-radius:6px;
    font-family:'Consolas','Malgun Gothic',monospace;
    font-size:12px;
    font-weight:700;
    padding:8px 11px;
}

QPushButton#closeAction{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #ffffff,stop:1 #f3f5f2);
    color:#657269;
    border:1px solid #c4cec6;
    border-bottom:3px solid #aeb9b0;
    border-radius:8px;
    padding:10px 20px 11px 20px;
    font-weight:700;
}
QPushButton#closeAction:hover{
    background:#edf1ec;
    border-color:#adb9af;
    border-bottom-color:#929f95;
}
QPushButton#closeAction:pressed{
    background:#e0e6df;
    border:1px solid #a6b2a8;
    border-top:3px solid #8e9c91;
    border-bottom:1px solid #c4cec6;
    padding:12px 20px 9px 20px;
}

QPushButton#sentAction{
    background:qlineargradient(x1:0,y1:0,x2:0,y2:1,stop:0 #26724d,stop:1 #185d3c);
    color:#ffffff;
    border:1px solid #165637;
    border-bottom:3px solid #0d4229;
    border-radius:8px;
    padding:11px 24px 12px 24px;
    font-weight:900;
}

QPushButton#sentAction:hover{
    background:#145438;
    border-color:#104a2f;
    border-bottom-color:#0a3823;
}
QPushButton#sentAction:pressed{
    background:#0f472e;
    border:1px solid #0d4229;
    border-top:3px solid #08351f;
    border-bottom:1px solid #185d3c;
    padding:13px 24px 10px 24px;
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
QLabel#channelStatusBadge[state="pending"]{
    background:#fff3d8;
    color:#8a641d;
    border:1px solid #e5cf98;
}
QLabel#channelStatusBadge[state="failed"]{
    background:#fbe5e2;
    color:#a13d32;
    border:1px solid #e6b8b1;
}
QLabel#channelStatusBadge[state="success"]{
    background:#dcebdd;
    color:#23603b;
    border:1px solid #b8d2bd;
}

QDialog#customerOrderDialog{background:#f4f7f3;}
QLabel#customerStatusBadge{
    background:#dcebdd;
    color:#23593a;
    border:1px solid #bdd2bd;
    border-radius:14px;
    padding:6px 14px;
    font-weight:800;
}
QFrame#customerSummaryCard,QFrame#customerInfoCard,QFrame#customerFinanceCard{
    background:#ffffff;
    border:1px solid #d9e4da;
    border-radius:11px;
}
QLabel#customerMetricCaption{color:#7b8b80;font-size:11px;font-weight:600;}
QLabel#customerMetricValue{color:#183d2d;font-size:15px;font-weight:800;}
QLabel#customerAmountValue{color:#174c31;font-size:25px;font-weight:900;}

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
QPushButton#emailSendPrimary{
    border-bottom:3px solid #1d466c;
    padding:10px 24px 11px 24px;
}
QPushButton#emailSendPrimary:hover{background:#275680;border-color:#275680;border-bottom-color:#173b5c;}
QPushButton#emailSendPrimary:pressed{
    background:#214d74;
    border:1px solid #1d466c;
    border-top:3px solid #153851;
    border-bottom:1px solid #2f6597;
    padding:12px 24px 9px 24px;
}
QPushButton#emailSendPrimary:disabled{background:#aab8c6;border:1px solid #aab8c6;border-bottom:2px solid #95a5b4;color:#eef2f5;}

QDialog#quoteSmsDialog{background:#f4f7f3;}
QLabel#smsSendStatus{
    background:#edf4ea;
    color:#47624e;
    border:1px solid #d5e2d2;
    border-radius:8px;
    padding:9px 11px;
}
QPushButton#smsSendPrimary{
    background:#3e7a50;
    color:#ffffff;
    border:1px solid #3e7a50;
    border-radius:8px;
    padding:11px 24px;
    font-weight:800;
}
QPushButton#smsSendPrimary{
    border-bottom:3px solid #285a38;
    padding:10px 24px 11px 24px;
}
QPushButton#smsSendPrimary:hover{background:#316640;border-color:#316640;border-bottom-color:#214d30;}
QPushButton#smsSendPrimary:pressed{
    background:#2a5b39;
    border:1px solid #285a38;
    border-top:3px solid #1f492e;
    border-bottom:1px solid #3e7a50;
    padding:12px 24px 9px 24px;
}
QPushButton#smsSendPrimary:disabled{background:#a9baad;border:1px solid #a9baad;border-bottom:2px solid #92a596;color:#eef2ed;}

QPushButton#smsAction:disabled,QPushButton#mailAction:disabled,QPushButton#pdfAction:disabled,QPushButton#closeAction:disabled{
    background:#e9ede8;
    color:#98a29a;
    border:1px solid #d8ded8;
    border-bottom:2px solid #cbd3cc;
}
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


class ManualQuoteDialog(QDialog):
    """Quick phone quote intake: customer basics + manually typed seedlings."""

    def __init__(self, draft=None, parent=None):
        super().__init__(parent)
        self.draft = copy.deepcopy(draft or new_manual_draft())
        self.setObjectName("manualQuoteDialog")
        self.setWindowTitle("전화 견적 추가")
        self.resize(980, 720)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        layout.setSpacing(12)
        layout.addWidget(label("전화 견적 추가", "deliveryTitle"))
        layout.addWidget(label(
            "전화로 받은 고객 정보와 묘목·수량·판매단가를 입력하면 견적 작업대에 바로 추가됩니다.",
            "deliverySubtitle", True
        ))

        info_card = QFrame()
        info_card.setObjectName("emailAddressCard")
        form = QGridLayout(info_card)
        form.setContentsMargins(16, 14, 16, 14)
        form.setHorizontalSpacing(12)
        form.setVerticalSpacing(9)

        self.quote_number = QLineEdit(self.draft["requestReceiptNumber"])
        self.quote_number.setReadOnly(True)
        self.customer_name = QLineEdit(self.draft.get("customerName", ""))
        self.customer_name.setPlaceholderText("고객명")
        self.customer_phone = QLineEdit(self.draft.get("customerPhone", ""))
        self.customer_phone.setPlaceholderText("01012345678")
        self.customer_email = QLineEdit(self.draft.get("customerEmail", ""))
        self.customer_email.setPlaceholderText("선택 입력")
        self.region = QLineEdit(self.draft.get("requestRegion", ""))
        self.region.setPlaceholderText("선택 입력")

        rows = (
            ("견적번호", self.quote_number, "고객명", self.customer_name),
            ("전화번호", self.customer_phone, "이메일", self.customer_email),
            ("지역", self.region, "", None),
        )
        for r, (left_title, left, right_title, right) in enumerate(rows):
            form.addWidget(label(left_title), r, 0)
            form.addWidget(left, r, 1)
            if right is not None:
                form.addWidget(label(right_title), r, 2)
                form.addWidget(right, r, 3)
        form.setColumnStretch(1, 1)
        form.setColumnStretch(3, 1)
        layout.addWidget(info_card)

        item_title = QHBoxLayout()
        item_title.addWidget(label("묘목 목록", "sectionTitle"))
        item_title.addStretch()
        item_title.addWidget(button("+ 묘목 추가", self.add_item))
        item_title.addWidget(button("선택 삭제", self.remove_item))
        layout.addLayout(item_title)

        self.items = table(["묘목명", "수량", "판매 단가", "판매 금액"])
        self.items.setEditTriggers(
            self.items.EditTrigger.DoubleClicked
            | self.items.EditTrigger.EditKeyPressed
            | self.items.EditTrigger.AnyKeyPressed
        )
        self.items.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        polish_table(self.items)
        self.items.itemChanged.connect(self.recalculate)
        layout.addWidget(self.items, 1)

        self.total = label("합계 0원", "metricValue")
        layout.addWidget(self.total)

        layout.addWidget(label("요청사항 / 메모", "sectionTitle"))
        self.note = QPlainTextEdit(self.draft.get("note", ""))
        self.note.setMaximumHeight(90)
        self.note.setPlaceholderText("통화 중 확인한 요청사항이 있으면 입력")
        layout.addWidget(self.note)

        self.formal_quote = QCheckBox("공식 견적서 요청")
        self.formal_quote.setChecked(bool(self.draft.get("formalQuoteRequested")))
        layout.addWidget(self.formal_quote)

        actions = QHBoxLayout()
        actions.addStretch()
        actions.addWidget(button("취소", self.reject))
        actions.addWidget(button("견적 추가", self.accept_quote, True))
        layout.addLayout(actions)

        for item in self.draft.get("items", []):
            self.add_item(item)
        if self.items.rowCount() == 0:
            self.add_item()

    def add_item(self, item=None):
        item = item if isinstance(item, dict) else {
            "species": "", "quantity": 1, "unitPrice": 0
        }
        row = self.items.rowCount()
        self.items.blockSignals(True)
        self.items.insertRow(row)
        for column, value in enumerate((
            item.get("species", ""),
            item.get("quantity", 1),
            item.get("unitPrice", 0),
        )):
            cell = QTableWidgetItem(str(value))
            if column > 0:
                cell.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            self.items.setItem(row, column, cell)
        amount = QTableWidgetItem("")
        amount.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
        amount.setFlags(amount.flags() & ~Qt.ItemFlag.ItemIsEditable)
        self.items.setItem(row, 3, amount)
        self.items.blockSignals(False)
        self.recalculate()

    def remove_item(self):
        row = self.items.currentRow()
        if row >= 0:
            self.items.removeRow(row)
            self.recalculate()

    def recalculate(self, *args):
        total = 0
        valid = self.items.rowCount() > 0
        self.items.blockSignals(True)
        try:
            for row in range(self.items.rowCount()):
                qty = self.items.item(row, 1).text().strip()
                price = self.items.item(row, 2).text().strip()
                if not (qty.isascii() and qty.isdigit() and price.isascii() and price.isdigit()):
                    valid = False
                    self.items.item(row, 3).setText("확인")
                    continue
                amount = int(qty) * int(price)
                total += amount
                self.items.item(row, 3).setText(f"{amount:,}")
        finally:
            self.items.blockSignals(False)
        self.total.setText(f"합계 {total:,}원" if valid else "수량 · 판매 단가를 확인해주세요")

    def collect(self):
        draft = copy.deepcopy(self.draft)
        name = self.customer_name.text().strip()
        phone = "".join(c for c in self.customer_phone.text() if c.isdigit())
        email = self.customer_email.text().strip()
        draft.update(
            customerName=name,
            customerPhone=phone,
            customerEmail=email,
            receiverName=name,
            requestRegion=self.region.text().strip(),
            note=self.note.toPlainText().strip(),
            formalQuoteRequested=self.formal_quote.isChecked(),
            quoteRecipientName=name,
            quoteReplyChannel="sms",
            shippingFee=0,
            isPrepaid=False,
            taxAmount=0,
            costConfirmed=False,
            manualStatus="견적확정",
            items=[],
        )
        for row in range(self.items.rowCount()):
            species = self.items.item(row, 0).text().strip()
            qty = self.items.item(row, 1).text().strip()
            price = self.items.item(row, 2).text().strip()
            require(qty.isascii() and qty.isdigit() and price.isascii() and price.isdigit(),
                    f"{row+1}번째 묘목 수량·판매 단가를 숫자로 입력해주세요.")
            draft["items"].append({
                "species": species,
                "specification": "",
                "quantity": int(qty),
                "unitPrice": int(price),
                "unitCost": 0,
            })
        return validate_draft(draft)

    def accept_quote(self):
        try:
            self.draft = self.collect()
            self.accept()
        except UserError as error:
            QMessageBox.warning(self, "전화 견적 확인", str(error))


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
            self.note = QPlainTextEdit(draft.get("note", ""))
            self.note.setReadOnly(True)
            self.note.setMinimumHeight(82)
            self.note.setMaximumHeight(110)
            form.addRow(label("고객이 요청한 품목과 수량을 확인하고 판매 단가를 입력하세요.", "muted", True))
            split.addWidget(info)
            self.cost = QCheckBox()
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
            draft = self.collect()
            if mode == "finalize" and QMessageBox.question(self, "견적 확정", "판매 단가와 합계를 확인했나요?\n확정하면 온라인 요청 상태가 '견적확정'으로 바뀌고 견적 작업대로 이동합니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
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
        visible_rows = min(max(len(self.draft.get("items", [])), 3), 8)
        self.items.setMinimumHeight(62 + visible_rows * 54)
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

        actions.addWidget(self.sms_button)
        actions.addWidget(self.email_button)
        actions.addWidget(self.generate_quote_button)
        actions.addWidget(self.preview_quote_button)
        actions.addWidget(self.quote_file_label)

        actions.addStretch()

        actions.addWidget(close_button)
        actions.addWidget(self.sent_button)
        actions.addWidget(self.payment_button)

        layout.addLayout(actions)

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
        layout.setContentsMargins(26, 22, 26, 22)
        layout.setSpacing(14)

        header = QHBoxLayout()
        title_box = QVBoxLayout()
        title_box.addWidget(label("고객 관리", "deliveryTitle"))
        title_box.addWidget(label("입금확인 이후 고객정보 · 배송정보 · 원가를 확인하고 다음 업무를 처리합니다.",
                                  "deliverySubtitle", True))
        header.addLayout(title_box)
        header.addStretch()
        status = QLabel(customer_status_text(self.meta["status"]))
        status.setObjectName("customerStatusBadge")
        header.addWidget(status, 0, Qt.AlignmentFlag.AlignTop)
        layout.addLayout(header)

        summary = QFrame()
        summary.setObjectName("customerSummaryCard")
        metrics = QGridLayout(summary)
        metrics.setContentsMargins(18, 13, 18, 13)
        metrics.setHorizontalSpacing(24)
        metrics.setVerticalSpacing(5)
        item_names = ", ".join(i["species"] for i in self.meta.get("items", [])) or "—"
        tracking = tracking_number(self.order) or "미등록"
        payment = self.meta["totalPrice"] + self.meta.get("taxAmount", 0)
        summary_values = (
            ("주문번호", self.meta["orderNumber"]),
            ("주문일", timestamp(self.meta["orderDate"])),
            ("입금일", timestamp(self.meta["paidAt"])),
            ("송장번호", tracking),
            ("품목", item_names),
        )
        for column, (caption, value) in enumerate(summary_values):
            metrics.addWidget(label(caption, "customerMetricCaption"), 0, column)
            metrics.addWidget(label(value, "customerMetricValue"), 1, column)
        metrics.addWidget(label("결제금액", "customerMetricCaption"), 0, len(summary_values))
        amount_label = label(money(payment), "customerAmountValue")
        metrics.addWidget(amount_label, 1, len(summary_values))
        layout.addWidget(summary)

        body = QHBoxLayout()
        body.setSpacing(16)

        info_card = QFrame()
        info_card.setObjectName("customerInfoCard")
        info_layout = QVBoxLayout(info_card)
        info_layout.setContentsMargins(18, 16, 18, 16)
        info_layout.addWidget(label("고객 · 배송 정보", "sectionTitle"))
        form = QGridLayout()
        form.setHorizontalSpacing(12)
        form.setVerticalSpacing(9)
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
            edit.setMaxLength({"customerEmail":254, "roadAddress":300, "detailAddress":300,
                               "receiverName":100, "customerName":50, "customerPhone":20, "zipNo":10}.get(name, 300))
            if name == "customerPhone" and self.row["private"].get("quoteDocumentJson"):
                edit.setReadOnly(True)
                edit.setToolTip("확정 견적서와 연결된 고객 전화번호는 유지합니다.")
            self.customer_fields[name] = edit
            r = index
            form.addWidget(label(caption), r, 0)
            form.addWidget(edit, r, 1)
        form.setColumnStretch(1, 1)
        info_layout.addLayout(form)

        info_layout.addWidget(label("배송 메모", "customerMetricCaption"))
        self.note = QPlainTextEdit(str(self.order.get("note", "") or ""))
        self.note.setMaximumHeight(100)
        info_layout.addWidget(self.note)
        body.addWidget(info_card, 3)

        finance_card = QFrame()
        finance_card.setObjectName("customerFinanceCard")
        finance_layout = QVBoxLayout(finance_card)
        finance_layout.setContentsMargins(18, 16, 18, 16)
        finance_layout.addWidget(label("판매 · 원가 확인", "sectionTitle"))

        finance_form = QFormLayout()
        self.profit_mode = QComboBox()
        self.profit_mode.addItem("매입 후 판매", "resale")
        self.profit_mode.addItem("중개 수수료", "commission")
        self.profit_mode.setCurrentIndex(1 if self.order.get("profitMode") == "commission" else 0)
        finance_form.addRow("수익 방식", self.profit_mode)

        self.commission = QDoubleSpinBox()
        self.commission.setRange(0, 100)
        self.commission.setDecimals(2)
        self.commission.setSuffix(" %")
        self.commission.setValue(self.order.get("commissionBps", 3000) / 100)
        finance_form.addRow("수수료율", self.commission)

        self.cost_total = QLineEdit(str(int(self.order.get("costTotal", 0) or 0)))
        self.cost_total.setValidator(QIntValidator(0, 999999999, self.cost_total))
        finance_form.addRow("원가 합계 (원)", self.cost_total)

        self.expense_total = QLineEdit(str(int(self.order.get("expenseTotal", 0) or 0)))
        self.expense_total.setValidator(QIntValidator(0, 999999999, self.expense_total))
        finance_form.addRow("기타 비용 (원)", self.expense_total)

        shipping_text = (
            f"선불 {int(self.order.get('shippingFee', 0) or 0):,}원"
            if self.order.get("isPrepaid")
            else f"후불 · 선불 기준 {int(self.order.get('shippingFee', 0) or 0):,}원"
        )
        finance_form.addRow("택배비", label(shipping_text, "customerMetricValue"))
        finance_layout.addLayout(finance_form)

        self.cost_confirmed = QCheckBox("원가와 비용을 확인했습니다")
        self.cost_confirmed.setChecked(bool(self.order.get("costConfirmed")))
        finance_layout.addWidget(self.cost_confirmed)

        profit_value = label(money(profit(self.meta)), "customerAmountValue")
        finance_layout.addWidget(label("현재 저장 기준 매출이익", "customerMetricCaption"))
        finance_layout.addWidget(profit_value)

        self.quote_print = button("확정 견적서 출력", self.print_quote)
        self.quote_print.setEnabled(bool(self.row["private"].get("quoteDocumentJson")))
        finance_layout.addWidget(self.quote_print)
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
        self.items.setMinimumHeight(min(280, 62 + max(1, len(self.meta.get("items", []))) * 45))
        layout.addWidget(self.items, 1)

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
        actions.addStretch()
        actions.addWidget(close_button)
        actions.addWidget(self.save_button)
        actions.addWidget(self.delivery_button)
        layout.addLayout(actions)

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
        self._request_arrival_notice = ""
        self.request_unread_count = 0
        self.request_nav_badge = None
        self.tray_icon = None
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
            if index == 1:
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
        self.global_search.setFixedWidth(230)
        self.global_search.returnPressed.connect(self.search_all)
        header.addWidget(self.global_search)

        self.sync_button.setText("↻  서버 동기화")
        self.sync_button.setObjectName("syncPrimary")
        self.sync_button.setMinimumWidth(138)
        header.addWidget(self.sync_button)

        self.server_log_frame = QFrame()
        self.server_log_frame.setObjectName("serverLogFrame")
        self.server_log_frame.setMinimumWidth(330)
        self.server_log_frame.setMaximumWidth(480)
        log_layout = QHBoxLayout(self.server_log_frame)
        log_layout.setContentsMargins(0, 0, 0, 0)
        log_layout.setSpacing(0)
        log_title = QLabel("SERVER LOG")
        log_title.setObjectName("serverLogTitle")
        log_title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        log_layout.addWidget(log_title)
        self.message.setObjectName("serverLogText")
        self.message.setWordWrap(False)
        self.message.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        self.message.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        log_layout.addWidget(self.message, 1)
        header.addWidget(self.server_log_frame, 1)

        self.new_quote_button = button("+ 전화 견적", self.add_manual_quote, True)
        header.addWidget(self.new_quote_button)
        content_layout.addLayout(header)
        self.stack = QStackedWidget()
        self.stack.addWidget(self.build_dashboard())
        self.stack.addWidget(old_pages[0])
        self.stack.addWidget(self.build_drafts())
        self.stack.addWidget(self.build_customer_management())
        self.stack.addWidget(self.build_shipping())
        self.stack.addWidget(old_pages[2])
        self.stack.addWidget(old_pages[3])
        content_layout.addWidget(self.stack, 1)
        content_layout.addWidget(self.summary)
        outer.addWidget(content, 1)
        old_root.deleteLater()
        self.manual_quote_button = button("전화 견적 추가", self.add_manual_quote, True)
        self.request_quote_button = button("견적 작성", self.quote_request, True)
        self.request_table.itemDoubleClicked.connect(self.quote_request)
        self.request_actions.addWidget(self.manual_quote_button)
        self.request_actions.addWidget(self.request_quote_button)
        for item in old_pages[0].findChildren(QLabel):
            if "수동 견적" in item.text():
                item.setText("요청을 더블클릭해 견적을 작성하세요. 견적확정 후 견적 작업대에서 고객 전달을 진행합니다.")
        for widget in self.findChildren(type(self.request_table)):
            polish_table(widget)
        self.save_button.setObjectName("primary")
        self.next_button.setObjectName("primary")
        # 판매·고객 관리는 목록 + 전용 팝업으로 동작합니다. 기존 내장 편집기는
        # MainWindow 호환을 위해 객체만 유지하고 실제 작업 화면에는 노출하지 않습니다.
        self.delete_button.setObjectName("danger")

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
        self.navigate(0)
        QShortcut(QKeySequence("Ctrl+K"), self, activated=self.global_search.setFocus)
        QShortcut(QKeySequence("Ctrl+N"), self, activated=self.add_manual_quote)
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
        layout.addWidget(label("견적확정 → 고객 전달 → 견적발송완료까지 이 작업대에서 관리합니다. 이후 실제 입금을 확인하면 입금확인으로 판매 · 고객 관리에 이동합니다.", "muted", True))
        self.draft_table = table(["견적번호", "상태", "작성일", "고객명", "연락처", "메일", "공식견적서", "품목", "판매금액"])
        self.draft_sort_descending = False
        header = self.draft_table.horizontalHeader()
        header.setStretchLastSection(False)
        header.setSortIndicatorShown(True)
        header.setSortIndicator(0, Qt.SortOrder.AscendingOrder)
        header.sectionClicked.connect(self.sort_drafts_by_header)
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

    def sort_drafts_by_header(self, section):
        if section != 0:
            return
        self.draft_sort_descending = not self.draft_sort_descending
        self.draft_table.horizontalHeader().setSortIndicator(
            0,
            Qt.SortOrder.DescendingOrder if self.draft_sort_descending else Qt.SortOrder.AscendingOrder,
        )
        self.refresh_workspace()

    def build_customer_management(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(12)
        layout.addWidget(label("입금확인 이후 고객 · 배송 · 판매 상태를 관리하세요.", "sectionTitle"))
        layout.addWidget(label(
            "견적발송완료 후 구매·입금이 확인된 주문이 이 목록으로 이동합니다. "
            "주문을 더블클릭하면 고객정보, 배송주소, 품목, 원가와 다음 단계를 한 번에 확인할 수 있습니다.",
            "muted", True
        ))

        bar = QHBoxLayout()
        self.sale_search = QLineEdit()
        self.sale_search.setPlaceholderText("주문번호 · 고객명 · 연락처 · 이메일 · 수령인 · 주소 · 품목 검색")
        self.sale_search.textChanged.connect(self.refresh_sales)
        self.state_filter = QComboBox()
        self.state_filter.addItems(["전체 상태", "입금확인", "배송완료", "구매취소"])
        self.state_filter.currentIndexChanged.connect(self.refresh_sales)
        bar.addWidget(self.sale_search, 1)
        bar.addWidget(self.state_filter)
        layout.addLayout(bar)

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
        self.sale_table.setWordWrap(True)
        self.sale_table.setTextElideMode(Qt.TextElideMode.ElideNone)
        self.sale_table.verticalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
        self.sale_table.itemSelectionChanged.connect(self.select_sale)
        self.sale_table.itemDoubleClicked.connect(self.open_customer_order)
        layout.addWidget(self.sale_table, 1)

        bottom = QHBoxLayout()
        self.customer_list_status = label("주문을 선택하세요.", "muted", True)
        bottom.addWidget(self.customer_list_status, 1)
        self.customer_open_button = button("고객 관리 열기", self.open_customer_order, True)
        bottom.addWidget(self.customer_open_button)
        layout.addLayout(bottom)
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
        if index == 1 and self.workspace_ready:
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

        if self.stack.currentIndex() == 1:
            self.mark_requests_seen()
        else:
            self.update_request_badge(state)

    def open_new_requests_from_notification(self):
        self.showNormal()
        self.raise_()
        self.activateWindow()
        self.navigate(1)

    def job_finished(self, task, value, error, conflict, on_success):
        super().job_finished(task, value, error, conflict, on_success)
        if self._request_arrival_notice:
            current = self.message.text().strip()
            self.message.setText(
                self._request_arrival_notice + ("  |  " + current if current else "")
            )
            self._request_arrival_notice = ""

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
        self.manual_quote_button.setEnabled(ready)
        self.request_quote_button.setEnabled(ready and self.request_table.currentRow() >= 0)
        self.draft_open.setEnabled(ready)
        self.draft_delete.setEnabled(ready)
        self.export_button.setEnabled(paired)
        self.import_button.setEnabled(paired)
        self.print_button.setEnabled(ready and not self.data["pending"])
        self.tracking_save.setEnabled(paired)
        self.shipping_edit.setEnabled(ready)
        self.customer_open_button.setEnabled(ready and self.selected_sale is not None)
        if getattr(self, "demo_mode", False):
            for widget in (self.sync_button, self.new_quote_button, self.manual_quote_button, self.request_quote_button,
                           self.draft_open, self.draft_delete, self.customer_open_button,
                           self.export_button, self.import_button, self.print_button,
                           self.tracking_save, self.open_button, self.connect_key_button, self.report_button,
                           self.connection_password, self.copy_button):
                widget.setEnabled(False)

    def apply_view(self, value):
        super().apply_view(value)
        if self.workspace_ready:
            self.refresh_workspace()
            self.refresh_request_notifications()

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
            status = request_status.get(source, "상태확인") if source else draft.get("manualStatus", "수기견적")
            if source and status not in ("견적확정", "견적발송완료"):
                continue
            draft_pairs.append((draft, status))
        draft_pairs.sort(
            key=lambda pair: draft_quote_sort_key(pair[0]),
            reverse=self.draft_sort_descending,
        )
        self.draft_rows = [draft for draft, _status in draft_pairs]
        self.draft_statuses = [status for _draft, status in draft_pairs]
        self.metrics[1].setText(f"{len(draft_pairs):,}건")
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
        ) for d, status in draft_pairs])
        center_table_columns(self.draft_table, (0, 1, 2, 3, 4, 6, 8))
        pending = len(self.data["pending"])
        self.health.setText(f"전송 대기 {pending}건\n동기화하여 저장 여부를 확인하세요." if pending else "운영 시트 연결됨\n수정 중에는 자동 새로고침을 멈춥니다." if self.data["paired"] else "연결 · 설정에서 보관함을 열고\nPC를 연결해주세요.")
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
                -int(row["metadata"].get("orderDate", 0) or 0),
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
        fill_table(self.sale_table, table_rows)
        center_table_columns(self.sale_table, (0, 1, 2, 3, 4, 6, 9))
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
                f"{customer_status_text(self.selected_sale['metadata']['status'])}"
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
        self.navigate(3)

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

    def add_manual_quote(self, *args):
        if getattr(self, "demo_mode", False) or not self.manager or self.task or not self.allow_discard():
            return
        dialog = ManualQuoteDialog(new_manual_draft(), self)
        self.timer.stop()
        result = dialog.exec()
        self.timer.start()
        if result != QDialog.DialogCode.Accepted:
            return
        draft = copy.deepcopy(dialog.draft)
        self.run_job(
            "전화 견적을 작업대에 추가 중…",
            lambda: self.manager.save_draft(draft),
            self.manual_quote_added,
        )

    def manual_quote_added(self, value):
        self.apply_view(value)
        self.message.setText("전화 견적을 추가했습니다. 견적 작업대에서 문자·메일·공식견적서 전달을 진행하세요.")
        self.navigate(2)

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


