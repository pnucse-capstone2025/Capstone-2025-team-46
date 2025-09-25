// src/pages/AdminOptionsPage.tsx

import React, { useEffect, useState } from "react";
import { toast, ToastContainer } from "react-toastify";
import "react-toastify/dist/ReactToastify.css";

const CheckCircleIcon = () => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    className="h-6 w-6 text-white bg-blue-600 rounded-full p-1"
    viewBox="0 0 20 20"
    fill="currentColor"
  >
    <path
      fillRule="evenodd"
      d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
      clipRule="evenodd"
    />
  </svg>
);

const SignalIcon = ({ level }: { level: number }) => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    className="h-8 w-8"
    fill="none"
    viewBox="0 0 24 24"
    stroke="currentColor"
    strokeWidth={2}
  >
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      d="M4 15.75V20"
      className={`${level >= 1 ? "text-blue-500" : "text-gray-300"}`}
    />
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      d="M8 11.25V20"
      className={`${level >= 2 ? "text-blue-500" : "text-gray-300"}`}
    />
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      d="M12 6.75V20"
      className={`${level >= 3 ? "text-blue-500" : "text-gray-300"}`}
    />
  </svg>
);

const AdminOptionsPage: React.FC = () => {
  const [sensitivity, setSensitivity] = useState("medium");

  const sensitivityOptions = [
    {
      value: "all",
      label: "높음",
      // '높음' 설정에 대한 설명을 더 명확하게 변경
      description:
        "매우 낮은 기준을 적용하여 사소한 이상 징후까지 모두 탐지합니다.",
      icon: <SignalIcon level={3} />,
    },
    {
      value: "medium",
      label: "중간",
      description: "균형 잡힌 기준으로 이상치를 탐지합니다.", // 설명 일관성 위해 수정
      icon: <SignalIcon level={2} />,
    },
    {
      value: "low",
      label: "낮음",
      description: "가장 확실한 주요 이상치만 탐지합니다.", // 설명 일관성 위해 수정
      icon: <SignalIcon level={1} />,
    },
  ];

  const handleSave = () => {
    // 의미가 명확해진 키 이름으로 localStorage에 저장
    localStorage.setItem("anomalySensitivity", sensitivity);
    toast.success("민감도 기준이 성공적으로 저장되었습니다!");
    window.dispatchEvent(new Event("local-storage-changed"));
  };

  useEffect(() => {
    // 새로운 키 이름으로 값을 불러옴
    const savedSensitivity = localStorage.getItem("anomalySensitivity");
    if (savedSensitivity) {
      setSensitivity(savedSensitivity);
    }
  }, []);

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900 p-4 sm:p-8 md:p-12">
      <div className="max-w-4xl mx-auto space-y-10">
        <header>
          <h1 className="text-4xl font-bold text-gray-900 dark:text-white tracking-tight">
            이상치 탐지 설정
          </h1>
          <p className="mt-2 text-lg text-gray-600 dark:text-gray-300">
            시스템이 이상 로그를 감지하는 민감도를 조절합니다.
          </p>
        </header>

        <main>
          <h2 className="text-xl font-semibold text-gray-800 dark:text-gray-200 mb-4">
            탐지 민감도
          </h2>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {sensitivityOptions.map((option) => (
              <div
                key={option.value}
                className={`
                  group relative rounded-xl border p-5 cursor-pointer transition-all duration-300
                  flex flex-col items-start space-y-4
                  ${
                    sensitivity === option.value
                      ? "border-blue-600 border-2 bg-white dark:bg-gray-800 shadow-lg"
                      : "border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 hover:border-blue-400 hover:shadow-md"
                  }
                `}
                onClick={() => setSensitivity(option.value)}
              >
                {sensitivity === option.value && (
                  <div className="absolute top-4 right-4">
                    <CheckCircleIcon />
                  </div>
                )}
                <div className="flex-shrink-0">{option.icon}</div>
                <div className="flex-grow">
                  <h3 className="font-semibold text-xl text-gray-800 dark:text-gray-200">
                    {option.label}
                  </h3>
                  <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
                    {option.description}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </main>

        <footer className="flex justify-start pt-4">
          <button
            onClick={handleSave}
            className="inline-flex items-center justify-center px-8 py-3 bg-blue-600 text-white font-semibold rounded-lg shadow-md hover:bg-blue-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 transition-colors duration-200"
          >
            설정 저장
          </button>
        </footer>
      </div>

      <ToastContainer
        position="bottom-right"
        autoClose={3000}
        theme="colored"
      />
    </div>
  );
};

export default AdminOptionsPage;
