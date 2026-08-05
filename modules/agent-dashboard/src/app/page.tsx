'use client';

import React, { useState } from 'react';
import { Header, DashboardTab } from '@/components/Header';
import { RunInspector } from '@/components/RunInspector';
import { RagInspector } from '@/components/RagInspector';
import { QueueOps } from '@/components/QueueOps';
import { ConfigStudio } from '@/components/ConfigStudio';
import { SecurityArtifacts } from '@/components/SecurityArtifacts';
import { EvalAnalytics } from '@/components/EvalAnalytics';
import {
  MOCK_RUNS,
  MOCK_KNOWLEDGE_DOCS,
  MOCK_PDF_BLOCKS,
  MOCK_RETRIEVAL_HITS,
  MOCK_WORKERS,
  MOCK_QUEUE_SNAPSHOT,
  MOCK_EVAL_TRENDS,
  MOCK_CONFIG,
  MOCK_ARTIFACTS
} from '@/lib/api';

export default function Home() {
  const [activeTab, setActiveTab] = useState<DashboardTab>('runs');
  const [serverUrl, setServerUrl] = useState('http://localhost:8080');
  const [selectedRunId, setSelectedRunId] = useState(MOCK_RUNS[0].runId);

  // External URLs (can be customized by environment variables)
  const langfuseUrl = 'http://localhost:3000';
  const grafanaUrl = 'http://localhost:3001';

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans selection:bg-indigo-500 selection:text-white">
      {/* Top Fixed Header */}
      <Header
        activeTab={activeTab}
        onTabChange={setActiveTab}
        serverUrl={serverUrl}
        onServerUrlChange={setServerUrl}
        langfuseUrl={langfuseUrl}
        grafanaUrl={grafanaUrl}
      />

      {/* Main Tab Views */}
      <main className="flex-1">
        {activeTab === 'runs' && (
          <RunInspector
            runs={MOCK_RUNS}
            selectedRunId={selectedRunId}
            onSelectRun={setSelectedRunId}
            langfuseUrl={langfuseUrl}
          />
        )}

        {activeTab === 'rag' && (
          <RagInspector
            initialDocs={MOCK_KNOWLEDGE_DOCS}
            blocks={MOCK_PDF_BLOCKS}
            hits={MOCK_RETRIEVAL_HITS}
          />
        )}

        {activeTab === 'queue' && (
          <QueueOps
            workers={MOCK_WORKERS}
            queueSnapshot={MOCK_QUEUE_SNAPSHOT}
          />
        )}

        {activeTab === 'config' && (
          <ConfigStudio
            initialConfig={MOCK_CONFIG}
          />
        )}

        {activeTab === 'security' && (
          <SecurityArtifacts
            artifacts={MOCK_ARTIFACTS}
            allowedTools={MOCK_CONFIG.allowedToolNames}
          />
        )}

        {activeTab === 'evals' && (
          <EvalAnalytics
            trends={MOCK_EVAL_TRENDS}
          />
        )}
      </main>
    </div>
  );
}
