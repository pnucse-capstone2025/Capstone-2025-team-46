import React, { useEffect, useState } from "react";

interface GaugeWidgetProps {
  value: number; // 0~100
  size?: number;
}

const clamp = (v: number, min = 0, max = 100) =>
  Math.max(min, Math.min(max, v));

const GaugeWidget: React.FC<GaugeWidgetProps> = ({ value, size = 200 }) => {
  const [needleAngle, setNeedleAngle] = useState(-90);

  useEffect(() => {
    const safe = clamp(value);
    const targetAngle = (safe / 100) * 180 - 90;
    setNeedleAngle(targetAngle);
  }, [value]);

  // ✅ [추가] SVG 반원의 정확한 둘레 길이를 계산합니다. (π * 반지름)
  const circumference = Math.PI * 90;

  return (
    <div
      className="relative flex flex-col items-center"
      style={{ width: `${size}px` }}
    >
      <div
        className="relative"
        style={{ width: `${size}px`, height: `${size / 2}px` }}
      >
        <svg viewBox="0 0 200 100" className="w-full h-full">
          {/* 배경 반원 */}
          <path
            d="M 10 100 A 90 90 0 0 1 190 100"
            fill="none"
            stroke="#333"
            strokeWidth="10"
          />

          {/* 위험 색상 */}
          <path
            d="M 10 100 A 90 90 0 0 1 190 100"
            fill="none"
            stroke={value > 70 ? "#dc2626" : value > 40 ? "#facc15" : "#22c55e"}
            strokeWidth="10"
            // ✅ [수정] 부정확한 값 '283' 대신 정확하게 계산된 둘레 길이를 사용합니다.
            strokeDasharray={`${(clamp(value) / 100) * circumference} ${circumference}`}
          />

          {/* 눈금 (변경 없음) */}
          {Array.from({ length: 11 }).map((_, i) => {
            const tickAngle = (-90 + i * 18) * (Math.PI / 180);
            const x1 = 100 + Math.cos(tickAngle) * 80;
            const y1 = 100 + Math.sin(tickAngle) * 80;
            const x2 = 100 + Math.cos(tickAngle) * 90;
            const y2 = 100 + Math.sin(tickAngle) * 90;
            return (
              <line
                key={i}
                x1={x1}
                y1={y1}
                x2={x2}
                y2={y2}
                stroke="#fff"
                strokeWidth="2"
              />
            );
          })}
        </svg>

        {/* 바늘 (변경 없음) */}
        <div
          className="absolute left-1/2 bottom-0"
          style={{
            width: "4px",
            height: "70px",
            backgroundColor: "#facc15",
            transform: `translateX(-50%) rotate(${needleAngle}deg)`,
            transformOrigin: "bottom center",
            transition: "transform 0.8s ease-in-out",
          }}
        />

        {/* 중앙 원 (변경 없음) */}
        <div
          className="absolute left-1/2 bottom-0"
          style={{
            transform: "translate(-50%, 50%)",
            width: "12px",
            height: "12px",
            backgroundColor: "#facc15",
            borderRadius: "50%",
          }}
        />
      </div>

      {/* 점수 표기 (변경 없음) */}
      <div className="mt-2 text-white text-lg font-bold">
        {Math.round(clamp(value))}%
      </div>
    </div>
  );
};

export default GaugeWidget;
