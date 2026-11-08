"""Shared desktop presentation; no storage or network operations."""
import re

from PySide6.QtCore import QEvent, Qt, QRectF, QTimer
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPainter, QPalette
from PySide6.QtWidgets import (QApplication, QFrame, QGridLayout, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QScrollArea,
                               QSizePolicy, QTableWidget, QVBoxLayout, QWidget,
                               QStyledItemDelegate, QStyleOptionViewItem, QStyle, QPushButton)


TOKENS = {
    "sidebar": "#17362C", "primary": "#2F6B4F", "primary-hover": "#255A42", "primary-pressed": "#1C4A35",
    "accent": "#B9E0A0", "danger": "#B3261E", "text": "#1C2621", "muted": "#56645C",
    "border": "#DDE3DE", "input-border": "#84948A", "page-bg": "#F3F5F2", "card": "#FFFFFF",
    "table-head": "#EEF2EE", "row-selected": "#E6F1E9", "nav-selected": "#24493C", "nav-caption": "#9DBFAE",
    "waiting": "#F2D38A", "working-bg": "#FFF6E0", "new-fg": "#1D4F91", "new-bg": "#E7F0FB",
    "quote-fg": "#8A5300", "quote-bg": "#FDF1DC", "paid-fg": "#1E6B3F", "paid-bg": "#E3F2E8",
    "ready-fg": "#0B5F56", "ready-bg": "#DFF1EF", "tracking-fg": "#5B3C9E", "tracking-bg": "#EFE9FB",
    "done-fg": "#46545C", "done-bg": "#ECEFF1", "cancel-fg": "#9A2A22", "cancel-bg": "#FBE9E7",
}

# 2026-11-07 A안: 메뉴마다 색 하나 (견적요청 파랑 · 견적 작업대 주황 · 판매관리 초록 계열).
# solid 바탕 위 글자는 on, tint 바탕 위 글자는 ink (모두 4.5:1 이상). nav는 어두운 사이드바 위 점·아이콘 색.
CATEGORY = {
    "requests": {"solid": "#2563C9", "on": "#FFFFFF", "tint": "#DCE8FB", "ink": "#163D73", "line": "#B9C9F0",
                 "head": "#EAF1FD", "row": "#F5F9FF", "nav": "#8DB6F5"},
    "quotes": {"solid": "#F5A623", "on": "#3A2300", "tint": "#FDEFD3", "ink": "#6F4300", "line": "#F0CD8C",
               "head": "#FDF3E0", "row": "#FFF9EE", "nav": "#F5C46B"},
    "sales": {"solid": "#23824A", "on": "#FFFFFF", "tint": "#E3F2E8", "ink": "#1E6B3F", "line": "#B8DEC5",
              "head": "#E8F4EC", "row": "#F4FAF6", "nav": "#7FD8A2"},
}
# 판매관리 안의 단계 (입금확인 → 배송준비 → 송장등록 → 배송완료)
SALES_STAGES = {
    "입금확인": {"key": "paid", "solid": "#23824A", "on": "#FFFFFF", "ink": "#1E6B3F", "line": "#B8DEC5"},
    "배송준비": {"key": "ready", "solid": "#0E7C7B", "on": "#FFFFFF", "ink": "#0B5F56", "line": "#A6D9D7"},
    "송장등록": {"key": "tracking", "solid": "#6A4BD1", "on": "#FFFFFF", "ink": "#5B3C9E", "line": "#CDBFF3"},
    "배송완료": {"key": "done", "solid": "#E4E9EC", "on": "#2F3B42", "ink": "#46545C", "line": "#CBD3D8"},
    "전체 상태": {"key": "all", "solid": "#1C2621", "on": "#FFFFFF", "ink": "#46545C", "line": "#CBD3D8"},
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
            "style": f"color:{fg};background:{bg};border:0;border-radius:6px;padding:4px 10px;font-size:{size}px;font-weight:700;"}


def stage_label(stage, parent=None):
    badge = status_badge(stage)
    result = QLabel(badge["text"], parent)
    result.setObjectName("stageBadge")
    result.setStyleSheet(badge["style"])
    return result


def _category_css():
    """Per-menu colour pills, table heads and sidebar accents (A안)."""
    rules = []
    for name, c in CATEGORY.items():
        page = f'QWidget[category="{name}"]'
        rules += [
            f'{page} QPushButton#filterTab{{border-radius:20px;min-height:40px;padding:0 16px;background:@card@;color:{c["ink"]};border:1px solid {c["line"]};}}',
            f'{page} QPushButton#filterTab:hover{{background:{c["tint"]};}}',
            f'{page} QPushButton#filterTab:checked{{background:{c["solid"]};color:{c["on"]};border-color:{c["solid"]};font-weight:700;}}',
            f'{page} QHeaderView::section{{background:{c["head"]};color:{c["ink"]};}}',
            f'{page} QTableWidget::item:selected{{background:{c["tint"]};color:@text@;}}',
            f'#sidebar QPushButton[category="{name}"]:checked{{border-left-color:{c["nav"]};}}',
            f'#sidebar QLabel#navBadge[kind="{name}"]{{background:{c["solid"]};color:{c["on"]};}}',
        ]
    for stage, c in SALES_STAGES.items():
        key = c["key"]
        rules += [
            f'QPushButton#stageTab[stage="{key}"]{{border-radius:20px;min-height:40px;padding:0 16px;background:@card@;color:{c["ink"]};border:1px solid {c["line"]};}}',
            f'QPushButton#stageTab[stage="{key}"]:checked{{background:{c["solid"]};color:{c["on"]};border-color:{c["solid"]};font-weight:700;}}',
        ]
    return "\n".join(rules)


