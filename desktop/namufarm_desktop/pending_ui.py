"""Small administrator view for pending work and preserved recovery records."""
from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QDialog, QHBoxLayout, QLabel, QMessageBox, QPlainTextEdit,
    QPushButton, QTableWidget, QTableWidgetItem, QVBoxLayout, QHeaderView)


def describe(entry):
    body = entry.get("body") or {}
    lines = [entry.get("title") or entry.get("reason", "복구 기록"),
             "대상: " + str(entry.get("reference", "")), entry.get("detail", "")]
    candidates = [body, body.get("payload"), body.get("quoteWrite"), body.get("localQuote")]
    candidates += body.get("quotes", [])
    candidates += body.get("sales", [])
    candidates += [op.get("payload", {}) for op in body.get("operations", [])]
    for candidate in candidates:
        if not isinstance(candidate, dict):
            continue
        draft = candidate.get("draft") or candidate.get("personalData", {}).get("order") or candidate
        if candidate.get("lastError"):
            lines.append("마지막 확인 내용: " + str(candidate["lastError"]))
        for key, title in (("customerName", "고객"), ("requestReceiptNumber", "견적번호"),
                           ("orderNumber", "주문번호"), ("status", "상태"), ("note", "요청사항"),
                           ("smsStatusMessage", "문자 결과")):
            if draft.get(key):
                lines.append(title + ": " + str(draft[key]))
        for item in draft.get("items", []):
            lines.append(f"• {item.get('species', '')} {item.get('specification', '')} · {item.get('quantity', 0)}주 · 단가 {item.get('unitPrice', 0):,}원")
    nursery = body.get("nursery") or {}
    if nursery:
        lines.append(f"보관한 농원 {len(nursery.get('farms', []))}건 · 묘목 {len(nursery.get('species', []))}건 · 재고 {len(nursery.get('inventory', []))}건")
    if body.get("pendingDelivery"):
        lines.append("발송 완료 여부를 확인하지 못한 기록입니다.")
    return "\n".join(line for line in lines if line)


