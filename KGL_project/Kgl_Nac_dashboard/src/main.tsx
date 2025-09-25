// src/main.tsx

import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import { LogProvider } from "./context/LogContext/LogContext"; // 경로 확인
import "./index.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <BrowserRouter>
      {/* ✅ 앱 전체를 감싸는 LogProvider는 여기에만 있어야 합니다. */}
      <LogProvider>
        <App />
      </LogProvider>
    </BrowserRouter>
  </React.StrictMode>
);