def _theme(css, large=False, compact=False):
    css = css.replace("@category-css@", _category_css())
    values = {**TOKENS, **{key: str(value + (2 if large else 0)) for key, value in
              {"body": 15, "small": 13, "title": 22, "section": 17, "action": 17, "total": 28, "detail": 20, "brand": 20}.items()},
              "control-height": str(48 if large else 44),
              "nav-height": str((52 if large else 48) if compact else (60 if large else 56))}
    for key, value in values.items():
        css = css.replace("@" + key + "@", value)
    return css


def request_view_style(large=False):
    """Scoped customer-card presentation; no sales or shared-detail selectors."""
    css = """
QFrame#requestCountPill{background:#F5F9FF;border:2px solid #2563C9;border-radius:28px;}
QFrame#requestCountPill QLabel{background:transparent;border:0;}
QFrame#requestCountPill QLabel#requestCountDot{background:#2563C9;border-radius:5px;}
QLabel#requestCountTitle{font-size:@request-title@px;font-weight:700;color:#163D73;}
QLabel#requestCountValue{font-size:@request-count@px;font-weight:700;color:#2563C9;}
QLabel#requestCountUnit{font-size:@request-body@px;color:#163D73;}
QScrollArea#requestHeaderGroup{background:transparent;border:0;}
QLineEdit#requestHeaderSearch{background:#F3F5F2;border:1px solid #DDE3DE;border-radius:12px;min-height:0;padding:0 12px;font-size:@request-search@px;}
QLineEdit#requestHeaderSearch:focus{border-color:#2563C9;}
QTableWidget#requestInboxTable{background:#FFFFFF;alternate-background-color:#F7F9F7;selection-background-color:#DCE8FB;}
QTableWidget#requestInboxTable::item{border:0;border-bottom:1px solid #EEF2EE;padding:8px;}
QTableWidget#requestInboxTable::item:selected{background:#DCE8FB;color:#1C2621;}
QTableWidget#requestInboxTable QHeaderView::section{font-size:@request-body@px;font-weight:700;background:#EAF1FD;color:#163D73;border:0;border-top:3px solid #2563C9;border-bottom:1px solid #B9C9F0;padding:8px;}
QFrame#requestDetailPanel{background:#FFFFFF;border:1px solid #DDE3DE;border-radius:12px;}
QFrame#requestDetailPanel QScrollArea,QWidget#requestDetailBody{background:transparent;border:0;}
QFrame#requestDetailPanel QLabel{background:transparent;border:0;}
QFrame#requestDetailPanel QLabel#requestEmpty{color:#56645C;}
QFrame#requestDetailPanel QLabel#requestAvatar{background:#DCE8FB;color:#163D73;border-radius:28px;font-size:24px;font-weight:700;}
QFrame#requestDetailPanel QLabel#requestCustomerName{font-size:24px;font-weight:700;}
QFrame#requestDetailPanel QLabel#requestNewBadge{background:#E7F0FB;color:#1D4F91;border-radius:6px;font-size:@request-small@px;padding:3px 8px;}
QFrame#requestDetailPanel QLabel#requestReference{font-size:@request-small@px;color:#56645C;}
QFrame#requestContactCard{background:#F5F9FF;border:1px solid #B9C9F0;border-radius:12px;}
QFrame#requestDetailPanel QLabel#requestContactCaption{font-size:@request-small@px;font-weight:700;color:#163D73;}
QFrame#requestDetailPanel QLabel#requestPhone{font-family:Consolas;font-size:23px;font-weight:700;color:#163D73;}
QFrame#requestDetailPanel QPushButton#requestPhoneCopy{font-size:@request-body@px;min-height:0;padding:0 8px;color:#163D73;border-color:#B9C9F0;}
QFrame#requestInfoCell,QFrame#requestOfficialCard{border:1px solid #DDE3DE;border-radius:10px;}
QFrame#requestOfficialCard[requested="true"]{background:#DCE8FB;}
QFrame#requestOfficialCard[requested="false"]{background:#ECEFF1;}
QFrame#requestDetailPanel QLabel#requestInfoCaption{font-size:@request-caption@px;color:#56645C;}
QFrame#requestDetailPanel QLabel#requestInfoValue{font-size:@request-body@px;font-weight:700;}
QFrame#requestDetailPanel QLabel#requestItemsHeading{font-size:@request-item@px;font-weight:700;color:#1E6B3F;}
QFrame#requestDetailPanel QLabel#requestItemsCount{font-size:@request-small@px;color:#1E6B3F;}
QFrame#requestItemsCard{background:#FFFFFF;border:1px solid #B8DEC5;border-radius:10px;}
QWidget#requestItemRow{border:0;border-bottom:1px solid #EEF2EE;}
QFrame#requestDetailPanel QLabel#requestItemNumber{background:#E3F2E8;border-radius:13px;color:#1E6B3F;font-size:@request-small@px;font-weight:700;}
QFrame#requestDetailPanel QLabel#requestItemSpecies{font-size:@request-item@px;font-weight:700;}
QFrame#requestDetailPanel QLabel#requestItemSpecification{font-size:@request-small@px;color:#56645C;}
QFrame#requestDetailPanel QLabel#requestItemQuantity{font-size:@request-search@px;font-weight:700;}
QFrame#requestDetailPanel QLabel#requestItemsTotal{background:#F4FAF6;color:#1E6B3F;font-size:@request-body@px;font-weight:700;padding:10px;}
QFrame#requestDetailPanel QLabel#requestNoteHeading{font-size:@request-item@px;font-weight:700;color:#8A5300;}
QFrame#requestDetailPanel QLabel#requestCustomerNote{font-size:@request-body@px;background:#F3F5F2;color:#56645C;border-radius:10px;padding:12px;}
QFrame#requestDetailPanel QLabel#requestCustomerNote[hasMessage="true"]{background:#FDF1DC;border:1px solid #F0CD8C;color:#1C2621;}
QFrame#requestDetailPanel QPushButton#primary{font-size:18px;font-weight:700;min-height:0;}
QFrame#requestDetailPanel QLabel#requestOpenHint{font-size:13px;color:#56645C;}
QFrame#requestDetailPanel QPushButton#requestDetailDelete{font-size:13px;color:#B3261E;background:transparent;border:0;min-height:32px;padding:0 4px;}
"""
    sizes = {'title': 18, 'count': 26, 'body': 15, 'search': 17, 'small': 13, 'caption': 12, 'item': 16}
    for key, size in sizes.items():
        css = css.replace('@request-' + key + '@', str(size + (2 if large else 0)))
    return css


