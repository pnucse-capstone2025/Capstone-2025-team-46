// src/api/mockApi.ts

import type { Log } from "../types";

const createMockLog = (id: number): Log => {
  const isAnomaly = Math.random() < 0.2;
  const score = isAnomaly ? 0.6 + Math.random() * 0.4 : Math.random() * 0.5;

  return {
    id: id,
    // [수정] 'file'을 'file_name'으로 변경하여 Log 타입과 일치시킵니다.
    file_name: `log_file_${id}.json`,
    timestamp: new Date(Date.now() - id * 60000).toISOString(),
    modality: ["sensor", "network", "process"][id % 3],
    anomaly_score: parseFloat(score.toFixed(5)),
    is_anomaly: isAnomaly,
  };
};

export const getMockAnomalyLogs = (): Promise<Log[]> => {
  return new Promise((resolve) => {
    setTimeout(() => {
      const mockLogs: Log[] = [];
      for (let i = 1; i <= 100; i++) {
        mockLogs.push(createMockLog(i));
      }
      resolve(mockLogs);
    }, 500);
  });
};
