// src/pages/DashboardPage.tsx

import React, { useContext, useState, useEffect } from "react";
import RiskGauge from "../components/RiskGauge";
import StatCard from "../components/StatCard";
import RecentRiskTrend from "../components/TrendChart";
import UserInfoCard from "../components/UserInfoCard";
import LogsTable from "../components/LogsTable";
import { LogContext } from "../context/LogContext/LogContext";

export const DashboardPage: React.FC = () => {
  const { logs, isLoading, error } = useContext(LogContext);

  const [currentTime, setCurrentTime] = useState<string>(
    new Date().toLocaleTimeString()
  );

  useEffect(() => {
    const timer = setInterval(() => {
      setCurrentTime(new Date().toLocaleTimeString());
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const totalLogs = logs.length;
  const anomalyCount = logs.filter((log) => log.is_anomaly === true).length;

  const totalScore = logs.reduce(
    (acc, log) => acc + (log.normalized_score || 0),
    0
  );
  const averageRisk = logs.length > 0 ? totalScore / logs.length : 0;

  // ✅ [핵심 수정] 평균 위험도 계산 시 배율을 3에서 2로 변경합니다.
  const displayAverageRisk = Math.min(averageRisk, 100);

  // TrendChart에 사용될 점수는 display_score를 직접 사용합니다.
  const recentRiskScores = logs
    .slice(0, 20)
    .map((log) => log.display_score || 0);

  if (isLoading) {
    return <div className="p-6 text-center">Loading data...</div>;
  }

  if (error) {
    return (
      <div className="p-6 text-center text-red-500">
        Error loading data: {error.message}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex justify-between items-center">
        <h1 className="text-2xl font-bold">Dashboard</h1>
        <div className="text-lg font-mono bg-gray-700 p-2 rounded">
          {currentTime}
        </div>
      </div>
      <div className="flex flex-wrap items-start gap-8">
        <div className="flex flex-col gap-4">
          <div className="flex gap-4">
            <div className="w-[220px]">
              <h2 className="text-lg mb-2">Average Risk</h2>
              <RiskGauge value={displayAverageRisk} />
            </div>
            <div className="flex flex-col gap-4 w-[180px]">
              <StatCard
                label="Total Logs"
                value={totalLogs}
                colorClass="bg-blue-500"
              />
              <StatCard
                label="Anomalies"
                value={anomalyCount}
                colorClass={anomalyCount > 0 ? "bg-red-500" : "bg-green-500"}
              />
            </div>
          </div>
          <div className="w-[400px]">
            <UserInfoCard />
          </div>
        </div>
        <div className="flex-1 min-w-[300px]">
          <RecentRiskTrend risks={recentRiskScores} />
        </div>
      </div>
      <div>
        <h2 className="text-lg font-semibold mb-2">Recent Logs</h2>
        <LogsTable
          logs={logs}
          isLoading={isLoading}
          error={error}
          isDashboard={true}
        />
      </div>
    </div>
  );
};

export default DashboardPage;
