// src/api/logsApi.ts
import { http } from "../api/http";

export type LogKind = "network" | "sensor" | "touch";
export type AnomalyPercent = 1 | 3 | 5;

export interface LogsQuery {
  type?: LogKind; // 없으면 전체
  limit?: number; // 기본 100
  afterTs?: number; // 증분 조회
  cursor?: string; // 페이지네이션
  anomalyPercent?: AnomalyPercent; // 서버 필터(상위 n%)
}

export interface BehaviorLog {
  ts?: number | string;
  seq?: number;
  type?: LogKind | string; // 서버가 type 필드를 넣는 경우 대비
  [k: string]: any;
}

export interface LogsResponse<T = BehaviorLog> {
  items: T[];
  nextCursor?: string | null;
}

export async function fetchBehaviorLogs<T = BehaviorLog>(
  params: LogsQuery = {}
): Promise<LogsResponse<T>> {
  const { data } = await http.get("/api/behavior-logs/", { params });

  // 서버가 배열만 주는 경우: 래핑해서 반환
  if (Array.isArray(data)) {
    return { items: data as T[], nextCursor: null };
  }
  return data as LogsResponse<T>;
}

// (선택) 단건/배치 수집용 POST가 필요하면 사용
export async function ingestBehaviorLog(item: Record<string, any>) {
  const { data } = await http.post("/api/behavior-logs/", item);
  return data;
}

export interface Log {
  id: number;
  file_name: string;
  timestamp: string;
  modality: string;
  anomaly_score: number;
  is_anomaly: boolean;
  // Django 모델에 정의된 다른 필드가 있다면 여기에 추가합니다.
}
