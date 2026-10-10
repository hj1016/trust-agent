import { Alert } from "@/components/ui/alert";
import { ApiError } from "@/lib/api";
import { errorGuide } from "@/lib/labels";

/** 오류 안내: 한국어 안내 + 서버 설명 + 원래 코드(원인을 숨기지 않는다). */
export function ErrorNotice({ error, fallback, className }: { error: unknown; fallback: string; className?: string }) {
  if (!(error instanceof ApiError)) return <Alert tone="danger" className={className}>{fallback}</Alert>;
  const guide = errorGuide(error.code);
  return (
    <Alert tone="danger" className={className}>
      <p className="font-semibold">{guide}</p>
      {error.detail && error.detail !== guide && <p className="mt-0.5">{error.detail}</p>}
      <p className="mt-0.5 font-mono text-[13px]">오류 코드 {error.code}</p>
    </Alert>
  );
}
