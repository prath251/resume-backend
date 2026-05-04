import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Pie,
  PieChart,
  PolarAngleAxis,
  PolarGrid,
  PolarRadiusAxis,
  Radar,
  RadarChart,
  RadialBar,
  RadialBarChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis
} from 'recharts';
import './Dashboard.css';

const API = 'http://localhost:8080/api/resume';
const COLORS = ['#ff4f7b', '#6ee7d8', '#ffd166', '#8b7cf6', '#4ea5ff'];
const localHistoryKey = 'resumeAnalyzerLocalHistory';

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
  const [chatQuestion, setChatQuestion] = useState('');
  const [chatMessages, setChatMessages] = useState([]);
  const [chatLoading, setChatLoading] = useState(false);
  const [firstCompareId, setFirstCompareId] = useState('');
  const [secondCompareId, setSecondCompareId] = useState('');
  const [comparison, setComparison] = useState(null);
  const [profileUrl, setProfileUrl] = useState('');
  const [profileInsights, setProfileInsights] = useState(null);
  const [profileLoading, setProfileLoading] = useState(false);

  const getAuthHeaders = useCallback(() => ({ Authorization: `Bearer ${token}` }), [token]);

  const readLocalHistory = () => {
    try {
      return JSON.parse(localStorage.getItem(localHistoryKey) || '[]');
    } catch {
      return [];
    }
  };

  const saveLocalHistory = (items) => {
    localStorage.setItem(localHistoryKey, JSON.stringify(items.slice(0, 20)));
  };

  const mergeHistory = (serverItems, localItems) => {
    const seen = new Set();
    return [...serverItems, ...localItems]
      .filter((item) => {
        const key = item.id || item.analysisId || item.localId || item.fileName + item.analyzedAt;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      })
      .sort((a, b) => new Date(b.analyzedAt || 0) - new Date(a.analyzedAt || 0));
  };

  const fetchHistory = useCallback(async () => {
    const localItems = readLocalHistory();
    try {
      const res = await axios.get(`${API}/history`, { headers: getAuthHeaders() });
      setHistory(mergeHistory(res.data, localItems));
    } catch (err) {
      setHistory(localItems);
    }
  }, [getAuthHeaders]);

  useEffect(() => { fetchHistory(); }, [fetchHistory]);

  const parseList = (value) => {
    if (Array.isArray(value)) return value;
    try { return JSON.parse(value || '[]'); } catch { return []; }
  };

  const normalizeAnalysis = (item) => ({
    analysisId: item.analysisId || item.id || item.localId,
    localOnly: item.localOnly || String(item.localId || '').startsWith('local-'),
    fileName: item.fileName,
    analyzedAt: item.analyzedAt,
    resumeScore: item.resumeScore || 0,
    technicalSkills: parseList(item.technicalSkills),
    softSkills: parseList(item.softSkills),
    experienceLevel: item.experienceLevel || 'Not specified',
    recommendedJobs: parseList(item.recommendedJobs),
    jobMatches: parseList(item.jobMatches),
    missingSkills: parseList(item.missingSkills),
    strengths: parseList(item.strengths),
    improvements: parseList(item.improvements),
    advice: item.advice || ''
  });

  const current = result || (history[0] ? normalizeAnalysis(history[0]) : null);
  const selectedAnalysisId = result?.analysisId || history[0]?.id;

  const skillPieData = current ? [
    { name: 'Technical', value: Math.max(current.technicalSkills.length, 1) },
    { name: 'Soft', value: Math.max(current.softSkills.length, 1) },
    { name: 'Missing', value: Math.max(current.missingSkills.length, 1) }
  ] : [];

  const radarData = current ? [
    { subject: 'Skills', value: Math.min(current.technicalSkills.length * 12, 100) },
    { subject: 'Soft Skills', value: Math.min(current.softSkills.length * 18, 100) },
    { subject: 'Completeness', value: current.resumeScore },
    { subject: 'Role Fit', value: current.jobMatches[0]?.percentage || current.resumeScore - 8 || 50 },
    { subject: 'Growth', value: Math.max(100 - current.missingSkills.length * 12, 35) }
  ] : [];

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
      const res = await axios.post(`${API}/analyze`, formData, {
        headers: { ...getAuthHeaders(), 'Content-Type': 'multipart/form-data' }
      });

      const parsed = res.data?.choices
        ? JSON.parse(res.data.choices[0].message.content.replace(/```json\n?/g, '').replace(/```\n?/g, '').trim())
        : res.data;
      const normalized = normalizeAnalysis({
        ...parsed,
        localId: parsed.analysisId || parsed.id || `local-${Date.now()}`,
        fileName: parsed.fileName || file.name,
        analyzedAt: parsed.analyzedAt || new Date().toISOString(),
        localOnly: !parsed.analysisId && !parsed.id
      });
      setResult(normalized);

      const localItems = readLocalHistory();
      const storedItem = {
        ...normalized,
        id: normalized.localOnly ? normalized.analysisId : normalized.analysisId,
        localId: normalized.analysisId,
        technicalSkills: JSON.stringify(normalized.technicalSkills),
        softSkills: JSON.stringify(normalized.softSkills),
        recommendedJobs: JSON.stringify(normalized.recommendedJobs),
        jobMatches: JSON.stringify(normalized.jobMatches),
        missingSkills: JSON.stringify(normalized.missingSkills),
        strengths: JSON.stringify(normalized.strengths),
        improvements: JSON.stringify(normalized.improvements)
      };
      saveLocalHistory([storedItem, ...localItems.filter((item) => (item.localId || item.id) !== storedItem.localId)]);
      fetchHistory();
    } catch (err) {
      setError(err.response?.data?.error || 'Failed to analyze resume. Please try again.');
      console.error(err);
    }
    setLoading(false);
  };

  const reopenAnalysis = (item) => {
    setResult(normalizeAnalysis(item));
    setActiveTab('analyze');
  };

  const downloadReport = async (analysisId = selectedAnalysisId) => {
    if (!analysisId) return;
    try {
      const res = await axios.get(`${API}/report/${analysisId}`, {
        headers: getAuthHeaders(),
        responseType: 'blob'
      });
      const url = window.URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `resume-analysis-${analysisId}.pdf`;
      link.click();
      window.URL.revokeObjectURL(url);
    } catch {
      setError('Could not download the PDF report.');
    }
  };

  const sendChat = async () => {
    if (!current || !chatQuestion.trim()) return;
    const question = chatQuestion.trim();
    setChatQuestion('');
    setChatMessages((messages) => [...messages, { role: 'user', text: question }]);
    setChatLoading(true);
    const selectedIsLocal = String(selectedAnalysisId || '').startsWith('local-') || current.localOnly;
    if (selectedIsLocal) {
      setChatMessages((messages) => [...messages, { role: 'assistant', text: localResumeReply(question, current) }]);
      setChatLoading(false);
      return;
    }
    try {
      const res = await axios.post(`${API}/chat`, {
        analysisId: String(selectedAnalysisId),
        question
      }, { headers: getAuthHeaders() });
      setChatMessages((messages) => [...messages, { role: 'assistant', text: res.data.answer }]);
    } catch {
      setChatMessages((messages) => [...messages, { role: 'assistant', text: 'I could not answer from this resume right now.' }]);
    }
    setChatLoading(false);
  };

  const compareResumes = async () => {
    setComparison(null);
    if (!firstCompareId || !secondCompareId) return;
    const firstLocal = history.find((item) => String(item.id || item.localId) === String(firstCompareId));
    const secondLocal = history.find((item) => String(item.id || item.localId) === String(secondCompareId));
    if (firstLocal?.localOnly || secondLocal?.localOnly || String(firstCompareId).startsWith('local-') || String(secondCompareId).startsWith('local-')) {
      setComparison(buildLocalComparison(normalizeAnalysis(firstLocal), normalizeAnalysis(secondLocal)));
      return;
    }
    try {
      const res = await axios.post(`${API}/compare`, {
        firstId: Number(firstCompareId),
        secondId: Number(secondCompareId)
      }, { headers: getAuthHeaders() });
      setComparison(res.data);
    } catch {
      setComparison({ error: 'Choose two different saved resumes to compare.' });
    }
  };

  const localResumeReply = (question, analysis) => {
    const lowerQuestion = question.toLowerCase();
    if (lowerQuestion.includes('job') || lowerQuestion.includes('role')) {
      return `Best role fit: ${analysis.jobMatches[0]?.role || analysis.recommendedJobs[0] || 'Software Developer'}. Your next strongest options are ${analysis.recommendedJobs.slice(1).join(', ') || 'similar developer roles'}.`;
    }
    if (lowerQuestion.includes('skill') || lowerQuestion.includes('missing') || lowerQuestion.includes('improve')) {
      return `Focus on ${analysis.missingSkills.join(', ') || 'stronger project metrics'}. Add measurable achievements and one project that proves your target role fit.`;
    }
    return `This resume currently scores ${analysis.resumeScore}/100. The strongest signal is ${analysis.technicalSkills.slice(0, 3).join(', ') || 'your project experience'}, and the main improvement area is ${analysis.missingSkills.slice(0, 2).join(', ') || 'more quantified impact'}.`;
  };

  const buildLocalComparison = (first, second) => {
    const firstScore = first?.resumeScore || 0;
    const secondScore = second?.resumeScore || 0;
    return {
      first: {
        id: first.analysisId,
        fileName: first.fileName,
        score: firstScore,
        experienceLevel: first.experienceLevel,
        technicalSkills: first.technicalSkills
      },
      second: {
        id: second.analysisId,
        fileName: second.fileName,
        score: secondScore,
        experienceLevel: second.experienceLevel,
        technicalSkills: second.technicalSkills
      },
      winner: firstScore === secondScore ? 'Tie' : firstScore > secondScore ? first.fileName : second.fileName
    };
  };

  const analyzeProfile = async () => {
    if (!profileUrl.trim()) return;
    setProfileLoading(true);
    setProfileInsights(null);
    try {
      const res = await axios.post(`${API}/profile`, {
        url: profileUrl,
        analysisId: selectedAnalysisId ? String(selectedAnalysisId) : ''
      }, { headers: getAuthHeaders() });
      setProfileInsights(res.data);
    } catch {
      setProfileInsights({ error: 'Could not analyze this profile URL.' });
    }
    setProfileLoading(false);
  };

  const renderTags = (items, className = 'skill-chip') => (
    <div className="tag-row">
      {items.length
        ? items.map((item, index) => <span className={className} key={index}>{item}</span>)
        : <span className="muted">No data yet</span>}
    </div>
  );

  const renderAnalytics = () => current && (
    <div className="analytics-wrap">
      <div className="result-toolbar">
        <div>
          <span className="eyebrow">{current.fileName || 'Latest resume'}</span>
          <h2>Resume Intelligence Dashboard</h2>
        </div>
        <button className="ghost-btn" onClick={() => downloadReport(current.analysisId)}>Download Report</button>
      </div>

      <div className="hero-grid">
        <div className="glass-card score-visual">
          <div className="chart-shell">
            <ResponsiveContainer width="100%" height={220}>
              <RadialBarChart innerRadius="72%" outerRadius="100%" data={[{ name: 'Score', value: current.resumeScore, fill: '#6ee7d8' }]} startAngle={90} endAngle={-270}>
                <RadialBar dataKey="value" cornerRadius={18} background={{ fill: '#263148' }} />
              </RadialBarChart>
            </ResponsiveContainer>
            <div className="score-center">
              <strong>{current.resumeScore}</strong>
              <span>/ 100</span>
            </div>
          </div>
          <h3>Resume Score</h3>
          <p>Based on skill coverage, completeness, role alignment, and improvement potential.</p>
        </div>

        <div className="glass-card chart-card wide">
          <div className="card-title">
            <h3>Job Match Ranking</h3>
            <span>{current.experienceLevel}</span>
          </div>
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={current.jobMatches} layout="vertical" margin={{ top: 8, right: 22, bottom: 8, left: 28 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#28324a" horizontal={false} />
              <XAxis type="number" domain={[0, 100]} tick={{ fill: '#aeb8d4', fontSize: 12 }} />
              <YAxis type="category" dataKey="role" tick={{ fill: '#f7f9ff', fontSize: 12 }} width={120} />
              <Tooltip cursor={{ fill: '#1b2540' }} contentStyle={{ background: '#11182b', border: '1px solid #33415f', borderRadius: 8, color: '#fff' }} />
              <Bar dataKey="percentage" radius={[0, 10, 10, 0]}>
                {current.jobMatches.map((_, index) => <Cell key={index} fill={COLORS[index % COLORS.length]} />)}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="insight-grid">
        <div className="metric-tile">
          <span>Top Role</span>
          <strong>{current.jobMatches[0]?.role || current.recommendedJobs[0] || 'Not ranked'}</strong>
        </div>
        <div className="metric-tile">
          <span>Technical Skills</span>
          <strong>{current.technicalSkills.length}</strong>
        </div>
        <div className="metric-tile">
          <span>Missing Skills</span>
          <strong>{current.missingSkills.length}</strong>
        </div>
        <div className="metric-tile">
          <span>Experience</span>
          <strong>{current.experienceLevel}</strong>
        </div>
      </div>

      <div className="dashboard-grid">
        <div className="glass-card chart-card">
          <h3>Skill Mix</h3>
          <ResponsiveContainer width="100%" height={230}>
            <PieChart>
              <Pie data={skillPieData} dataKey="value" nameKey="name" innerRadius={62} outerRadius={92} paddingAngle={4}>
                {skillPieData.map((_, index) => <Cell key={index} fill={COLORS[index % COLORS.length]} />)}
              </Pie>
              <Tooltip contentStyle={{ background: '#11182b', border: '1px solid #33415f', borderRadius: 8, color: '#fff' }} />
            </PieChart>
          </ResponsiveContainer>
          <div className="legend-row">
            {skillPieData.map((item, index) => (
              <span key={item.name}><i style={{ background: COLORS[index] }} />{item.name}</span>
            ))}
          </div>
        </div>

        <div className="glass-card chart-card">
          <h3>Profile Shape</h3>
          <ResponsiveContainer width="100%" height={260}>
            <RadarChart data={radarData}>
              <PolarGrid stroke="#33415f" />
              <PolarAngleAxis dataKey="subject" tick={{ fill: '#aeb8d4', fontSize: 11 }} />
              <PolarRadiusAxis angle={90} domain={[0, 100]} tick={false} axisLine={false} />
              <Radar dataKey="value" stroke="#6ee7d8" fill="#6ee7d8" fillOpacity={0.32} />
            </RadarChart>
          </ResponsiveContainer>
        </div>

        <div className="glass-card content-card">
          <h3>Technical Skills</h3>
          {renderTags(current.technicalSkills)}
          <h3 className="spaced-title">Soft Skills</h3>
          {renderTags(current.softSkills, 'soft-chip')}
        </div>

        <div className="glass-card content-card">
          <h3>Missing Skills</h3>
          {renderTags(current.missingSkills, 'missing-chip')}
          <h3 className="spaced-title">Recommended Jobs</h3>
          {renderTags(current.recommendedJobs, 'role-chip')}
        </div>
      </div>

      <div className="action-grid">
        <div className="glass-card">
          <h3>Strengths</h3>
          <ul className="clean-list">{current.strengths.map((item, index) => <li key={index}>{item}</li>)}</ul>
        </div>
        <div className="glass-card">
          <h3>Improvement Plan</h3>
          <ul className="clean-list">{current.improvements.map((item, index) => <li key={index}>{item}</li>)}</ul>
        </div>
      </div>

      <div className="advice-panel">
        <span>AI Advice</span>
        <p>{current.advice}</p>
      </div>
    </div>
  );

  return (
    <div className="dashboard">
      <header className="topbar">
        <div>
          <span className="brand-kicker">AI Resume Analyzer</span>
          <h1>Career Intelligence Studio</h1>
        </div>
        <div className="user-info">
          <span>{name}</span>
          <button className="logout-btn" onClick={handleLogout}>Logout</button>
        </div>
      </header>

      <nav className="tabs">
        {['analyze', 'history', 'chat', 'compare', 'profile'].map((tab) => (
          <button className={activeTab === tab ? 'tab active' : 'tab'} key={tab} onClick={() => setActiveTab(tab)}>
            {tab === 'analyze' ? 'Analyze' : tab === 'history' ? `History (${history.length})` : tab[0].toUpperCase() + tab.slice(1)}
          </button>
        ))}
      </nav>

      <main className="main">
        {activeTab === 'analyze' && (
          <>
            <section className="upload-panel">
              <div>
                <span className="eyebrow">Resume upload</span>
                <h2>Analyze Your Resume</h2>
                <p>Upload a PDF and get a visual score, job fit ranking, skill gaps, and an action plan.</p>
              </div>
              <div className="upload-box">
                <div className="upload-icon">PDF</div>
                <p>Drop your resume here or choose a file</p>
                <input type="file" accept=".pdf" onChange={handleFileChange} id="fileInput" style={{ display: 'none' }} />
                <label htmlFor="fileInput" className="browse-btn">Browse PDF</label>
                {file && <p className="file-name">{file.name}</p>}
              </div>
              {error && <div className="error-msg">{error}</div>}
              <button className="analyze-btn" onClick={handleAnalyze} disabled={loading}>
                {loading ? 'Analyzing Resume...' : 'Analyze Resume'}
              </button>
            </section>
            {renderAnalytics()}
          </>
        )}

        {activeTab === 'history' && (
          <section>
            <div className="page-heading">
              <span className="eyebrow">Saved reports</span>
              <h2>Analysis History</h2>
            </div>
            {history.length === 0 ? (
              <div className="empty-state">No analyses yet. Upload a resume to get started.</div>
            ) : (
              <div className="history-grid">
                {history.map((item) => {
                  const normalized = normalizeAnalysis(item);
                  return (
                    <div className="history-card" key={item.id}>
                      <div className="history-score">{normalized.resumeScore}</div>
                      <h3>{item.fileName}</h3>
                      <p>{new Date(item.analyzedAt).toLocaleString('en-IN')}</p>
                      {renderTags(normalized.technicalSkills.slice(0, 5))}
                      <div className="history-actions">
                        <button className="ghost-btn" onClick={() => reopenAnalysis(item)}>Open</button>
                        <button className="ghost-btn" onClick={() => downloadReport(item.id)}>PDF</button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </section>
        )}

        {activeTab === 'chat' && (
          <section className="glass-card chat-box">
            <div className="page-heading">
              <span className="eyebrow">Resume coach</span>
              <h2>Chat with Resume</h2>
            </div>
            <div className="chat-messages">
              {chatMessages.length === 0 && <div className="empty-state compact">Ask how to improve, which role fits best, or what skill to learn next.</div>}
              {chatMessages.map((message, index) => <div className={`chat-message ${message.role}`} key={index}>{message.text}</div>)}
              {chatLoading && <div className="chat-message assistant">Thinking...</div>}
            </div>
            <div className="inline-form">
              <input value={chatQuestion} onChange={(e) => setChatQuestion(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && sendChat()} placeholder="How can I improve this resume?" />
              <button className="analyze-btn compact" onClick={sendChat}>Send</button>
            </div>
          </section>
        )}

        {activeTab === 'compare' && (
          <section>
            <div className="page-heading">
              <span className="eyebrow">Side by side</span>
              <h2>Resume Comparison</h2>
            </div>
            <div className="inline-form">
              <select value={firstCompareId} onChange={(e) => setFirstCompareId(e.target.value)}>
                <option value="">First resume</option>
                {history.map((item) => <option value={item.id} key={item.id}>{item.fileName}</option>)}
              </select>
              <select value={secondCompareId} onChange={(e) => setSecondCompareId(e.target.value)}>
                <option value="">Second resume</option>
                {history.map((item) => <option value={item.id} key={item.id}>{item.fileName}</option>)}
              </select>
              <button className="analyze-btn compact" onClick={compareResumes}>Compare</button>
            </div>
            {comparison?.error && <div className="error-msg">{comparison.error}</div>}
            {comparison?.first && (
              <div className="comparison-grid">
                {[comparison.first, comparison.second].map((item) => (
                  <div className="glass-card compare-card" key={item.id}>
                    <span className="history-score">{item.score}</span>
                    <h3>{item.fileName}</h3>
                    <p>{item.experienceLevel}</p>
                    {renderTags(item.technicalSkills || [])}
                  </div>
                ))}
                <div className="advice-panel full"><span>Winner</span><p>{comparison.winner}</p></div>
              </div>
            )}
          </section>
        )}

        {activeTab === 'profile' && (
          <section>
            <div className="page-heading">
              <span className="eyebrow">Portfolio signal</span>
              <h2>GitHub / LinkedIn Analyzer</h2>
            </div>
            <div className="inline-form">
              <input value={profileUrl} onChange={(e) => setProfileUrl(e.target.value)} placeholder="https://github.com/username" />
              <button className="analyze-btn compact" onClick={analyzeProfile} disabled={profileLoading}>{profileLoading ? 'Analyzing...' : 'Analyze'}</button>
            </div>
            {profileInsights?.error && <div className="error-msg">{profileInsights.error}</div>}
            {profileInsights?.skills && (
              <div className="dashboard-grid profile-results">
                <div className="glass-card"><h3>Profile Skills</h3>{renderTags(profileInsights.skills)}</div>
                <div className="glass-card"><h3>Project Signals</h3><ul className="clean-list">{profileInsights.projectSignals.map((item, index) => <li key={index}>{item}</li>)}</ul></div>
                <div className="advice-panel full"><span>Career Summary</span><p>{profileInsights.careerSummary}</p></div>
              </div>
            )}
          </section>
        )}
      </main>
    </div>
  );
}

export default Dashboard;
