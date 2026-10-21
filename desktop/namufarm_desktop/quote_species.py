"""Per-row seedling search with an offline base catalog and cached nursery names."""
from PySide6.QtCore import QEvent, Qt, Signal
from PySide6.QtWidgets import (QButtonGroup, QDialog, QGridLayout, QHBoxLayout,
    QLabel, QLineEdit, QListWidget, QPushButton, QSizePolicy, QVBoxLayout, QWidget)

from .nursery import INITIALS, initial


# Website catalog (website/lib/species-data.json), with the same name grouping as
# species-catalog.ts. Kept in Python so the base list is also bundled in the EXE.
DEFAULT_SPECIES_NAMES = (
    "가죽나무", "감", "개나리", "개암", "계수나무", "고로쇠",
    "골담초", "구기자", "구상나무", "금송", "꽃잔디", "낙우송",
    "남경도", "남천", "눈주목", "느티나무", "능소화", "다래",
    "단풍나무", "담쟁이덩굴", "대왕송", "대추", "등나무", "때죽나무",
    "떡갈잎수국", "라일락", "러시아올리브", "마가목", "마로니에", "마키베리",
    "만병초", "매실", "맥문동", "메타세쿼이아", "명자나무", "모감주나무",
    "모과", "목련", "목백합", "무궁화", "무늬호랑가시", "무스카리",
    "무화과", "문그로우", "미니백일홍", "미산딸나무", "미스김라일락", "미측백나무",
    "민들레", "바이오체리", "박태기", "반송", "밤", "배",
    "백일홍", "벚나무", "별목련", "보리수", "복분자", "복숭아",
    "분꽃", "불두화", "블랙초코베리", "블랙커런트", "블루매트", "블루베리",
    "블루엔젤", "비파", "뽕", "사계패랭이", "사과", "사철나무",
    "산딸나무", "산사나무", "산수국", "산수유", "산초", "살구",
    "상수리나무", "석류나무", "설중매", "소나무", "소사나무", "수국",
    "수사해당화", "수생식물", "수선화", "슈가메이플", "슈퍼블랙베리", "스카이로켓",
    "스트로브잣나무", "아나벨수국", "아마릴리스", "앵두나무", "양잔디", "엄나무",
    "에메랄드 그린", "오가피", "오미자", "오이도", "옥향", "옻나무",
    "유칼립투스", "으름", "은목서", "은청가문비", "은테 사철", "은행나무",
    "이팝나무", "일세다래", "자귀나무", "자두", "자작나무", "잔디",
    "잣나무", "전나무", "조팝나무", "좀눈향", "주목", "쥐똥나무",
    "직립꽝꽝이", "직송", "짐드롭", "철쭉", "체리", "측백나무",
    "층층나무", "치자나무", "칠자화", "칼슘나무", "코니카가문비", "키위",
    "탱자", "팜콘수국", "팥배나무", "팽나무", "편백나무", "포도",
    "포포나무", "플루오트", "피라미드 수국", "피라칸타스", "피칸", "하이드랑게아",
    "해당화", "해송", "헛개나무", "호두", "홀리", "홍가시",
    "홍도화", "화살나무", "황금꽝꽝이", "황금주목", "황금측백", "황칠나무",
    "회양목", "회화나무", "히아신스",
)


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
        self.names = sorted(set(DEFAULT_SPECIES_NAMES) |
                            {str(name).strip() for name in names if str(name).strip()})
        self.selected_initial = ""
        self.selected_name = ""
        self.notice = "기본 묘목 목록이 포함되어 있습니다. " + notice
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
        self.status = QLabel(self.notice)
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
                            "검색 결과가 없습니다. 검색어·초성을 바꾸거나 묘목명을 직접 입력해주세요.")

    def update_selection(self):
        self.choose_button.setEnabled(self.results.currentItem() is not None)

    def accept_selection(self):
        item = self.results.currentItem()
        if item is not None:
            self.selected_name = item.text()
            self.accept()

