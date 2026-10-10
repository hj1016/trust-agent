import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const alertVariants = cva("rounded-xl border px-4 py-3 text-sm leading-relaxed", {
  variants: {
    tone: {
      info: "border-info/20 bg-info-soft text-info",
      hold: "border-hold/25 bg-hold-soft text-hold",
      danger: "border-danger/25 bg-danger-soft text-danger",
      neutral: "border-line bg-muted text-ink-soft",
      ready: "border-ready/25 bg-ready-soft text-ready",
    },
  },
  defaultVariants: { tone: "neutral" },
});

export function Alert({ className, tone, ...props }: React.HTMLAttributes<HTMLDivElement> & VariantProps<typeof alertVariants>) {
  return <div role="status" className={cn(alertVariants({ tone }), className)} {...props} />;
}
