import React, { createContext, useState, useEffect } from "react";
import type { ReactNode } from "react";
import { getAnomalyLogs } from "../../api/logsApi";
import type { Log } from "../../types";

interface LogContextType {
  logs: Log[];
  isLoading: boolean;
  error: Error | null;
}

export const LogContext = createContext<LogContextType>({
  logs: [],
  isLoading: true,
  error: null,
});

interface LogProviderProps {
  children: ReactNode;
}

export const LogProvider: React.FC<LogProviderProps> = ({ children }) => {
  const [logs, setLogs] = useState<Log[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<Error | null>(null);

  const getSensitivity = (): "all" | "medium" | "low" => {
    const v = localStorage.getItem("anomalySensitivity");
    if (v === "all" || v === "medium" || v === "low") return v;
    return "medium"; // 기본값
  };

  const loadLogs = async (isInitialLoad = false) => {
    if (isInitialLoad) setIsLoading(true);
    try {
      setError(null);
      const data = await getAnomalyLogs();

      if (!data || data.length === 0) {
        setLogs([]);
        return;
      }

      const sensitivity = getSensitivity();

      const processedData = data.map((log) => {
        const modality = log.modality === "unknown" ? "network" : log.modality;
        const originalScore = log.anomaly_score || 0;
        const originalIsAnomaly = log.is_anomaly;

        let normalizedScore = 0;

        switch (modality) {
          case "touch_drag":
            normalizedScore = (originalScore / 0.3) * 100;
            break;
          case "sensor":
            normalizedScore = originalScore * 100;
            break;
          case "touch_pressure":
            if (originalScore > 0) {
              normalizedScore = Math.log10(originalScore + 1) * 150;
            }
            break;
          default:
            normalizedScore = 0;
            break;
        }

        normalizedScore = Math.round(
          Math.max(0, Math.min(normalizedScore, 100))
        );

        // --- 🔹 핵심 수정: Anomaly 로그의 점수대별 가중치 차등 적용 ---
        if (originalIsAnomaly) {
          if (modality === "network") {
            normalizedScore = 100;
          } else {
            if (normalizedScore < 40) {
              normalizedScore += 40; // 0-39점 -> +30점
            } else if (normalizedScore < 70) {
              normalizedScore += 30; // 40-69점 -> +20점
            } else {
              normalizedScore += 20; // 70점 이상 -> +10점
            }
          }

          if (sensitivity === "all" && normalizedScore < 80) {
            const newScore = 80 + (normalizedScore * 15) / 79;
            normalizedScore = Math.round(newScore);
          }
        } else {
          if (normalizedScore >= 80) {
            normalizedScore -= 20;
          }
        }

        normalizedScore = Math.round(
          Math.max(0, Math.min(normalizedScore, 100))
        );
        // -----------------------------------------------------------------

        const meetsSensitivityRule =
          sensitivity === "all" ||
          (sensitivity === "medium" && normalizedScore >= 80) ||
          (sensitivity === "low" && normalizedScore === 100);

        const isNowAnomaly = originalIsAnomaly && meetsSensitivityRule;

        return {
          ...log,
          modality,
          display_score: normalizedScore,
          normalized_score: normalizedScore,
          is_anomaly: isNowAnomaly,
          tag: isNowAnomaly ? "anomaly" : undefined,
          is_tagged_anomaly: isNowAnomaly,
        };
      });

      const sortedLogs = processedData.sort(
        (a, b) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
      );

      setLogs(sortedLogs);
    } catch (err) {
      setError(err as Error);
    } finally {
      if (isInitialLoad) setIsLoading(false);
    }
  };

  useEffect(() => {
    loadLogs(true);
    const intervalId = setInterval(() => loadLogs(false), 30000);

    const onAdminChange = () => loadLogs(false);
    window.addEventListener("local-storage-changed", onAdminChange);

    return () => {
      clearInterval(intervalId);
      window.removeEventListener("local-storage-changed", onAdminChange);
    };
  }, []);

  return (
    <LogContext.Provider value={{ logs, isLoading, error }}>
      {children}
    </LogContext.Provider>
  );
};
