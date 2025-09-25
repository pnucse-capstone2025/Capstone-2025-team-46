import React from "react";
import { useNavigate } from "react-router-dom";
// import the mockUser object directly, not the default export
import { mockUser } from "../pages/UserPage";

const UserInfoCard: React.FC = () => {
  const navigate = useNavigate();

  return (
    <div className="bg-gray-800 text-white rounded-md p-4 shadow w-full">
      <div className="flex justify-between items-start flex-nowrap gap-4 w-full">
        {/* 유저 정보 + 이미지 */}
        <div className="flex items-start gap-3">
          <div className="w-12 h-12 rounded-full overflow-hidden bg-gray-600 flex-shrink-0 border border-gray-500">
            <img
              src={mockUser.image}
              alt="프로필"
              className="w-full h-full object-cover"
            />
          </div>
          <div className="flex flex-col">
            <div className="font-bold text-2xl">{mockUser.name}</div>
            <div className="text-base text-gray-300">{mockUser.role}</div>
            <div className="text-sm text-gray-400 mt-1">
              Last login: {mockUser.lastLogin}
            </div>
          </div>
        </div>

        {/* 버튼 그룹 */}
        <div className="flex flex-col gap-2 w-[120px] flex-shrink-0 self-start">
          <button
            className="bg-gray-700 hover:bg-gray-600 px-4 py-2 rounded text-base whitespace-nowrap w-full"
            onClick={() => navigate("/user")}
          >
            유저 페이지
          </button>
          <button
            className="bg-gray-700 hover:bg-gray-600 px-4 py-2 rounded text-base whitespace-nowrap w-full"
            onClick={() => navigate("/options")}
          >
            설정 페이지
          </button>
        </div>
      </div>
    </div>
  );
};

export default UserInfoCard;
