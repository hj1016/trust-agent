import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, request } from "@/lib/api";
import type {
  AiPreparationResponse, ApplicableState, ApplicationSummary, Confirmation, Consultation, Preparation, ProposalSummary,
} from "@/lib/types";

export function useConsultations() {
  return useQuery({
    queryKey: ["consultations"],
    queryFn: async () => (await request<{ consultations: Consultation[] }>("/api/v1/consultations")).consultations,
  });
}

export function useConsultation(consultationId: string | undefined) {
  return useQuery({
    queryKey: ["consultation", consultationId],
    enabled: Boolean(consultationId),
    queryFn: () => request<Consultation>(`/api/v1/consultations/${encodeURIComponent(consultationId!)}`),
  });
}

export function useApplications(enabled: boolean) {
  return useQuery({
    queryKey: ["applications"],
    enabled,
    queryFn: async () => (await request<{ applications: ApplicationSummary[] }>("/api/v1/applications")).applications,
  });
}

export function useApplicable(familyId: string, businessDate: string) {
  return useQuery({
    queryKey: ["applicable", familyId, businessDate],
    queryFn: () =>
      request<ApplicableState>(`/api/v1/internal-policy/checklists/${encodeURIComponent(familyId)}/applicable?businessDate=${encodeURIComponent(businessDate)}`),
  });
}

/** 이 상담 건에 연결된 최신 준비안. 없으면 null. */
export function usePreparation(consultationId: string) {
  return useQuery({
    queryKey: ["preparation", consultationId],
    queryFn: async () => {
      try {
        return await request<Preparation>(`/api/v1/consultations/${encodeURIComponent(consultationId)}/preparation`);
      } catch (error) {
        if (error instanceof ApiError && error.code === "PREPARATION_NOT_FOUND") return null;
        throw error;
      }
    },
  });
}

export function useConfirmations(consultationId: string) {
  return useQuery({
    queryKey: ["confirmations", consultationId],
    queryFn: async () =>
      (await request<{ confirmations: Confirmation[] }>(`/api/v1/consultations/${encodeURIComponent(consultationId)}/confirmations`)).confirmations,
  });
}

export function useCreateConsultation() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (applicationId: string) => request<Consultation>("/api/v1/consultations", { json: { applicationId } }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["consultations"] }),
  });
}

export function useRequestPreparation(consultationId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (businessDate: string) =>
      request<AiPreparationResponse>(`/api/v1/consultations/${encodeURIComponent(consultationId)}/preparation`, { json: { businessDate } }),
    onSettled: () => client.invalidateQueries({ queryKey: ["preparation", consultationId] }),
  });
}

export function useConfirm(consultationId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { preparationId: string; familyId: string; ruleVersionIds: string[] }) =>
      request<Confirmation>(`/api/v1/consultations/${encodeURIComponent(consultationId)}/confirmations`, { json: body }),
    onSettled: () => client.invalidateQueries({ queryKey: ["confirmations", consultationId] }),
  });
}

export function useProposals() {
  return useQuery({
    queryKey: ["proposals"],
    queryFn: async () => (await request<{ proposals: ProposalSummary[] }>("/api/v1/reviews/proposals")).proposals,
  });
}
