// src/components/Navbar.tsx
import { Link } from "react-router-dom";
import { FiHome, FiFileText } from "react-icons/fi";

export default function Sidebar() {
  return (
    <nav className="bg-white dark:bg-gray-800 shadow px-6 py-4 flex justify-between items-center">
      <div className="text-xl font-bold text-gray-800 dark:text-white">
        🚦 NAC Dashboard
      </div>
      <div className="flex space-x-2">
        <Link
          to="/"
          className="
            inline-flex items-center space-x-1
            px-4 py-2 rounded border border-gray-300 
            dark:border-gray-600
            bg-gray-100 text-gray-800 
            dark:bg-gray-700 dark:text-white 
            hover:bg-gray-200 dark:hover:bg-gray-600
            transition-colors duration-200
          "
        >
          <FiHome className="w-4 h-4" />
          <span>Dashboard</span>
        </Link>

        <Link
          to="/logs"
          className="
            inline-flex items-center space-x-1
            px-4 py-2 rounded border border-gray-300 
            dark:border-gray-600
            bg-gray-100 text-gray-800 
            dark:bg-gray-700 dark:text-white 
            hover:bg-gray-200 dark:hover:bg-gray-600
            transition-colors duration-200
          "
        >
          <FiFileText className="w-4 h-4" />
          <span>Logs</span>
        </Link>
        <Link
          to="/stats"
          className="
            inline-flex items-center space-x-1
            px-4 py-2 rounded border border-gray-300 
            dark:border-gray-600
            bg-gray-100 text-gray-800 
            dark:bg-gray-700 dark:text-white 
            hover:bg-gray-200 dark:hover:bg-gray-600
            transition-colors duration-200
          "
        >
          <FiFileText className="w-4 h-4" />
          <span>Stats</span>
        </Link>
      </div>
    </nav>
  );
}
export { Sidebar };