def workspace_style(large=False, compact=False):
    # Compact layouts change geometry, never reduce the readable type scale.
    return _theme("""
QWidget{font-family:'Malgun Gothic','NanumGothic',sans-serif;font-size:@body@px;color:@text@;}
QMainWindow,#workspace,QDialog{background:@page-bg@;}
#sidebar{background:@sidebar@;border:0;}
#sidebarScroll{background:@sidebar@;border:0;}
#sidebar QLabel{color:@card@;background:transparent;}
#sidebar QLabel#brand{font-size:@brand@px;font-weight:700;}
#sidebar QLabel#navGroup{font-size:@body@px;font-weight:700;color:@nav-caption@;padding:4px 12px 2px;}
#sidebar QFrame#navDivider{background:#2E5446;border:0;}
#sidebar QFrame#sidebarClock{background:#20463A;border:0;border-radius:12px;}
#sidebar QLabel#sidebarDate{font-size:@body@px;color:@nav-caption@;}
#sidebar QLabel#sidebarTime{font-size:@title@px;font-weight:700;color:@card@;}
#sidebar QLabel#navFoot{font-size:@small@px;color:@nav-caption@;}
#sidebar QPushButton{background:transparent;color:@card@;border:0;border-left:3px solid transparent;border-radius:8px;text-align:left;padding:0 14px;min-height:@nav-height@px;font-size:@action@px;font-weight:600;}
#sidebar QPushButton:checked{background:@nav-selected@;border-left-color:@accent@;}
#sidebar QPushButton:hover{background:@nav-selected@;}
#sidebar QPushButton[keyboardFocus="true"]{border:2px solid @accent@;}
#sidebar #navRow{background:transparent;}
#sidebar QLabel#navBadge{font-size:@section@px;font-weight:700;background:@border@;color:@sidebar@;border-radius:10px;padding:0 6px;}
#sidebar QLabel#navBadge[kind="new"]{background:@accent@;}
#sidebar QLabel#navBadge[kind="inquiry"]{background:@waiting@;}
QFrame#pageHeader{background:@card@;border:0;border-bottom:1px solid @border@;}
QLabel#pageTitle,QLabel#summaryTitle,QLabel#heroTitle,QLabel#deliveryTitle{font-size:@title@px;font-weight:700;}
QLabel#sectionTitle,QLabel#quoteItemsTitle,QLabel#emailSectionTitle{font-size:@section@px;font-weight:700;}
QLabel#summaryCount,QLabel#summaryCountUnit,QLabel#muted,QLabel#eyebrow,QLabel#selectionHint,QLabel#deliverySubtitle,QLabel#infoCaption{font-size:@small@px;color:@muted@;}
QLabel#detailCustomer{font-size:@detail@px;font-weight:700;}
QLabel#detailQuantity{font-size:@action@px;font-weight:700;}
QLabel#metricValue,QLabel#quoteTotalValue,QLabel#workbenchTotal{font-size:@total@px;font-weight:700;color:@primary@;}
QFrame#card,QFrame#hero,QFrame#detailPanel,QFrame#quoteItemsCard,QFrame#customerInfoCard,QFrame#emailAddressCard,QFrame#workbenchDocumentTools{background:@card@;border:1px solid @border@;border-radius:12px;}
QFrame#statusBar{background:@card@;border:0;border-top:1px solid @border@;}
QFrame#statusBar QLabel{font-size:@small@px;}
QFrame#statusBar[state="working"]{background:@working-bg@;}
QFrame#statusBar[state="success"] QLabel{color:@primary@;}
QFrame#errorBanner{background:@cancel-bg@;border:0;border-radius:8px;}
QFrame#errorBanner QLabel{color:@cancel-fg@;font-size:@small@px;}
QPushButton{background:@card@;color:@text@;border:1px solid @input-border@;border-radius:8px;min-height:@control-height@px;padding:0 16px;font-size:@body@px;font-weight:600;}
QPushButton:hover{background:@row-selected@;border-color:@primary@;}
QPushButton:pressed{background:@border@;}
QPushButton:focus{border:2px solid @primary@;}
QPushButton:disabled{background:@table-head@;color:@muted@;border-color:@border@;}
QPushButton#primary,QPushButton[role="primary"]{background:@primary@;color:@card@;border-color:@primary@;font-size:@action@px;}
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
QTableWidget{background:@card@;alternate-background-color:@card@;border:1px solid @border@;border-radius:12px;gridline-color:@border@;selection-background-color:@row-selected@;selection-color:@text@;font-size:@body@px;}
QTableWidget::item{padding:8px;border:0;border-bottom:1px solid @border@;}
QTableWidget::item:selected{background:@row-selected@;color:@text@;}
QHeaderView::section{background:@table-head@;color:@muted@;border:0;border-bottom:1px solid @border@;padding:8px;font-size:@small@px;font-weight:700;}
QGroupBox{background:@card@;border:1px solid @border@;border-radius:12px;margin-top:24px;padding:16px;font-size:@section@px;font-weight:700;}
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
@category-css@
QTabWidget::pane{border:0;border-top:1px solid @border@;}
QTabBar::tab{background:transparent;border:0;border-bottom:3px solid transparent;padding:8px 16px;min-height:32px;}
QTabBar::tab:selected{color:@primary@;border-bottom-color:@primary@;}
QLabel#channelStatusBadge{font-size:@small@px;background:@table-head@;color:@muted@;border-radius:6px;padding:4px 8px;}
QLabel#channelStatusBadge[state="pending"]{background:@working-bg@;color:@quote-fg@;}
QLabel#channelStatusBadge[state="failed"]{background:@cancel-bg@;color:@cancel-fg@;}
QLabel#channelStatusBadge[state="success"]{background:@paid-bg@;color:@paid-fg@;}
QLabel#emptyState{font-size:@body@px;color:@muted@;background:transparent;padding:24px;}
""" + _work_window_css() + request_view_style(large), large, compact)


