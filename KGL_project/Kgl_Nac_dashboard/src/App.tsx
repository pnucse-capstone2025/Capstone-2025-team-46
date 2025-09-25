import React from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import Layout from "./components/Layout";
import DashboardPage from "./pages/DashboardPage";
import LogsPage from "./pages/LogsPage";
import NotFound from "./pages/Notfound";
import { LogProvider } from "./context/LogContext/LogContext"; // ✅ LogProvider import
import AdminOptionsPage from "./pages/AdminOptionsPage";
import UserPage from "./pages/UserPage";
import { ToastContainer } from "react-toastify";
import "react-toastify/dist/ReactToastify.css";
import StatsPage from "./pages/StatsPage";
function App() {
  return (
    <>
      <meta httpEquiv="refresh" content="10"></meta>

      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<DashboardPage />} />
          <Route path="logs" element={<LogsPage />} />
          <Route path="stats" element={<StatsPage />} />
          <Route path="options" element={<AdminOptionsPage />} />
          <Route path="user" element={<UserPage />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>

      {/* ✅ 어떤 페이지에서도 알림 뜨도록 전역에 위치 */}
      <ToastContainer position="top-right" autoClose={4000} />
    </>
  );
}

export default App;
