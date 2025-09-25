// src/components/Layout.tsx
import React from 'react'
import { Outlet } from 'react-router-dom'
import Navbar from './Navbar'

const Layout: React.FC = () => {
  return (
    <div className="flex h-screen bg-gray-800 text-white">
      {/* 재사용 Navbar */}
      <Navbar />

      {/* Main Content */}
      <main className="flex-1 p-6 overflow-auto">
        <Outlet />
      </main>
    </div>
  )
}

export default Layout