# 작업창 색: 견적 작업대는 주황, 판매관리 작업창은 초록 (CATEGORY와 같은 계열).
WORK_PALETTES = {
    "quotes": {"solid": "#F5A623", "on": "#3A2300", "tint": "#FDEFD3", "ink": "#6F4300", "line": "#F0CD8C",
               "head": "#FDF3E0", "row": "#FFF9EE", "soft": "#FFFCF6", "rule": "#F3E6CC"},
    "sales": {"solid": "#23824A", "on": "#FFFFFF", "tint": "#E3F2E8", "ink": "#1E6B3F", "line": "#B8DEC5",
              "head": "#E8F4EC", "row": "#F4FAF6", "soft": "#F7FBF8", "rule": "#DCEDE2"},
}


def quote_workbench_style(large=False, category="quotes"):
    """Work-window layout shared by the quote workbench (주황) and the sales window (초록)."""
    css = """
QDialog#quoteWorkbench{background:@page-bg@;}
QDialog#quoteWorkbench QFrame#workbenchHeader{background:@sidebar@;border:0;border-bottom:4px solid @w-solid@;}
QDialog#quoteWorkbench #workbenchHeader QLabel{color:@card@;background:transparent;border:0;}
QDialog#quoteWorkbench QLabel#workbenchTitle{font-size:@section@px;font-weight:700;color:@w-on@;background:@w-solid@;border-radius:8px;padding:4px 12px;}
QDialog#quoteWorkbench QLabel#workbenchBrand{font-size:@title@px;font-weight:700;}
QDialog#quoteWorkbench QLabel#workbenchReference{font-size:@small@px;color:#CFE3D8;}
QDialog#quoteWorkbench #workbenchCustomerRail{background:@page-bg@;}
QDialog#quoteWorkbench QLabel#workbenchCaption{font-size:@small@px;color:@muted@;background:transparent;border:0;padding:0;}
QDialog#quoteWorkbench QTextBrowser#workbenchCustomerName,QDialog#quoteWorkbench QTextBrowser#workbenchInfo,QDialog#quoteWorkbench QTextBrowser#workbenchReceipt{background:transparent;border:0;padding:0;font-size:@body@px;}
QDialog#quoteWorkbench QTextBrowser#workbenchCustomerName,QDialog#quoteWorkbench QTextBrowser#workbenchReceipt{font-weight:700;}
QDialog#quoteWorkbench QFrame#workbenchDocumentTools{border-radius:12px;}
QDialog#quoteWorkbench QTableWidget#workbenchItems{font-size:@body@px;background:@card@;}
QDialog#quoteWorkbench QTableWidget#workbenchItems::item{padding:4px 8px;border-bottom:1px solid @border@;}
QDialog#quoteWorkbench QTableWidget#workbenchItems::item:hover:!selected{background:@table-head@;}
QDialog#quoteWorkbench #quoteSpeciesEditor QLineEdit{border:1px solid @border@;border-radius:6px;background:@card@;padding:0 6px;min-height:34px;}
QDialog#quoteWorkbench #quoteSpeciesEditor QLineEdit:focus{border-color:@primary@;}
QDialog#quoteWorkbench #quoteSpeciesEditor QPushButton{background:@card@;border:1px solid @border@;border-radius:6px;color:@primary@;min-width:36px;max-width:36px;min-height:36px;max-height:36px;padding:0;}
QDialog#quoteWorkbench #quoteSpeciesEditor QPushButton:hover{background:@table-head@;border-color:@primary@;}
QDialog#quoteWorkbench #unitPriceEditor QPushButton{min-width:40px;max-width:40px;min-height:40px;padding:0;}
QDialog#quoteWorkbench #unitPriceEditor QLineEdit{min-height:38px;min-width:64px;padding:0 4px;}
QDialog#quoteWorkbench QFrame#workbenchFooter{background:@card@;border:0;border-top:1px solid @border@;}
QDialog#quoteWorkbench #workbenchFooter QPushButton{min-height:52px;}
QDialog#quoteWorkbench QLabel#workbenchTotal{font-size:@total@px;font-weight:700;color:#1E6B3F;}
QDialog#quoteWorkbench QFrame#workbenchShipping{background:@w-soft@;border:0;border-top:1px solid @w-line@;border-bottom-left-radius:14px;border-bottom-right-radius:14px;}
QDialog#quoteWorkbench #workbenchShipping QLineEdit{min-height:28px;}
QDialog#quoteWorkbench #workbenchOriginalText{background:#F5F9FF;border:1px solid #DCE8FB;border-radius:10px;padding:6px 8px;font-size:@body@px;}
QDialog#quoteWorkbench QPushButton[deliveryCompletion="true"]{min-width:180px;max-width:200px;min-height:64px;font-size:@detail@px;font-weight:700;border-radius:14px;}
QDialog#quoteWorkbench QPushButton#smsAction,QDialog#quoteWorkbench QPushButton#mailAction{min-height:56px;font-size:@action@px;padding:0 8px;background:@card@;color:#163D73;border:2px solid #2563C9;border-radius:12px;}
QDialog#quoteWorkbench QPushButton#smsAction:hover,QDialog#quoteWorkbench QPushButton#mailAction:hover{background:#F5F9FF;}
QDialog#quoteWorkbench QPushButton#smsAction:disabled,QDialog#quoteWorkbench QPushButton#mailAction:disabled{border:1px solid @border@;color:@muted@;background:@table-head@;}
QDialog#quoteWorkbench QLabel#deliveryActionTitle{font-size:@action@px;font-weight:700;color:#163D73;}
QDialog#quoteWorkbench #workbenchCustomerInput{background:@card@;}
/* 2026-11-08 A안: 고객=파랑, 판매자 작성=주황, 금액·완료=초록 */
QDialog#quoteWorkbench QPushButton#workbenchClose{background:transparent;color:@card@;border:1px solid #5E8072;border-radius:10px;min-height:0;padding:0;font-size:@action@px;}
QDialog#quoteWorkbench QPushButton#workbenchClose:hover{background:@nav-selected@;}
QDialog#quoteWorkbench QLabel#workbenchAvatar{background:#DCE8FB;color:#163D73;border-radius:24px;font-size:@detail@px;font-weight:700;}
QDialog#quoteWorkbench QLabel#workbenchCustomerTitle{font-size:@section@px;font-weight:700;color:#163D73;}
QDialog#quoteWorkbench QFrame#workbenchPhoneBox{background:#F5F9FF;border:1px solid #B9C9F0;border-radius:12px;}
QDialog#quoteWorkbench #workbenchPhoneBox QLabel{background:transparent;border:0;}
QDialog#quoteWorkbench QLabel#workbenchPhoneCaption{font-size:@small@px;font-weight:700;color:#163D73;}
QDialog#quoteWorkbench QLabel#workbenchPhone{font-family:Consolas;font-size:@detail@px;font-weight:700;color:@text@;}
QDialog#quoteWorkbench QPushButton#workbenchPhoneCopy{background:@card@;color:#163D73;border:1px solid #2563C9;border-radius:10px;min-height:40px;min-width:84px;padding:0 10px;font-weight:700;}
QDialog#quoteWorkbench QPushButton#workbenchPhoneCopy:hover{background:#DCE8FB;}
QDialog#quoteWorkbench QFrame#workbenchFormal[requested="true"]{background:#E7F0FB;border-radius:8px;}
QDialog#quoteWorkbench QFrame#workbenchFormal[requested="true"] QLabel{color:#1D4F91;font-weight:700;}
QDialog#quoteWorkbench QLabel#deliverySectionTitle{font-size:@section@px;font-weight:700;}
QDialog#quoteWorkbench QPushButton#pdfAction[documentGenerate="true"]{background:@w-row@;color:@w-ink@;border:2px solid @w-solid@;border-radius:10px;min-height:48px;font-weight:700;}
QDialog#quoteWorkbench QPushButton#pdfAction[documentGenerate="true"]:hover{background:@w-tint@;}
QDialog#quoteWorkbench QPushButton#pdfAction{min-height:48px;color:@text@;border-color:@border@;}
QDialog#quoteWorkbench QFrame#workbenchItemsCard{background:@card@;border:1px solid @w-line@;border-radius:14px;}
QDialog#quoteWorkbench QFrame#workbenchItemsHead{background:@w-tint@;border:0;border-top-left-radius:14px;border-top-right-radius:14px;}
QDialog#quoteWorkbench #workbenchItemsHead QLabel{background:transparent;}
QDialog#quoteWorkbench #workbenchItemsHead QLabel#workbenchSectionTitle{font-size:@section@px;font-weight:700;color:@w-ink@;}
QDialog#quoteWorkbench QLabel#workbenchItemsCount{font-size:@body@px;font-weight:700;color:@w-ink@;}
QDialog#quoteWorkbench #workbenchItemsHead QLabel#deliveryCaption{color:@w-ink@;}
QDialog#quoteWorkbench QPushButton[role="itemAdd"]{background:@card@;color:@w-ink@;border:1px solid @w-ink@;border-radius:10px;min-height:42px;font-weight:700;}
QDialog#quoteWorkbench QPushButton[role="itemAdd"]:hover{background:@w-row@;}
QDialog#quoteWorkbench QTableWidget#workbenchItems{border:0;border-radius:0;alternate-background-color:@w-row@;}
QDialog#quoteWorkbench QTableWidget#workbenchItems::item{border-bottom:1px solid @w-rule@;}
QDialog#quoteWorkbench QTableWidget#workbenchItems QHeaderView::section{background:@w-head@;color:@w-ink@;font-size:@body@px;font-weight:700;border:0;border-bottom:2px solid @w-solid@;padding:6px 8px;}
QDialog#quoteWorkbench #workbenchShipping QLabel#deliverySectionTitle{color:@w-ink@;}
QDialog#quoteWorkbench QFrame#workbenchOriginalRequest{background:@card@;border:1px solid #B9C9F0;border-radius:14px;}
QDialog#quoteWorkbench QLabel#workbenchOriginalTitle{font-size:@section@px;font-weight:700;color:#163D73;}
QDialog#quoteWorkbench QLabel#workbenchOriginalBadge{font-size:@small@px;font-weight:700;color:@muted@;background:@table-head@;border-radius:6px;padding:2px 8px;}
QDialog#quoteWorkbench QFrame#workbenchSellerNote{background:@card@;border:1px solid @w-line@;border-radius:14px;}
QDialog#quoteWorkbench QLabel#workbenchSellerNoteTitle{font-size:@section@px;font-weight:700;color:@w-ink@;}
QDialog#quoteWorkbench QLabel#workbenchSellerBadge{font-size:@small@px;font-weight:700;color:@w-ink@;background:@w-tint@;border-radius:6px;padding:2px 8px;}
QDialog#quoteWorkbench QFrame#workbenchFooter{background:@card@;border:0;border-top:1px solid @border@;}
QDialog#quoteWorkbench QPushButton#workbenchCloseText{background:transparent;color:@muted@;border:0;min-width:64px;font-size:@action@px;}
QDialog#quoteWorkbench QPushButton#workbenchCloseText:hover{color:@text@;background:@table-head@;}
QDialog#quoteWorkbench QFrame#workbenchFooterRule{background:@border@;border:0;}
QDialog#quoteWorkbench QLabel#workbenchAmountDetail{font-size:@small@px;color:@muted@;}
QDialog#quoteWorkbench QLabel#workbenchStepSend{font-size:@small@px;font-weight:700;color:#1D4F91;}
QDialog#quoteWorkbench QLabel#workbenchStepDone{font-size:@small@px;font-weight:700;color:#1E6B3F;}
QDialog#quoteWorkbench QLabel#workbenchStepArrow{font-size:@title@px;color:@input-border@;padding-bottom:14px;}
QDialog#quoteWorkbench QLabel#workbenchCompletionHint{font-size:@small@px;color:@muted@;}
"""
    for key, value in WORK_PALETTES[category].items():
        css = css.replace("@w-" + key + "@", value)
    return _theme(css, large)


