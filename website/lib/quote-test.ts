export const testRequestPrefix = "[홈페이지 테스트 · 실제 견적 아님]";
export const testNotice = "현재 테스트 중입니다. 실제 견적 요청은 접수하지 않습니다.";
export function testRequestMessage(message: string) {
  return `${testRequestPrefix}\n${message.trim()}`.trim();
}
