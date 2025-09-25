import { NavLink } from "react-router-dom";
import { FiHome, FiFileText } from "react-icons/fi";

export default function Navbar() {
  return (
    <nav className="w-64 bg-gray-900 p-4 h-screen">
      <h2 className="text-xl font-bold mb-4">🚦 NAC Dashboard</h2>
      <ul className="space-y-2">
        <li>
          <NavLink
            to="/"
            className={({ isActive }) =>
              `
              flex items-center space-x-2 px-4 py-2 rounded 
              transition-colors duration-200
              ${
                isActive
                  ? "bg-gray-700 border-l-4 border-blue-400 text-blue-400"
                  : "text-gray-300 hover:bg-gray-800"
              }
              `
            }
          >
            <FiHome className="w-5 h-5" />
            <span>Dashboard</span>
          </NavLink>
        </li>
        <li>
          <NavLink
            to="/logs"
            className={({ isActive }) =>
              `
              flex items-center space-x-2 px-4 py-2 rounded 
              transition-colors duration-200
              ${
                isActive
                  ? "bg-gray-700 border-l-4 border-blue-400 text-blue-400"
                  : "text-gray-300 hover:bg-gray-800"
              }
              `
            }
          >
            <FiFileText className="w-5 h-5" />
            <span>Logs</span>
          </NavLink>
        </li>
        <li>
          <NavLink
            to="/stats"
            className={({ isActive }) =>
              `
              flex items-center space-x-2 px-4 py-2 rounded 
              transition-colors duration-200
              ${
                isActive
                  ? "bg-gray-700 border-l-4 border-blue-400 text-blue-400"
                  : "text-gray-300 hover:bg-gray-800"
              }
              `
            }
          >
            <FiFileText className="w-5 h-5" />
            <span>Stats</span>
          </NavLink>
        </li>
      </ul>
    </nav>
  );
}

export { Navbar };