# 2026-11-08 작업창 공통: 어두운 머리띠 + 업무색 칩·밑줄, 버튼은 중요도(tier)로 크기를 나눈다.
WORK_WINDOW_ACCENTS = {
    "quotes": ("#F5A623", "#3A2300"), "requests": ("#2563C9", "#FFFFFF"), "sales": ("#23824A", "#FFFFFF"),
    "shipping": ("#0E7C7B", "#FFFFFF"), "inquiries": ("#F2D38A", "#3A2300"), "nursery": ("#7FD8A2", "#17362C"),
    "system": ("#CBD3D8", "#2F3B42"),
}


def _work_window_css():
    css = ["QFrame#workWindowHeader{background:@sidebar@;border:0;border-bottom:4px solid #F5A623;border-radius:10px;}",
           "QFrame#workWindowHeader QLabel{background:transparent;border:0;color:@card@;}",
           "QFrame#workWindowHeader QLabel#workWindowChip{font-size:@section@px;font-weight:700;border-radius:8px;padding:4px 12px;}",
           "QFrame#workWindowHeader QLabel#workWindowSubtitle{font-size:@small@px;color:#CFE3D8;}",
           "QPushButton[tier=\"quiet\"]{background:transparent;color:@muted@;border:0;min-width:64px;}",
           "QPushButton[tier=\"quiet\"]:hover{background:@table-head@;color:@text@;}",
           "QPushButton[tier=\"main\"]{min-height:52px;min-width:150px;font-size:@action@px;font-weight:700;border-radius:12px;}"]
    for key, (solid, on) in WORK_WINDOW_ACCENTS.items():
        css.append(f'QFrame#workWindowHeader[category="{key}"]{{border-bottom-color:{solid};}}')
        css.append(f'QFrame#workWindowHeader QLabel#workWindowChip[category="{key}"]{{background:{solid};color:{on};}}')
    return "\n".join(css) + "\n"


