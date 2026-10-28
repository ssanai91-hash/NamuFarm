import type { CatalogSpecies } from "./species-catalog";

// Curated discovery tags. A species can appear under several uses; names not
// listed here remain available in the name and initial search.
// theme/copy are the homepage card text; label stays the category shared with the PC app.
export const purposes = [
  { id: "fruit", label: "열매", theme: "과수정원", copy: "따 먹는 재미", title: "열매 따기", names: "감 개암 구기자 다래 대추 마키베리 매실 모과 무화과 밤 배 보리수 복분자 복숭아 블랙초코베리 블랙커런트 블루베리 비파 뽕 사과 살구 석류나무 슈퍼블랙베리 앵두나무 오미자 으름 일세다래 자두 체리 키위 포도 포포나무 플루오트 피칸 호두" },
  { id: "hedge", label: "울타리", theme: "울타리", copy: "경계를 푸르게", title: "울타리 만들기", names: "개나리 남천 눈주목 무늬호랑가시 미측백나무 사철나무 에메랄드_그린 은테_사철 조팝나무 주목 쥐똥나무 직립꽝꽝이 철쭉 측백나무 탱자 피라칸타스 해당화 홍가시 화살나무 황금꽝꽝이 황금주목 황금측백 회양목" },
  { id: "flower", label: "꽃", theme: "꽃정원", copy: "봄부터 화사하게", title: "꽃 감상하기", names: "개나리 꽃잔디 능소화 라일락 매실 명자나무 목련 무궁화 미니백일홍 미스김라일락 박태기 백일홍 벚나무 별목련 복숭아 불두화 산수국 산수유 수국 수사해당화 아나벨수국 앵두나무 이팝나무 자귀나무 조팝나무 철쭉 치자나무 칠자화 팜콘수국 피라미드_수국 해당화 홍도화 히아신스" },
  { id: "shade", label: "그늘", theme: "그늘 쉼터", copy: "시원한 여름", title: "그늘 만들기", names: "계수나무 느티나무 단풍나무 메타세쿼이아 목백합 벚나무 상수리나무 소나무 은행나무 자귀나무 자작나무 층층나무 팽나무 회화나무" },
  { id: "garden", label: "정원", theme: "정원수", copy: "마당의 얼굴", title: "정원 꾸미기", names: "구상나무 금송 남천 단풍나무 라일락 목련 무궁화 반송 산딸나무 소사나무 수국 아나벨수국 에메랄드_그린 옥향 은청가문비 자작나무 주목 철쭉 코니카가문비 편백나무 홍가시 화살나무 회양목" },
  { id: "lawn", label: "잔디", theme: "잔디마당", copy: "맨발로 걷는 마당", title: "잔디 깔기", names: "잔디 양잔디 꽃잔디" },
] as const;

export type PurposeId = typeof purposes[number]["id"];

const purposeNames = Object.fromEntries(purposes.map(purpose => [purpose.id, new Set(purpose.names.split(" ").map(name => name.replaceAll("_", " ")))])) as Record<PurposeId, Set<string>>;

export function filterSpeciesByPurpose(species: CatalogSpecies[], purpose: PurposeId): CatalogSpecies[] {
  return species.filter(item => purposeNames[purpose].has(item.name));
}
