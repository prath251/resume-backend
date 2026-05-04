import React, { useState } from 'react';
import axios from 'axios';
import { useNavigate, Link } from 'react-router-dom';
import './Auth.css';

function Register() {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const navigate = useNavigate();

  const handleRegister = async (e) => {
    e.preventDefault();
    try {
      const res = await axios.post('http://localhost:8080/api/auth/register', { name, email, password });
      localStorage.setItem('token', res.data.token);
      localStorage.setItem('name', res.data.name);
      navigate('/dashboard');
    } catch (err) {
      setError('Registration failed. Email may already be used.');
    }
  };

  return (
    <div className="auth-container">
      <div className="auth-box">
        <h2>Create Account 🚀</h2>
        <p className="subtitle">Start analyzing your resume with AI</p>
        {error && <div className="error">{error}</div>}
        <form onSubmit={handleRegister}>
          <input type="text" placeholder="Full Name" value={name}
            onChange={e => setName(e.target.value)} required />
          <input type="email" placeholder="Email" value={email}
            onChange={e => setEmail(e.target.value)} required />
          <input type="password" placeholder="Password" value={password}
            onChange={e => setPassword(e.target.value)} required />
          <button type="submit">Create Account</button>
        </form>
        <p className="switch">Already have an account? <Link to="/login">Login</Link></p>
      </div>
    </div>
  );
}

export default Register;