def work_window_header(title, category="quotes", subtitle=""):
    """작업창 맨 위 머리띠. 제목은 업무색 칩, 보조 설명은 옅은 글씨로 둡니다."""
    frame = QFrame()
    frame.setObjectName("workWindowHeader")
    frame.setProperty("category", category)
    row = QHBoxLayout(frame)
    row.setContentsMargins(16, 10, 16, 10)
    row.setSpacing(12)
    chip = QLabel(title)
    chip.setObjectName("workWindowChip")
    chip.setProperty("category", category)
    chip.setTextFormat(Qt.TextFormat.PlainText)
    chip.setSizePolicy(QSizePolicy.Policy.Maximum, QSizePolicy.Policy.Preferred)
    row.addWidget(chip)
    detail = QLabel(subtitle)
    detail.setObjectName("workWindowSubtitle")
    detail.setTextFormat(Qt.TextFormat.PlainText)
    detail.setWordWrap(True)
    detail.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
    detail.setVisible(bool(subtitle))
    row.addWidget(detail, 1)
    row.addStretch(0 if subtitle else 1)
    frame.title_label = chip
    frame.subtitle_label = detail
    return frame


def set_button_tier(button, tier):
    """quiet: 닫기·취소처럼 가장 약한 버튼, main: 그 창의 마지막 단계(가장 크게)."""
    button.setProperty("tier", tier)
    if tier == "main" and button.objectName() != "primary" and button.property("role") != "danger":
        button.setProperty("role", "primary")
    button.style().unpolish(button)
    button.style().polish(button)
    return button


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


