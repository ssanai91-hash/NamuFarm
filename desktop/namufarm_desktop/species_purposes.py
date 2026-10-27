# website/lib/species-purposes.ts 와 동일하게 유지
"""Shared category vocabulary and editor defaults; no storage or server calls."""
from dataclasses import dataclass


@dataclass(frozen=True)
class SpeciesPurpose:
    id: str
    label: str
    names: str


PURPOSES = (
    SpeciesPurpose("fruit", "열매", "감 개암 구기자 다래 대추 마키베리 매실 모과 무화과 밤 배 보리수 복분자 복숭아 블랙초코베리 블랙커런트 블루베리 비파 뽕 사과 살구 석류나무 슈퍼블랙베리 앵두나무 오미자 으름 일세다래 자두 체리 키위 포도 포포나무 플루오트 피칸 호두"),
    SpeciesPurpose("hedge", "울타리", "개나리 남천 눈주목 무늬호랑가시 미측백나무 사철나무 에메랄드_그린 은테_사철 조팝나무 주목 쥐똥나무 직립꽝꽝이 철쭉 측백나무 탱자 피라칸타스 해당화 홍가시 화살나무 황금꽝꽝이 황금주목 황금측백 회양목"),
    SpeciesPurpose("flower", "꽃", "개나리 꽃잔디 능소화 라일락 매실 명자나무 목련 무궁화 미니백일홍 미스김라일락 박태기 백일홍 벚나무 별목련 복숭아 불두화 산수국 산수유 수국 수사해당화 아나벨수국 앵두나무 이팝나무 자귀나무 조팝나무 철쭉 치자나무 칠자화 팜콘수국 피라미드_수국 해당화 홍도화 히아신스"),
    SpeciesPurpose("shade", "그늘", "계수나무 느티나무 단풍나무 메타세쿼이아 목백합 벚나무 상수리나무 소나무 은행나무 자귀나무 자작나무 층층나무 팽나무 회화나무"),
    SpeciesPurpose("garden", "정원", "구상나무 금송 남천 단풍나무 라일락 목련 무궁화 반송 산딸나무 소사나무 수국 아나벨수국 에메랄드_그린 옥향 은청가문비 자작나무 주목 철쭉 코니카가문비 편백나무 홍가시 화살나무 회양목"),
    SpeciesPurpose("lawn", "잔디", "잔디 양잔디 꽃잔디"),
)
PURPOSE_LABELS = tuple(p.label for p in PURPOSES)


def normalize_species_name(name: str) -> str:
    return ''.join(c for c in name if not c.isspace() and c != '_').removesuffix('나무')


_PURPOSE_NAMES = {p.id: {normalize_species_name(n) for n in p.names.split(' ')} for p in PURPOSES}


def suggest_purpose_labels(name: str) -> tuple[str, ...]:
    normalized = normalize_species_name(name)
    return tuple(p.label for p in PURPOSES if normalized in _PURPOSE_NAMES[p.id])


@dataclass(frozen=True)
class CategoryParts:
    selected_labels: tuple[str, ...]
    legacy_values: tuple[str, ...]


def parse_category(category: str | None) -> CategoryParts:
    values = [v.strip() for v in (category or '').split(',') if v.strip()]
    return CategoryParts(tuple(label for label in PURPOSE_LABELS if label in values),
                         tuple(v for v in values if v not in PURPOSE_LABELS))


def format_category(selected_labels, legacy_values) -> str:
    # Unknown tokens (even duplicates) survive until the user removes them.
    selected_labels = set(selected_labels)
    return ', '.join([label for label in PURPOSE_LABELS if label in selected_labels] + list(legacy_values))


def category_error(category: str) -> str | None:
    # Apps Script and Kotlin use UTF-16 code units for the existing 100-character limit.
    if len(category.encode('utf-16-le', errors='surrogatepass')) // 2 > 100:
        return '분류가 너무 깁니다. 기존 값을 정리해주세요.'
    if '\n' in category or '\r' in category:
        return '분류에는 줄바꿈을 넣을 수 없어요.'
    return None


def matches_purpose(category: str | None, label: str) -> bool:
    # Suggestions never classify an unsaved row in the list filter.
    return not label or label in parse_category(category).selected_labels
