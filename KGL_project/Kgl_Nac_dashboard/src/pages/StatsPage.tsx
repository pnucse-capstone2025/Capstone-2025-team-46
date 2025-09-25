import React, { useContext } from "react";
import MostRiskyLogCard from "../components/MostRiskyLogCard";
import RecentRiskTrend from "../components/TrendChart";
import { LogContext } from "../context/LogContext/LogContext";
import type { Log } from "../types";

const StatsPage: React.FC = () => {
  const { logs, isLoading, error } = useContext(LogContext);

  const logsWithDisplayScore = logs.map((log) => ({
    ...log,
    display_score: Math.min(log.normalized_score || 0, 100),
  }));

  const topRiskLogs = [...logsWithDisplayScore]
    .sort((a, b) => (b.display_score || 0) - (a.display_score || 0))
    .slice(0, 5);

  const mostRiskyLog = topRiskLogs.length > 0 ? topRiskLogs[0] : null;

  // 그래프용 데이터는 Top 5가 아닌, 시간순 최근 20개를 사용해야 합니다.
  const displayRecentRiskScores = logs
    .slice(0, 20)
    .map((log) => Math.min(log.normalized_score || 0, 100));

  if (isLoading) {
    return <div className="p-6 text-center">Loading statistics...</div>;
  }

  if (error) {
    return (
      <div className="p-6 text-center text-red-500">Error: {error.message}</div>
    );
  }

  return (
    <div className="space-y-6 p-6 bg-gray-900 text-white min-h-screen">
      <h1 className="text-2xl font-bold">📊 Statistics</h1>

      <div className="flex flex-wrap items-start gap-8">
        <div className="flex-1 min-w-[300px] bg-gray-800 p-4 rounded-lg">
          <h2 className="text-xl font-semibold mb-2">
            📉 Recent 20 Risk Score Trend
          </h2>
          <RecentRiskTrend risks={displayRecentRiskScores} />
        </div>

        <div className="w-full md:w-[400px]">
          <h2 className="text-xl font-semibold mb-2">🧨 Most Risky Log</h2>
          <MostRiskyLogCard log={mostRiskyLog} />
        </div>
      </div>

      <section>
        <h2 className="text-xl font-semibold mb-4">🔥 Top 5 Risky Logs</h2>

        {/* ✅ [수정] space-y-4 대신, 각 카드에 직접 아래쪽 여백(mb-4)을 줍니다. */}
        <div>
          {topRiskLogs.length > 0 ? (
            topRiskLogs.map((log) => (
              // 각 카드를 div로 한번 감싸고 아래쪽 여백(margin-bottom)을 줍니다.
              <div key={log.id} className="mb-4">
                <MostRiskyLogCard log={log} />
              </div>
            ))
          ) : (
            <div className="bg-gray-800 p-4 rounded-lg text-center text-gray-500">
              No risk log data available.
            </div>
          )}
        </div>
      </section>
    </div>
  );
};

export default StatsPage;
