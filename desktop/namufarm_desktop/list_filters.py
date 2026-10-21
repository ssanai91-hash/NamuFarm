"""Local display filters; never change stored records or synchronization scope."""
from datetime import datetime, timedelta

from PySide6.QtCore import QDate, Signal
from PySide6.QtWidgets import (QComboBox, QDateEdit, QDialog, QDialogButtonBox,
                               QFormLayout, QHBoxLayout, QLabel, QWidget)

from .domain import SEOUL


def record_date(value):
    if not value:
        return None
    try:
        if isinstance(value, (int, float)) or str(value).isdigit():
            if int(value) <= 0:
                return None
            return datetime.fromtimestamp(int(value) / 1000, SEOUL).date()
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return (parsed.astimezone(SEOUL) if parsed.tzinfo else parsed).date()
    except (ValueError, TypeError, OverflowError, OSError):
        return None


def first_date(*values):
    return next((day for value in values if (day := record_date(value)) is not None), None)


class ListPeriodFilter(QWidget):
    changed = Signal()
    CHOICES = (("오늘", "today"), ("최근 7일", "week"), ("이번 달", "month"),
               ("올해", "year"), ("직접 선택", "custom"), ("전체 기간", "all"))

    def __init__(self, default="month", parent=None):
        super().__init__(parent)
        self.default = self.key = default
        today = datetime.now(SEOUL).date()
        self.custom_start = self.custom_end = today
        self.combo = QComboBox()
        self.combo.setAccessibleName("조회 기간")
        for title, key in self.CHOICES:
            self.combo.addItem(title, key)
        self.combo.setCurrentIndex(self.combo.findData(default))
        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.combo)
        self.combo.activated.connect(self.choose)

    def choose(self, index):
        key = self.combo.itemData(index)
        if key == "custom":
            dialog = QDialog(self)
            dialog.setWindowTitle("조회 기간 선택")
            form = QFormLayout(dialog)
            start, end = QDateEdit(QDate(self.custom_start.year, self.custom_start.month, self.custom_start.day)), QDateEdit(QDate(self.custom_end.year, self.custom_end.month, self.custom_end.day))
            for field in (start, end):
                field.setCalendarPopup(True)
                field.setDisplayFormat("yyyy-MM-dd")
            form.addRow("시작일", start)
            form.addRow("종료일", end)
            error = QLabel("종료일은 시작일 이후로 선택해주세요.")
            error.hide()
            form.addRow(error)
            buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
            buttons.button(QDialogButtonBox.StandardButton.Ok).setText("적용")
            buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("취소")
            def accept():
                if start.date() > end.date():
                    error.show()
                else:
                    dialog.accept()
            buttons.accepted.connect(accept)
            buttons.rejected.connect(dialog.reject)
            form.addRow(buttons)
            if dialog.exec() != QDialog.DialogCode.Accepted:
                self.combo.setCurrentIndex(self.combo.findData(self.key))
                return
            self.custom_start, self.custom_end = start.date().toPython(), end.date().toPython()
        self.key = key
        self.changed.emit()

    def set_key(self, key):
        self.key = key
        self.combo.setCurrentIndex(self.combo.findData(key))
        self.changed.emit()

    def reset(self):
        self.set_key(self.default)

    def bounds(self):
        today = datetime.now(SEOUL).date()
        if self.key == "all":
            return None, None
        if self.key == "custom":
            return self.custom_start, self.custom_end
        start = {"today": today, "week": today - timedelta(days=6),
                 "month": today.replace(day=1), "year": today.replace(month=1, day=1)}[self.key]
        return start, today

    def matches(self, day):
        start, end = self.bounds()
        return start is None or (day is not None and start <= day <= end)

    def description(self):
        start, end = self.bounds()
        return f"{start:%Y.%m.%d} ~ {end:%Y.%m.%d}" if start else "전체 기간"
