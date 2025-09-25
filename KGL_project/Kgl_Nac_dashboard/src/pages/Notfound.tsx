import React from 'react';
import { Link } from 'react-router-dom';

const NotFound: React.FC = () => {
  return (
    <div className="text-center mt-20">
      <h1 className="text-4xl font-bold text-red-500">404 - Page Not Found</h1>
      <p className="mt-4">
        Sorry, the page you are looking for does not exist.
      </p>
      <Link to="/" className="mt-6 inline-block text-blue-400">
        Go to Dashboard
      </Link>
    </div>
  );
};

export default NotFound;
