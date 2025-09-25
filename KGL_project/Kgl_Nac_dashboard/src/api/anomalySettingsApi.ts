// src/api/anomalySettingsApi.ts
import { http } from "./http";

export type AnomalyPercent = 1 | 3 | 5;

export async function fetchAnomalyThreshold(): Promise<AnomalyPercent> {
  const { data } = await http.get("/settings/anomaly-threshold");
  // 백엔드가 { threshold: number } 형태라고 가정
  const t = Number(data?.threshold);
  return ([1, 3, 5].includes(t) ? t : 1) as AnomalyPercent;
}

export async function updateAnomalyThreshold(threshold: AnomalyPercent) {
  await http.post("/settings/anomaly-threshold", { threshold });
}
