// src/components/AnomalyTrendChart.tsx (새 이름)

import React, { useContext, useMemo } from "react";
import { LogContext } from "../context/LogContext/LogContext";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";
import dayjs from "dayjs";

const BlockedTrendChart: React.FC = () => {
  const { logs } = useContext(LogContext);

  // [수정] is_anomaly가 true인 로그의 날짜별 카운트를 계산
  const anomalyTrend = useMemo(() => {
    const trendMap: Record<string, number> = {};
    logs.forEach((log) => {
      // is_anomaly가 true인 경우에만 카운트
      if (log.is_anomaly) {
        const date = dayjs(log.timestamp).format("MM/DD");
        trendMap[date] = (trendMap[date] || 0) + 1;
      }
    });
    // 날짜 순으로 정렬
    const sortedTrend = Object.entries(trendMap)
      .map(([date, count]) => ({ date, count }))
      .sort(
        (a, b) => dayjs(a.date, "MM/DD").unix() - dayjs(b.date, "MM/DD").unix()
      );

    return sortedTrend;
  }, [logs]);

  if (anomalyTrend.length === 0) {
    return (
      <div className="flex items-center justify-center h-full text-gray-500">
        이상 탐지 기록이 없습니다.
      </div>
    );
  }

  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={anomalyTrend}>
        <CartesianGrid strokeDasharray="3 3" stroke="#4A5568" />
        <XAxis dataKey="date" stroke="#A0AEC0" />
        <YAxis allowDecimals={false} stroke="#A0AEC0" />
        <Tooltip
          contentStyle={{
            backgroundColor: "#2D3748",
            borderColor: "#4A5568",
          }}
        />
        <Line
          type="monotone"
          dataKey="count"
          name="Anomalies"
          stroke="#ef4444" // 빨간색 라인
          strokeWidth={2}
          dot={{ r: 4 }}
          activeDot={{ r: 8 }}
        />
      </LineChart>
    </ResponsiveContainer>
  );
};

export default BlockedTrendChart;
