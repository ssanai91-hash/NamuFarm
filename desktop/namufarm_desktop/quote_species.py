"""Per-row seedling search using the nursery data already cached on this PC."""
from PySide6.QtCore import QEvent, Qt, Signal
from PySide6.QtWidgets import (QButtonGroup, QDialog, QGridLayout, QHBoxLayout,
    QLabel, QLineEdit, QListWidget, QPushButton, QSizePolicy, QVBoxLayout, QWidget)

from .nursery import INITIALS, initial


class SpeciesNameEditor(QWidget):
    changed = Signal(str)
    pick_requested = Signal()
    activated = Signal()

    def __init__(self, text="", parent=None):
        super().__init__(parent)
        self.setObjectName("quoteSpeciesEditor")
        row = QHBoxLayout(self)
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(6)
        self.input = QLineEdit(text)
        self.input.setPlaceholderText("묘목명")
        self.input.setMaxLength(200)
        self.input.setMinimumWidth(0)
        self.input.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        self.input.setAccessibleName("견적 묘목명")
        self.input.installEventFilter(self)
        self.input.textChanged.connect(self.changed.emit)
        row.addWidget(self.input, 1)
        self.search_button = QPushButton("초성검색")
        self.search_button.setFixedWidth(76)
        self.search_button.setAccessibleName("이 품목의 묘목 초성검색")
        self.search_button.installEventFilter(self)
        self.search_button.clicked.connect(lambda _checked=False: self.pick_requested.emit())
        row.addWidget(self.search_button)

    def eventFilter(self, watched, event):
        if event.type() == QEvent.Type.FocusIn:
            self.activated.emit()
        return super().eventFilter(watched, event)


class QuoteSpeciesPicker(QDialog):
    def __init__(self, names, current="", parent=None, notice=""):
        super().__init__(parent)
        self.setWindowTitle("묘목 초성검색")
        self.resize(680, 540)
        self.names = sorted({str(name).strip() for name in names if str(name).strip()})
        self.selected_initial = ""
        self.selected_name = ""
        self.notice = notice
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("초성을 누르고 묘목을 선택해주세요."))
        self.search = QLineEdit()
        self.search.setPlaceholderText("묘목명 또는 초성 입력")
        self.search.setAccessibleName("묘목 검색")
        layout.addWidget(self.search)
        grid = QGridLayout()
        grid.setSpacing(6)
        self.initial_group = QButtonGroup(self)
        self.initial_group.setExclusive(True)
        for i, title in enumerate(("전체", *INITIALS)):
            choice = QPushButton(title)
            choice.setCheckable(True)
            choice.setChecked(title == "전체")
            choice.setObjectName("filterTab")
            self.initial_group.addButton(choice)
            choice.clicked.connect(lambda checked=False, value=title: self.choose_initial(value))
            grid.addWidget(choice, i // 8, i % 8)
        layout.addLayout(grid)
        self.results = QListWidget()
        self.results.setAccessibleName("검색된 묘목 목록")
        self.results.itemDoubleClicked.connect(lambda _item: self.accept_selection())
        self.results.itemSelectionChanged.connect(self.update_selection)
        layout.addWidget(self.results, 1)
        self.status = QLabel(notice)
        self.status.setWordWrap(True)
        layout.addWidget(self.status)
        actions = QHBoxLayout()
        actions.addStretch()
        cancel = QPushButton("취소")
        cancel.clicked.connect(self.reject)
        actions.addWidget(cancel)
        self.choose_button = QPushButton("묘목 선택")
        self.choose_button.setObjectName("primary")
        self.choose_button.clicked.connect(self.accept_selection)
        self.choose_button.setEnabled(False)
        actions.addWidget(self.choose_button)
        layout.addLayout(actions)
        self.search.textChanged.connect(self.refresh)
        self.refresh()
        matches = self.results.findItems(current, Qt.MatchFlag.MatchExactly)
        if matches:
            self.results.setCurrentItem(matches[0])

    def choose_initial(self, value):
        self.selected_initial = "" if value == "전체" else value
        self.refresh()

    def refresh(self):
        query = self.search.text().strip()
        self.results.clear()
        names = [name for name in self.names
                 if (not self.selected_initial or initial(name) == self.selected_initial)
                 and (not query or query.casefold() in name.casefold()
                      or query in "".join(initial(char) for char in name))]
        self.results.addItems(names)
        self.choose_button.setEnabled(False)
        self.status.setText(self.notice if names else
                            "검색 결과가 없습니다. 묘목·농원에서 동기화하거나 묘목명을 직접 입력해주세요.")

    def update_selection(self):
        self.choose_button.setEnabled(self.results.currentItem() is not None)

    def accept_selection(self):
        item = self.results.currentItem()
        if item is not None:
            self.selected_name = item.text()
            self.accept()

