export type PlantingSource = { label: string; url: string };
export type PlantingGuide = {
  name: string;
  plantingMonths: readonly number[];
  preparationMonths: readonly number[];
  timing: string;
  reason: string;
  preparation: string;
  caution: string;
  sources: readonly PlantingSource[];
};

export const plantingReviewedAt = "2026-10-24";
const fruitTiming = {
  label: "농촌진흥청 · 과수나무 심는 시기",
  url: "https://www.nongsaro.go.kr/portal/ps/psz/psza/contentSub.ps?cntntsNo=208780&menuId=PS00078",
};
// Month buckets are editorial discovery windows, not a nationwide planting
// calendar. Korean thaw/leaf-fall/freeze conditions take precedence. Overseas
// RHS references support plant care only; their UK months are not imported.
export const plantingGuides: readonly PlantingGuide[] = [
  {
    name: "블루베리", plantingMonths: [3, 4], preparationMonths: [2, 10, 11],
    timing: "국내에서는 땅이 풀리는 이른 봄 심기를 먼저 검토해요. 지역과 품종, 포트묘 상태에 따라 시기를 확인해주세요.",
    reason: "이른 봄은 새싹이 본격적으로 자라기 전에 묘목을 자리 잡게 할 시기예요. 흙이 녹았는지 먼저 살펴봐요.",
    preparation: "봄에 심을 자리의 흙과 물 빠짐을 미리 살펴보세요. 블루베리는 산성 토양을 준비하는 일이 중요해요.",
    caution: "일반 정원 흙에 바로 심기보다 토양 산도와 배수를 확인하세요. 품종의 추위 적응성과 물 관리도 함께 상담해주세요.",
    sources: [{ label: "농촌진흥청 · 블루베리 심는 방법", url: "https://www.nongsaro.go.kr/portal/ps/psb/psby/vodPlay.ps?mvpClipNo=3&mvpNo=541" }],
  },
  {
    name: "매실", plantingMonths: [3, 10, 11], preparationMonths: [2, 9],
    timing: "봄에는 땅이 풀리고 싹이 트기 전, 가을에는 잎이 떨어진 뒤 땅이 얼기 전을 살펴봐요. 추운 지역은 봄 심기를 우선 상담하세요.",
    reason: "낙엽과수는 잎이 없고 생장이 쉬는 때를 식재 시기로 검토해요. 달력보다 묘목의 싹과 낙엽 상태가 먼저예요.",
    preparation: "심을 자리의 배수와 공간을 확인하고, 지역에 맞는 품종을 미리 골라보세요.",
    caution: "가을에 심으면 겨울 추위와 건조 피해를 살펴야 해요. 열매를 원한다면 함께 심을 품종과 꽃가루받이 조건도 확인하세요.",
    sources: [fruitTiming, { label: "농촌진흥청 · 매실 재배 안내", url: "https://www.nongsaro.go.kr/portal/ps/psb/psbl/workScheduleDtl.ps?cntntsNo=30658&menuId=PS00087" }],
  },
  {
    name: "사과", plantingMonths: [3, 10, 11], preparationMonths: [2, 9],
    timing: "봄에는 해빙 후 싹이 트기 전, 가을에는 낙엽 후 땅이 얼기 전이 검토 시기예요. 겨울이 춥고 건조한 곳은 봄 심기를 우선해요.",
    reason: "잎과 새가지의 생장이 쉬는 때에 식재를 검토할 수 있어요. 가을에도 잎이 남아 있으면 상태를 먼저 확인해주세요.",
    preparation: "열매를 따고 싶은 시기와 심을 공간을 생각하며 품종과 묘목 크기를 미리 골라보세요.",
    caution: "지역의 추위와 토양 배수를 확인하세요. 품종에 따라 필요한 꽃가루받이 나무와 지지대도 함께 상담해주세요.",
    sources: [fruitTiming, { label: "RHS · 사과 품종과 꽃가루받이", url: "https://www.rhs.org.uk/fruit/apples/grow-your-own" }],
  },
  {
    name: "단풍나무", plantingMonths: [3, 4, 10, 11], preparationMonths: [2, 9],
    timing: "노지 묘목은 봄의 해빙·싹 트기 전이나 가을의 낙엽 뒤를 중심으로 살펴봐요. 포트묘도 동결된 땅과 한파는 피해야 해요.",
    reason: "낙엽 뒤나 새잎이 나오기 전의 상태를 보며 옮겨 심기를 검토해요. 단풍을 보는 계절과 실제 식재 시기는 구분해주세요.",
    preparation: "자랐을 때의 크기와 심을 자리를 미리 정해보세요. 품종에 맞는 햇빛과 바람 조건을 확인하면 좋아요.",
    caution: "물 빠짐이 나쁜 곳과 강한 건조 바람을 주의하세요. 같은 단풍나무라도 품종과 잎 색에 따라 햇빛 조건이 달라요.",
    sources: [{ label: "RHS · 단풍나무 관리 특성", url: "https://www.rhs.org.uk/plants/acer/japanese-maples/growing-guide" }, { label: "RHS · 나무 심기와 토양 조건", url: "https://www.rhs.org.uk/plants/types/trees/planting-trees-shrubs" }],
  },
  {
    name: "대추", plantingMonths: [3, 4], preparationMonths: [2, 10, 11],
    timing: "중·북부 지역은 대체로 3월 하순~4월 상순의 봄 심기를 검토해요. 어린 묘목은 추위 피해 때문에 봄 심기를 주로 권해요.",
    reason: "봄에 심으면 어린 묘목의 겨울 추위 피해를 피하는 데 도움이 돼요. 지역의 해빙 상태와 묘목 상태를 확인해주세요.",
    preparation: "남부의 가을 심기는 지역 조건을 먼저 확인해야 해요. 어린 묘목은 봄 심기를 준비하는 쪽으로 상담해보세요.",
    caution: "추운 지역에서 가을 식재를 서두르지 마세요. 어린 묘목의 월동 대책과 심을 자리의 배수도 확인해주세요.",
    sources: [{ label: "충북농업기술원 · 대추 재식방법", url: "https://ares.chungbuk.go.kr/home/sub.php?menukey=1449" }],
  },
  {
    name: "수국", plantingMonths: [3, 4, 9, 10], preparationMonths: [2, 11],
    timing: "봄·가을의 온화한 때를 중심으로 살펴봐요. 추운 지역의 가을 식재는 서리와 월동 조건을 먼저 확인하세요.",
    reason: "덥고 건조한 때를 피하며 심을 자리를 준비할 수 있어요. 꽃이 피는 시기와 묘목을 심는 시기는 같지 않아요.",
    preparation: "심을 곳의 햇빛과 흙 상태를 미리 살펴보세요. 추위가 시작됐다면 봄 식재를 준비하는 편이 좋아요.",
    caution: "품종에 맞는 햇빛을 확인하고 흙이 너무 마르거나 물이 고이지 않게 관리하세요. 새눈은 늦서리 피해를 주의해야 해요.",
    sources: [{ label: "RHS · 수국 심기와 관리", url: "https://www.rhs.org.uk/plants/hydrangea/shrubby/growing-guide" }],
  },
];

export function koreanMonth(date = new Date()): number {
  return Number(new Intl.DateTimeFormat("en-US", { timeZone: "Asia/Seoul", month: "numeric" }).format(date));
}

export function guidesForMonth(month: number): readonly PlantingGuide[] {
  return plantingGuides.filter(guide => guide.plantingMonths.includes(month) || guide.preparationMonths.includes(month));
}

export function monthGuidance(month: number): string {
  if ([12, 1, 2].includes(month)) return "추운 때에는 얼어 있는 땅을 피하고, 해빙 후 심을 자리와 묘목을 미리 준비해요.";
  if ([5, 6, 7, 8].includes(month)) return "더위·장마 때는 노지 묘목 식재를 서두르지 않아요. 포트묘는 물 관리와 현장 조건을 상담해주세요.";
  if ([9, 10, 11].includes(month)) return "가을 식재는 낙엽·첫서리·월동 조건을 확인해요. 봄 심기를 준비할 묘목도 함께 살펴보세요.";
  return "봄에는 땅이 풀렸는지, 묘목의 싹이 트기 전인지 먼저 확인해주세요.";
}