class CellWidgetDelegate(RowSelectionDelegate):
    """Keep model text for collection/search, but let its editor draw it once."""
    def initStyleOption(self, option, index):
        super().initStyleOption(option, index)
        table = self.parent()
        if table.cellWidget(index.row(), index.column()) is not None:
            option.text = ""


class QuoteRemarksDelegate(RowSelectionDelegate):
    """An empty-cell hint is presentation only, never quote data or export text."""
    def __init__(self, parent=None, *, specification=False):
        super().__init__(parent)
        self.specification = specification

    def initStyleOption(self, option, index):
        super().initStyleOption(option, index)
        if not index.data(Qt.ItemDataRole.DisplayRole):
            editable = bool(index.flags() & Qt.ItemFlag.ItemIsEditable)
            option.text = "규격 입력" if self.specification and editable else "" if self.specification else "입력가능"
            option.font.setItalic(not self.specification)
            color = QColor(TOKENS["input-border"] if self.specification else TOKENS["muted"])
            option.palette.setColor(QPalette.ColorRole.Text, color)
            option.palette.setColor(QPalette.ColorRole.HighlightedText, color)

    def paint(self, painter, option, index):
        super().paint(painter, option, index)
        if self.specification and index.flags() & Qt.ItemFlag.ItemIsEditable:
            painter.save()
            painter.setPen(QColor(TOKENS["border"]))
            painter.setBrush(Qt.BrushStyle.NoBrush)
            painter.drawRoundedRect(QRectF(option.rect).adjusted(4, 10, -4, -10), 5, 5)
            painter.restore()

    def createEditor(self, parent, option, index):
        editor = super().createEditor(parent, option, index)
        if isinstance(editor, QLineEdit):
            font = parent.font()
            font.setItalic(False)
            editor.setFont(font)
            editor.setPlaceholderText("규격 입력" if self.specification else "입력가능")
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


