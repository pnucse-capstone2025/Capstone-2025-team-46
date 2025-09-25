// src/api/http.ts
import axios from "axios";

// ✅ 1. 프로토콜(http://)을 추가하고, IP 주소까지만 baseURL로 설정합니다.
const baseURL = "http://172.21.146.42:8000"; //학교와파
//const baseURL = "http://192.168.248.152:8000";
export const http = axios.create({
  baseURL: baseURL,
  headers: {
    "Content-Type": "application/json",
  },
});

// 공통 응답/에러 처리 (원하면 토스트 연동)
http.interceptors.response.use(
  (res) => res,
  (err) => {
    // 💡 네트워크 에러나 CORS 에러가 발생하면 콘솔에 에러 내용이 표시됩니다.
    console.error("API 요청 에러:", err);
    return Promise.reject(err);
  }
);
