// src/types/index.ts

// API 응답에 맞춰 로그 데이터의 타입을 정의합니다.
export interface Log {
  id: number;
  file_name: string;
  timestamp: string;
  modality: string;
  anomaly_score: number;
  is_anomaly: boolean;
  normalized_score?: number;
  display_score?: number;
}
