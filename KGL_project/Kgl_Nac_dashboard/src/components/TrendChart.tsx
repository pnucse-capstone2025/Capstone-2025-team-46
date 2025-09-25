// src/components/TrendChart.tsx
import React from "react";
import { Line } from "react-chartjs-2";
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Tooltip,
  Filler,
} from "chart.js";

ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Tooltip,
  Filler
);

interface TrendChartProps {
  risks: number[]; // 0~100 점수 배열 (최신 항목이 마지막에 오게 표시)
  title?: string;
  className?: string;
  heightPx?: number; // 차트 높이 (기본 260px)
}

const TrendChart: React.FC<TrendChartProps> = ({
  risks,
  title = "Recent 20 Risk Scores",
  className = "",
  heightPx = 260,
}) => {
  if (!risks || risks.length === 0) {
    return (
      <div className={`bg-gray-800 p-4 rounded-md ${className}`}>
        <div className="text-gray-400">데이터가 없습니다</div>
      </div>
    );
  }

  // 최신이 오른쪽에 오도록 역순
  const chartData = risks.slice().reverse();
  const labels = chartData.map((_, idx) => `#${idx + 1}`);

  const data = {
    labels,
    datasets: [
      {
        label: "Risk Score",
        data: chartData,
        borderColor: "#facc15",
        backgroundColor: "rgba(250, 204, 21, 0.2)",
        tension: 0.3,
        fill: true,
        pointRadius: 2,
      },
    ],
  };

  const options = {
    responsive: true,
    maintainAspectRatio: false as const,
    scales: {
      y: {
        min: 0,
        max: 100,
        ticks: { stepSize: 20 },
        grid: { color: "rgba(255,255,255,0.08)" },
      },
      x: {
        grid: { display: false },
      },
    },
    plugins: {
      legend: { display: false },
      tooltip: { intersect: false, mode: "index" as const },
    },
  };

  return (
    <div className={`bg-gray-800 p-4 rounded-md h-full ${className}`}>
      <h2 className="text-lg mb-2 text-white">{title}</h2>
      <div style={{ height: `${heightPx}px` }}>
        <Line data={data} options={options} />
      </div>
    </div>
  );
};

export default TrendChart;
