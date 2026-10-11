import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva("inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-semibold", {
  variants: {
    tone: {
      neutral: "bg-muted text-ink-soft",
      ready: "bg-ready-soft text-ready",
      hold: "bg-hold-soft text-hold",
      partial: "bg-brand-soft text-brand-ink",
      danger: "bg-danger-soft text-danger",
      info: "bg-info-soft text-info",
      brand: "bg-brand text-brand-ink",
    },
  },
  defaultVariants: { tone: "neutral" },
});

export function Badge({ className, tone, ...props }: React.HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}
