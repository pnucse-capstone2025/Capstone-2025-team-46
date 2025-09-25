import React, { useState, useContext, useMemo } from "react";
import LogsTable from "../components/LogsTable";
import { LogContext } from "../context/LogContext/LogContext";

const LogsPage: React.FC = () => {
  const { logs, isLoading, error } = useContext(LogContext);
  const [showOnlyAnomalies, setShowOnlyAnomalies] = useState(false);
  // ✅ [추가] 선택된 modality를 저장하기 위한 상태
  const [selectedModality, setSelectedModality] = useState("All");

  // ✅ [추가] 전체 로그에서 고유한 modality 목록을 추출 (성능을 위해 useMemo 사용)
  const modalities = useMemo(
    () => ["All", ...Array.from(new Set(logs.map((log) => log.modality)))],
    [logs]
  );

  // ✅ [수정] 필터 로직에 modality 기준 추가
  const filteredLogs = logs.filter((log) => {
    const isAnomalyMatch = !showOnlyAnomalies || log.is_anomaly === true;
    const isModalityMatch =
      selectedModality === "All" || log.modality === selectedModality;
    return isAnomalyMatch && isModalityMatch;
  });

  return (
    <div className="p-6 space-y-6">
      {/* 페이지 헤더와 필터 UI */}
      <div className="flex flex-col sm:flex-row justify-between sm:items-center gap-4">
        <h1 className="text-3xl font-bold text-gray-200">All Logs</h1>

        {/* 필터들을 담는 컨테이너 */}
        <div className="flex items-center gap-6">
          {/* ✅ [추가] Modality 필터 드롭다운 */}
          <div>
            <label
              htmlFor="modality-filter"
              className="mr-3 text-lg font-medium text-gray-300"
            >
              Modality
            </label>
            <select
              id="modality-filter"
              value={selectedModality}
              onChange={(e) => setSelectedModality(e.target.value)}
              className="bg-gray-700 text-white rounded-md p-2 border border-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              {modalities.map((modality) => (
                <option key={modality} value={modality}>
                  {modality}
                </option>
              ))}
            </select>
          </div>

          {/* 기존 Anomaly 필터 토글 스위치 */}
          <label
            htmlFor="anomaly-toggle"
            className="flex items-center cursor-pointer"
          >
            <span className="mr-3 text-lg font-medium text-gray-300">
              Show Anomalies Only
            </span>
            <div className="relative">
              <input
                id="anomaly-toggle"
                type="checkbox"
                className="sr-only"
                checked={showOnlyAnomalies}
                onChange={() => setShowOnlyAnomalies(!showOnlyAnomalies)}
              />
              <div className="block bg-gray-600 w-14 h-8 rounded-full"></div>
              <div
                className={`
                  dot absolute left-1 top-1 bg-white w-6 h-6 rounded-full transition-transform duration-300 ease-in-out
                  ${
                    showOnlyAnomalies
                      ? "transform translate-x-6 bg-green-400"
                      : ""
                  }
                `}
              ></div>
            </div>
          </label>
        </div>
      </div>

      {/* 필터링된 로그와 상태를 LogsTable에 전달 */}
      <LogsTable logs={filteredLogs} isLoading={isLoading} error={error} />
    </div>
  );
};

export default LogsPage;
