import React from "react";

interface RiskIndicatorProps {
  score: number;
}

const RiskIndicator: React.FC<RiskIndicatorProps> = ({ score }) => {
  const displayScore = Math.min(score, 100);

  const getColor = (s: number) => {
    if (s >= 80) return "bg-red-500";
    if (s >= 70) return "bg-yellow-500";
    return "bg-green-500";
  };

  const colorClass = getColor(displayScore);
  const widthPercentage = `${displayScore}%`;

  return (
    <div className="flex items-center space-x-2">
      <div className="w-full bg-gray-600 rounded-full h-4 relative overflow-hidden">
        <div
          className={`h-full rounded-full transition-all duration-300 ${colorClass}`}
          style={{ width: widthPercentage }}
        ></div>
        <span className="absolute inset-0 flex items-center justify-center text-xs font-semibold text-white mix-blend-difference">
          {Math.round(displayScore)}
        </span>
      </div>
    </div>
  );
};

export default RiskIndicator;
