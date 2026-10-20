"""Small numeric waybill keypad; saving remains the sales dialog's responsibility."""
from PySide6.QtCore import Qt, QRegularExpression
from PySide6.QtGui import QRegularExpressionValidator
from PySide6.QtWidgets import QDialog, QVBoxLayout, QHBoxLayout, QGridLayout, QLabel, QLineEdit, QPushButton

from .commerce import normalize_tracking
from .errors import UserError


class TrackingNumberDialog(QDialog):
    def __init__(self, value="", parent=None):
        super().__init__(parent)
        self.setWindowTitle("송장번호 입력")
        self.setObjectName("trackingInputDialog")
        self.setWindowModality(Qt.WindowModality.WindowModal)
        self.setStyleSheet("QDialog#trackingInputDialog{background:#f5f8f6;}"
                           "QDialog#trackingInputDialog QLabel{font-size:15px;color:#193c32;}"
                           "QDialog#trackingInputDialog QLineEdit{font-size:24px;min-height:32px;max-height:32px;padding:6px;}"
                           "QDialog#trackingInputDialog QPushButton{font-size:22px;min-height:44px;max-height:44px;padding:0 10px;}"
                           "QDialog#trackingInputDialog QPushButton[keypadAction=\"true\"]{font-size:16px;min-height:34px;max-height:34px;}"
                           "QDialog#trackingInputDialog QPushButton#applyNumber{background:#25634b;color:white;font-weight:700;}")
        self.number = value
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("로젠 송장번호 입력"))
        self.entry = QLineEdit(value)
        self.entry.setAccessibleName("송장번호 숫자와 하이픈 입력")
        self.entry.setPlaceholderText("123-4567-8901")
        self.entry.setMaxLength(20)
        self.entry.setValidator(QRegularExpressionValidator(QRegularExpression("[0-9-]{0,20}"), self.entry))
        layout.addWidget(self.entry)
        self.hint = QLabel("하이픈을 제외한 숫자 11자리를 입력하세요.")
        self.hint.setWordWrap(True)
        layout.addWidget(self.hint)
        keys = QGridLayout()
        for index, text in enumerate(("1", "2", "3", "4", "5", "6", "7", "8", "9", "-", "0", "⌫")):
            key = QPushButton(text)
            key.setAutoDefault(False)
            key.setFocusPolicy(Qt.FocusPolicy.NoFocus)
            if text == "⌫":
                key.setAccessibleName("한 글자 지우기")
                key.clicked.connect(self.entry.backspace)
            else:
                key.clicked.connect(lambda _checked=False, value=text: self.entry.insert(value))
            keys.addWidget(key, index // 3, index % 3)
        layout.addLayout(keys)
        actions = QHBoxLayout()
        for text, callback in (("전체 지우기", self.entry.clear), ("취소", self.reject), ("입력", self.accept)):
            key = QPushButton(text)
            key.setProperty("keypadAction", True)
            key.setAutoDefault(False)
            key.clicked.connect(callback)
            if text == "입력":
                key.setObjectName("applyNumber")
                key.setDefault(True)
            actions.addWidget(key)
        layout.addLayout(actions)
        self.resize(360, self.sizeHint().height())
        self.entry.setFocus()
        self.entry.setCursorPosition(len(value))

    def showEvent(self, event):
        super().showEvent(event)
        if self.parentWidget():
            self.move(self.parentWidget().mapToGlobal(self.parentWidget().rect().center()) - self.rect().center())

    def accept(self):
        try:
            raw = self.entry.text().strip()
            self.number = normalize_tracking(raw) if raw else ""
        except UserError as error:
            self.hint.setText(str(error))
            self.entry.setFocus()
            return
        super().accept()
