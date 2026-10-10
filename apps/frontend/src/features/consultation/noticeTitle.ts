import { useApplicable } from "./queries";

/**
 * 사람이 읽는 이름: 기존 적용 checklist 조회가 돌려주는 공문 제목. 조회한 공문이 기록된 공문(noticeId)과 같을 때만 쓰고,
 * 다르거나 못 읽으면 코드를 그대로 돌려준다(이름을 지어내지 않는다). 같은 업무일·공문군은 화면의 다른 영역과 캐시를 공유한다.
 */
export function useNoticeTitle(familyId: string, businessDate: string, noticeId: string | null | undefined): string {
  const applicable = useApplicable(familyId, businessDate);
  const notice = applicable.data?.selectedNotice;
  if (notice && (!noticeId || notice.noticeId === noticeId)) return notice.title;
  if (applicable.isPending) return "공문 정보를 불러오는 중…";
  return noticeId ?? familyId;
}