class RequestQuoteDelegate(RowSelectionDelegate):
    """Render the original formal-quote flag without changing its stored table text."""
    def paint(self, painter, option, index):
        opt = QStyleOptionViewItem(option)
        self.initStyleOption(opt, index)
        requested = opt.text == '요청'
        opt.text = ''
        style = opt.widget.style() if opt.widget else QApplication.style()
        style.drawControl(QStyle.ControlElement.CE_ItemViewItem, opt, painter, opt.widget)
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        font = QFont(option.font)
        font.setPixelSize(17 if QApplication.instance().property('namufarmLargeFont') else 15)
        font.setBold(requested)
        painter.setFont(font)
        text = '요청' if requested else '없음'
        width = QFontMetrics(font).horizontalAdvance(text)
        left = option.rect.center().x() - (width + 16) / 2
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor('#2563C9' if requested else '#CBD3D8'))
        painter.drawEllipse(QRectF(left, option.rect.center().y() - 4, 8, 8))
        painter.setPen(QColor('#163D73' if requested else '#56645C'))
        rect = QRectF(left + 16, option.rect.y(), width, option.rect.height())
        painter.drawText(rect, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, text)
        painter.restore()


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
        rect = option.rect.adjusted(8, 2, -8, -2)
        # Each line uses its real font height (no half-row split) so large text never overlaps.
        top_font = QFont(option.font)
        top_font.setBold(True)
        sub_font = QFont(option.font)
        sub_font.setBold(False)
        sub_font.setPixelSize(15 if QApplication.instance().property('namufarmLargeFont') else 13)
        top_height, sub_height = QFontMetrics(top_font).height(), QFontMetrics(sub_font).height()
        gap = 2
        y = rect.y() + max(0, (rect.height() - top_height - gap - sub_height) / 2)
        painter.setFont(top_font)
        painter.setPen(QColor(TOKENS['text']))
        top = QRectF(rect.x(), y, rect.width(), top_height)
        painter.drawText(top, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                         painter.fontMetrics().elidedText(primary, Qt.TextElideMode.ElideRight, rect.width()))
        painter.setFont(sub_font)
        painter.setPen(QColor(TOKENS['muted']))
        bottom = QRectF(rect.x(), y + top_height + gap, rect.width(), sub_height)
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
        font = QFont(option.font)
        font.setPixelSize(15 if QApplication.instance().property("namufarmLargeFont") else 13)
        font.setBold(True)
        metrics = QFontMetrics(font)
        # Badge height follows the bold badge font (+4px top/bottom), never a fixed box.
        width = min(option.rect.width() - 12, metrics.horizontalAdvance(text) + 24)
        height = min(option.rect.height() - 4, max(26, metrics.height() + 8))
        rect = QRectF(0, 0, max(1, width), max(1, height))
        rect.moveCenter(QRectF(option.rect).center())
        painter.drawRoundedRect(rect, 6, 6)
        painter.setPen(QColor(foreground))
        painter.setFont(font)
        painter.drawText(rect, Qt.AlignmentFlag.AlignCenter,
                         painter.fontMetrics().elidedText(text, Qt.TextElideMode.ElideRight, max(1, int(rect.width())-8)))
        painter.restore()


class StageCards(QWidget):
    """A row of coloured count cards for one menu; display only, never stores data."""
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("stageCards")
        self._layout = QGridLayout(self)
        self._layout.setContentsMargins(0, 0, 0, 0)
        self._layout.setHorizontalSpacing(12)
        self._cards = {}

    def add(self, key, title, solid, on, caption=""):
        card = QFrame(self)
        card.setObjectName("stageCard")
        card.setStyleSheet(f"QFrame#stageCard{{background:{solid};border:0;border-radius:14px;}}"
                           f"QFrame#stageCard QLabel{{color:{on};background:transparent;}}")
        box = QVBoxLayout(card)
        box.setContentsMargins(18, 14, 18, 14)
        box.setSpacing(2)
        large = bool(QApplication.instance() and QApplication.instance().property("namufarmLargeFont"))
        heading = QLabel(title, card)
        heading.setStyleSheet(f"font-size:{15 if large else 13}px;font-weight:700;")
        count = QLabel("0", card)
        count.setStyleSheet(f"font-size:{34 if large else 30}px;font-weight:700;")
        note = QLabel(caption, card)
        note.setWordWrap(True)
        note.setStyleSheet(f"font-size:{15 if large else 13}px;")
        for widget in (heading, count, note):
            box.addWidget(widget)
        self._layout.addWidget(card, 0, len(self._cards))
        self._layout.setColumnStretch(len(self._cards), 1)
        self._cards[key] = (card, count, note)
        return card

    def set(self, key, count, caption=None, unit="건"):
        _card, number, note = self._cards[key]
        number.setText(f"{count:,} {unit}" if unit else str(count))
        if caption is not None:
            note.setText(caption)


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
        self._stripe = None  # colour, or callable(row) -> colour/None; drawn over the row's left edge
        self._stripe_width = 5
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
            if minimum_total > available and not self.property("preserveColumnMinimums"):
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

    def set_row_stripe(self, stripe, *, width=5):
        self._stripe = stripe
        self._stripe_width = width
        self.viewport().update()

    def paintEvent(self, event):
        super().paintEvent(event)
        if not self._stripe or not self.rowCount():
            return
        painter = QPainter(self.viewport())
        first = max(0, self.rowAt(0))
        last = self.rowAt(self.viewport().height() - 1)
        last = self.rowCount() - 1 if last < 0 else last
        for row in range(first, last + 1):
            if self.isRowHidden(row):
                continue
            color = self._stripe(row) if callable(self._stripe) else self._stripe
            if color:
                painter.fillRect(0, self.rowViewportPosition(row), self._stripe_width, self.rowHeight(row), QColor(color))
        painter.end()

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



