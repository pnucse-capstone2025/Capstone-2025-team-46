import React from "react";
import type { Log } from "../types";

// ✅ display_score를 포함하도록 props 타입 확장 (Log 타입에 추가 필요)
interface MostRiskyLogCardProps {
  log: (Log & { display_score?: number }) | null;
}

const MostRiskyLogCard: React.FC<MostRiskyLogCardProps> = ({ log }) => {
  if (!log) {
    return /* ... No data ... */;
  }

  // ✅ [수정] anomaly_score 대신 display_score를 사용
  const riskPercentage = Math.round(log.display_score || 0);
  const isAnomaly = log.is_anomaly === true;

  return (
    <div className="bg-gray-800 p-4 rounded-lg border-l-4 border-red-500 space-y-3">
      <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
        <p className="text-gray-400">Time</p>
        <p className="text-white">{new Date(log.timestamp).toLocaleString()}</p>

        <p className="text-gray-400">Risk Level</p>
        <p className="text-white font-semibold">{riskPercentage}</p>
      </div>
    </div>
  );
};

export default MostRiskyLogCard;
