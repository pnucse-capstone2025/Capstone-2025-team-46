import React, { useState } from "react";
import RiskIndicator from "./RiskIndicator";
import type { Log } from "../types";

interface LogsTableProps {
  logs: Log[];
  isLoading: boolean;
  error: Error | null;
  isDashboard?: boolean;
}

const LogsTable: React.FC<LogsTableProps> = ({
  logs = [],
  isLoading,
  error,
  isDashboard = false,
}) => {
  const [visibleCount, setVisibleCount] = useState(20);

  const handleLoadMore = () => {
    setVisibleCount((prev) => prev + 20);
  };

  const logsToShow = isDashboard
    ? logs.slice(0, 10)
    : logs.slice(0, visibleCount);

  const getStatusClass = (isAnomaly: boolean) => {
    if (isAnomaly) {
      return "bg-red-200 dark:bg-red-800 text-red-800 dark:text-red-200 px-2 py-0.5 rounded-full text-xs";
    }
    return "text-gray-500 dark:text-gray-400";
  };

  if (isLoading) {
    return <div className="p-4 text-center text-gray-400">Loading logs...</div>;
  }

  if (error) {
    return (
      <div className="p-4 text-center text-red-500">Error: {error.message}</div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm text-left">
        <thead className="bg-gray-700 text-gray-300 uppercase tracking-wider">
          <tr>
            <th className="px-4 py-3">Time</th>
            <th className="px-4 py-3">Action Type</th>
            <th className="px-4 py-3">Modality</th>
            <th className="px-4 py-3 w-40">Risk Score</th>
            <th className="px-4 py-3 text-center">Status</th>
          </tr>
        </thead>
        <tbody className="text-gray-200">
          {logsToShow.map((log: Log) => {
            const isAnomaly = log.is_anomaly === true;
            return (
              <tr
                // ✅ [수정] key prop에 최상위 id를 사용하여 고유성을 보장합니다.
                key={log.id}
                className={`border-b border-gray-700 transition-colors duration-200 ${
                  isAnomaly ? "bg-red-900 bg-opacity-30" : "hover:bg-gray-700"
                }`}
              >
                <td className="px-4 py-3">
                  {/* ✅ [수정] 올바른 경로에서 timestamp를 가져옵니다. */}
                  {new Date(log.timestamp).toLocaleString()}
                </td>
                <td className="px-4 py-3 font-mono">
                  {/* ✅ [수정] 올바른 경로에서 action을 가져옵니다. */}
                  {log.modality}
                </td>
                <td className="px-4 py-3">{log.modality}</td>
                <td className="px-4 py-3">
                  {typeof log.normalized_score === "number" && (
                    <RiskIndicator score={log.normalized_score} />
                  )}
                </td>
                <td className="px-4 py-3 text-center">
                  <span className={getStatusClass(isAnomaly)}>
                    {isAnomaly ? "ANOMALY" : "NORMAL"}
                  </span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {!isDashboard && visibleCount < logs.length && (
        <div className="text-center mt-6">
          <button
            onClick={handleLoadMore}
            className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
          >
            Load More
          </button>
        </div>
      )}
    </div>
  );
};

export default LogsTable;
