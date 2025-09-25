// src/api/logsApi.ts
import { http } from "./http";
import type { Log } from "../types/index"; // 로그 타입을 정의할 파일 (다음 단계에서 생성)

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

export const getAnomalyLogs = async (): Promise<Log[]> => {
  try {
    // 서버 응답의 실제 타입을 any로 받아서 처리합니다.
    const response = await http.get<any>("/api/anomalies");

    // ✅ 서버가 'results' 키에 배열을 담아준다고 가정합니다.
    //    네트워크 탭에서 확인한 실제 키 이름으로 변경하세요.
    if (response.data && Array.isArray(response.data.results)) {
      return response.data.results;
    }

    // 만약 응답이 이미 배열이라면 그대로 반환합니다.
    if (Array.isArray(response.data)) {
      return response.data;
    }

    // 그 외의 경우, 에러를 방지하기 위해 빈 배열을 반환합니다.
    console.warn("API response was not an array:", response.data);
    return [];
  } catch (error) {
    console.error("Anomaly 로그를 가져오는 데 실패했습니다:", error);
    throw error;
  }
};
