/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: "class", // ✅ 반드시 class 방식으로 설정
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
      colors: {
        background: {
          light: "#f9fafb", // 밝은 회색
          dark: "#1f2937", // 다크모드용 진한 그레이
        },
        surface: {
          light: "#ffffff", // 카드/테이블 배경
          dark: "#374151", // 다크 카드 배경
        },
        text: {
          light: "#111827",
          dark: "#f9fafb",
        },
      },
    },
  },
  plugins: [],
};
