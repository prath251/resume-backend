import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import './Dashboard.css';

function Dashboard() {
  const navigate = useNavigate();
  const name = localStorage.getItem('name');
  const token = localStorage.getItem('token');

  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [history, setHistory] = useState([]);
  const [activeTab, setActiveTab] = useState('analyze');

  useEffect(() => { fetchHistory(); }, []);

  const fetchHistory = async () => {
    try {
      const res = await axios.get('http://localhost:8080/api/resume/history', {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      setHistory(res.data);
    } catch (err) { console.log('Could not fetch history'); }
  };

  const handleLogout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('name');
    navigate('/login');
  };

  const handleFileChange = (e) => {
    setFile(e.target.files[0]);
    setResult(null);
    setError('');
  };

  const handleAnalyze = async () => {
    if (!file) { setError('Please select a PDF file first'); return; }
    setLoading(true);
    setError('');
    try {
      const formData = new FormData();
      formData.append('file', file);
      const res = await axios.post('http://localhost:8080/api/resume/analyze',
        formData, {
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'multipart/form-data'
          }
        });

      const data = res.data;
      const text = data.choices[0].message.content;
      const cleanJson = text.replace(/```json\n?/g, '').replace(/```\n?/g, '').trim();
      const parsed = JSON.parse(cleanJson);
      setResult(parsed);
      fetchHistory();
    } catch (err) {
      setError('Failed to analyze resume. Please try again.');
      console.error(err);
    }
    setLoading(false);
  };

  const parseList = (str) => {
    try { return JSON.parse(str); } catch { return []; }
  };

  return (
    <div className="dashboard">
      <div className="header">
        <div className="logo">🤖 AI Resume Analyzer</div>
        <div className="user-info">
          <span>👋 {name}</span>
          <button className="logout-btn" onClick={handleLogout}>Logout</button>
        </div>
      </div>

      <div className="tabs">
        <button className={activeTab === 'analyze' ? 'tab active' : 'tab'}
          onClick={() => setActiveTab('analyze')}>📄 Analyze Resume</button>
        <button className={activeTab === 'history' ? 'tab active' : 'tab'}
          onClick={() => setActiveTab('history')}>📋 History ({history.length})</button>
      </div>

      <div className="main">
        {activeTab === 'analyze' && (
          <>
            <h1>Analyze Your Resume</h1>
            <p className="subtitle">Upload your PDF and get AI-powered insights instantly</p>
            <div className="upload-box">
              <div className="upload-icon">📄</div>
              <p>Drop your resume here or click to browse</p>
              <input type="file" accept=".pdf" onChange={handleFileChange}
                id="fileInput" style={{display:'none'}} />
              <label htmlFor="fileInput" className="browse-btn">Browse PDF</label>
              {file && <p className="file-name">✅ {file.name}</p>}
            </div>

            {error && <div className="error-msg">{error}</div>}

            <button className="analyze-btn" onClick={handleAnalyze} disabled={loading}>
              {loading ? '🔄 Analyzing...' : '🚀 Analyze Resume'}
            </button>

            {result && (
              <div className="results">
                <h2>📊 Analysis Results</h2>
                <div className="cards">
                  <div className="card">
                    <h3>💻 Technical Skills</h3>
                    <ul>{result.technicalSkills.map((s, i) => <li key={i}>{s}</li>)}</ul>
                  </div>
                  <div className="card">
                    <h3>🤝 Soft Skills</h3>
                    <ul>{result.softSkills.map((s, i) => <li key={i}>{s}</li>)}</ul>
                  </div>
                  <div className="card">
                    <h3>📈 Experience Level</h3>
                    <div className="level">{result.experienceLevel}</div>
                  </div>
                  <div className="card">
                    <h3>💼 Recommended Jobs</h3>
                    <ul>{result.recommendedJobs.map((j, i) => <li key={i}>{j}</li>)}</ul>
                  </div>
                </div>
                <div className="advice-box">
                  <h3>💡 AI Advice</h3>
                  <p>{result.advice}</p>
                </div>
              </div>
            )}
          </>
        )}

        {activeTab === 'history' && (
          <>
            <h1>Analysis History</h1>
            <p className="subtitle">Your previous resume analyses</p>
            {history.length === 0 ? (
              <div className="empty-history">
                <p>📭 No analyses yet. Upload a resume to get started!</p>
              </div>
            ) : (
              <div className="history-list">
                {history.map((item, i) => (
                  <div className="history-card" key={i}>
                    <div className="history-header">
                      <span className="history-filename">📄 {item.fileName}</span>
                      <span className="history-date">
                        {new Date(item.analyzedAt).toLocaleDateString('en-IN', {
                          day: 'numeric', month: 'short', year: 'numeric',
                          hour: '2-digit', minute: '2-digit'
                        })}
                      </span>
                    </div>
                    <div className="history-level">
                      Level: <strong>{item.experienceLevel}</strong>
                    </div>
                    <div className="history-skills">
                      {parseList(item.technicalSkills).map((s, j) => (
                        <span className="skill-tag" key={j}>{s}</span>
                      ))}
                    </div>
                    <div className="history-advice">💡 {item.advice}</div>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

export default Dashboard;