class PendingDialog(QDialog):
    def __init__(self, owner):
        super().__init__(owner)
        self.owner = owner
        self.history = False
        self.items = []
        self.setWindowTitle("대기 내역 · 복구 기록")
        self.resize(900, 640)
        root = QVBoxLayout(self)
        root.setContentsMargins(20, 20, 20, 20)
        root.setSpacing(12)
        self.notice = QLabel("대기가 있어도 새 견적은 서버에서 계속 받습니다. 이 화면은 문자·메일을 재발송하지 않습니다.")
        self.notice.setWordWrap(True)
        root.addWidget(self.notice)
        self.table = QTableWidget(0, 3)
        self.table.setHorizontalHeaderLabels(["종류", "대상", "남은 확인"])
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QTableWidget.SelectionMode.SingleSelection)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.table.itemSelectionChanged.connect(self.selected)
        root.addWidget(self.table, 1)
        self.details = QPlainTextEdit()
        self.details.setReadOnly(True)
        self.details.setMaximumHeight(180)
        root.addWidget(self.details)
        self.status = QLabel("")
        self.status.setWordWrap(True)
        root.addWidget(self.status)
        row = QHBoxLayout()
        self.refresh_button = QPushButton("새로고침")
        self.refresh_button.clicked.connect(self.refresh)
        self.history_button = QPushButton("보관 기록 보기")
        self.history_button.clicked.connect(self.toggle_history)
        self.check_button = QPushButton("서버에서 확인")
        self.check_button.clicked.connect(lambda: self.act(False))
        self.release_button = QPushButton("기록 보관 후 대기 해제")
        self.release_button.clicked.connect(lambda: self.act(True))
        self.retry_button = QPushButton("저장 다시 시도")
        self.retry_button.clicked.connect(self.retry)
        self.close_button = QPushButton("닫기")
        self.close_button.clicked.connect(self.reject)
        for button in (self.refresh_button, self.history_button, self.check_button, self.release_button, self.retry_button, self.close_button):
            row.addWidget(button)
        root.addLayout(row)
        self.refresh()

    def refresh(self):
        if self.owner.task:
            return
        def rows():
            if self.history:
                records = self.owner.manager.vault.preference("server_recovery_archive", []) or []
                return [{**record, "title": "보관 기록", "reference": "", "detail": record["reason"]}
                        for record in reversed(records)]
            return self.owner.manager.pending_items()
        self.owner.run_job("대기 내역 확인 중…", rows, self.fill, False, self.failed)

    def fill(self, items):
        from .app import timestamp
        self.items = items
        self.table.setRowCount(len(items))
        for index, item in enumerate(items):
            values = (item.get("title", ""), timestamp(item["at"]) if self.history else item.get("reference", ""), item.get("detail", ""))
            for column, value in enumerate(values):
                self.table.setItem(index, column, QTableWidgetItem(str(value)))
        self.notice.setText(("미반영 수정과 관리자 해제 내역을 보관한 기록입니다." if self.history else
                            "대기가 있어도 서버 동기화와 다른 견적 처리는 계속할 수 있습니다.") + f"  {len(items):,}건")
        if items:
            self.table.selectRow(0)
        self.selected()

    def selected(self):
        index = self.table.currentRow()
        entry = self.items[index] if 0 <= index < len(self.items) else None
        self.details.setPlainText(describe(entry) if entry else "선택한 기록이 없습니다.")
        enabled = entry is not None and not self.history and not self.owner.task
        self.check_button.setEnabled(enabled)
        self.release_button.setEnabled(enabled)
        body = (entry or {}).get("body") or {}
        retryable = body.get("quoteWrite") or any(op.get("action") == "save_sale" for op in body.get("operations", []))
        retryable = retryable or (body.get("kind") == "outbox" and body.get("body", {}).get("action") == "save_sale")
        self.retry_button.setEnabled(bool(self.history and retryable and not self.owner.task))

    def retry(self):
        index = self.table.currentRow()
        if not self.history or self.owner.task or not 0 <= index < len(self.items):
            return
        item = self.items[index]
        if QMessageBox.question(self, "보관 기록 복구",
                "이 기록의 견적·판매 저장을 다시 시도할까요?\n원래 작업 번호와 버전을 사용하며 충돌하면 서버 내용을 유지합니다.\n문자·메일은 보내지 않습니다.",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
            return
        self.retry_button.setEnabled(False)
        self.close_button.setEnabled(False)
        def done(value):
            self.close_button.setEnabled(True)
            self.status.setText(value.get("transferNotice") or "저장 결과를 확인했습니다.")
            self.refresh()
            self.owner.refresh_storage_status_from_vault()
        self.owner.run_job("보관 기록을 원래 작업 번호로 저장 확인 중…",
                           lambda: self.owner.manager.retry_recovery_record(item["id"]), done, False, self.failed)

    def toggle_history(self):
        if self.owner.task:
            return
        self.history = not self.history
        self.history_button.setText("대기 내역 보기" if self.history else "보관 기록 보기")
        self.refresh()

    def act(self, release):
        index = self.table.currentRow()
        if self.history or self.owner.task or not 0 <= index < len(self.items):
            return
        item = self.items[index]
        if release:
            note = ("선택한 기록을 보관하고 대기를 해제할까요?\n서버 저장이나 발송 성공으로 처리하지 않으며 자동 재발송하지 않습니다.\n"
                    "문자·메일은 실제 발송내역을 먼저 확인해주세요.\n"
                    "농원·묘목·재고는 연결된 수정도 함께 보관하고 서버 자료로 복원합니다.")
            if QMessageBox.question(self, "관리자 확인", note,
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
        self.check_button.setEnabled(False)
        self.release_button.setEnabled(False)
        self.close_button.setEnabled(False)
        self.status.setText("선택한 기록을 처리하는 중…")
        operation = self.owner.manager.release_pending_item if release else self.owner.manager.check_pending_item
        def done(items):
            self.close_button.setEnabled(True)
            self.status.setText("기록을 보관하고 대기를 해제했습니다." if release else "서버 확인을 마쳤습니다.")
            self.fill(items)
            self.owner.refresh_storage_status_from_vault()
        self.owner.run_job("대기 내역 처리 중…", lambda: operation(item), done, False, self.failed)

    def failed(self, error):
        self.close_button.setEnabled(True)
        self.status.setText(error)
        self.selected()

    def reject(self):
        if not self.owner.task:
            super().reject()
