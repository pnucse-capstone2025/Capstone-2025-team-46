// src/pages/UserPage.tsx
import React from "react";

export const mockUser = {
  name: "권태현",
  role: "Security Analyst",
  email: "xogus0065@naver.com",
  lastLogin: new Date().toLocaleString(),
  image: "../../public/user.jpg",
};

const UserPage: React.FC = () => {
  return (
    <div className="space-y-6">
      <h1 className="text-3xl font-bold">유저 정보</h1>

      <div className="bg-gray-100 dark:bg-gray-800 p-6 rounded-xl shadow-lg w-full max-w-[700px] text-black dark:text-white flex items-start gap-6">
        <div className="w-40 h-40 rounded-full overflow-hidden border-2 border-gray-300 dark:border-gray-600 flex-shrink-0">
          <img
            src={mockUser.image}
            alt="프로필"
            className="w-full h-full object-cover"
          />
        </div>

        <div className="flex flex-col justify-center gap-2 text-lg">
          <div>
            <span className="font-semibold">이름: </span>
            {mockUser.name}
          </div>
          <div>
            <span className="font-semibold">역할: </span>
            {mockUser.role}
          </div>
          <div>
            <span className="font-semibold">이메일: </span>
            {mockUser.email}
          </div>
          <div>
            <span className="font-semibold">최근 로그인: </span>
            {mockUser.lastLogin}
          </div>
        </div>
      </div>
    </div>
  );
};

export default UserPage;
