import React from "react";
import type { Log } from "../types";

// ✅ display_score를 포함하도록 props 타입 확장 (Log 타입에 추가 필요)
interface TopRiskLogListProps {
  logs: (Log & { display_score?: number })[];
}

const TopRiskLogList: React.FC<TopRiskLogListProps> = ({ logs }) => {
  if (!logs || logs.length === 0) {
    return /* ... No risk logs ... */;
  }

  return (
    <div className="space-y-3">
      {logs.map((log) => (
        <div key={log.id} /* ... */>
          {/* ... */}
          <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
            {/* ... */}
            <p className="text-gray-400">Time</p>
            <p className="text-white">
              {new Date(log.timestamp).toLocaleString()}
            </p>
            {/* ... */}
            <p className="text-gray-400">Risk Level</p>
            <p className="text-white font-semibold">
              {/* ✅ [수정] anomaly_score 대신 display_score를 사용 */}
              {Math.round(log.display_score || 0)}
            </p>
          </div>
        </div>
      ))}
    </div>
  );
};

export default TopRiskLogList;
