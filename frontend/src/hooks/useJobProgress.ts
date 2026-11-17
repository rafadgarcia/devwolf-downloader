import { useCallback, useEffect, useRef, useState } from 'react';
import { api, getJobFileDownloadUrl } from '../services/api';
import type { JobResponse } from '../types/api';
import { clearActiveJobById } from '../utils/dedupe';

const TERMINAL_STATES = new Set(['ready', 'failed', 'cancelled', 'expired']);
const AUTO_DOWNLOADED_KEY = 'droplink_auto_downloaded_jobs_v1';

function hasTriggeredAutoDownload(jobId: string): boolean {
  try {
    const raw = window.sessionStorage.getItem(AUTO_DOWNLOADED_KEY);
    if (!raw) return false;
    const list = JSON.parse(raw) as string[];
    return Array.isArray(list) && list.includes(jobId);
  } catch {
    return false;
  }
}

function markAutoDownloadTriggered(jobId: string): void {
  try {
    const raw = window.sessionStorage.getItem(AUTO_DOWNLOADED_KEY);
    const list = raw ? (JSON.parse(raw) as string[]) : [];
    const next = Array.isArray(list) ? Array.from(new Set([...list, jobId])).slice(-50) : [jobId];
    window.sessionStorage.setItem(AUTO_DOWNLOADED_KEY, JSON.stringify(next));
  } catch {
    // Ignorar falha de sessionStorage
  }
}

export function triggerBrowserDownload(jobId: string, fileId: string, filename?: string): void {
  const url = getJobFileDownloadUrl(jobId, fileId);
  const anchor = document.createElement('a');
  anchor.href = url;
  if (filename) {
    anchor.download = filename;
  }
  anchor.rel = 'noopener';
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
}

export function useJobProgress(initialJob: JobResponse | null) {
  const [job, setJob] = useState<JobResponse | null>(initialJob);
  const [engineSwitchNotice, setEngineSwitchNotice] = useState<string | null>(null);
  const [connectionMode, setConnectionMode] = useState<'sse' | 'polling' | 'idle'>('idle');
  const lastEngineRef = useRef<string | null>(null);

  const updateJob = useCallback((nextJob: JobResponse) => {
    setJob((prev) => {
      const prevEngine = prev?.current_engine ?? lastEngineRef.current;
      const nextEngine = nextJob.current_engine;
      if (prevEngine && nextEngine && prevEngine !== nextEngine) {
        setEngineSwitchNotice(`Alternando motor: ${prevEngine} → ${nextEngine}`);
      } else if (nextJob.attempts && nextJob.attempts.length > 1 && nextEngine) {
        const prevFailed = nextJob.attempts
          .slice(0, -1)
          .find((a) => a.status === 'failed' && a.engine !== nextEngine);
        if (prevFailed) {
          setEngineSwitchNotice(`Fallback automático: ${prevFailed.engine} → ${nextEngine}`);
        }
      }
      if (nextEngine) {
        lastEngineRef.current = nextEngine;
      }
      return nextJob;
    });

    if (TERMINAL_STATES.has(nextJob.status)) {
      clearActiveJobById(nextJob.id);
    }

    if (
      nextJob.status === 'ready' &&
      nextJob.files?.length > 0 &&
      document.visibilityState === 'visible' &&
      !hasTriggeredAutoDownload(nextJob.id)
    ) {
      markAutoDownloadTriggered(nextJob.id);
      const primaryFile = nextJob.files[0];
      triggerBrowserDownload(nextJob.id, primaryFile.id, primaryFile.filename);
    }
  }, []);

  const setActiveJob = useCallback((newJob: JobResponse | null) => {
    lastEngineRef.current = newJob?.current_engine ?? null;
    setEngineSwitchNotice(null);
    setJob(newJob);
  }, []);

  const jobId = job?.id ?? null;
  const jobStatus = job?.status ?? null;

  useEffect(() => {
    if (!jobId || !jobStatus || TERMINAL_STATES.has(jobStatus)) {
      setConnectionMode('idle');
      return;
    }

    let cancelled = false;
    let pollTimer: number | null = null;
    let eventSource: EventSource | null = null;

    const startPolling = () => {
      if (cancelled) return;
      setConnectionMode('polling');
      const poll = async () => {
        if (cancelled) return;
        try {
          const fresh = await api.getJob(jobId);
          if (!cancelled) {
            updateJob(fresh);
            if (!TERMINAL_STATES.has(fresh.status)) {
              pollTimer = window.setTimeout(poll, 1500);
            }
          }
        } catch {
          if (!cancelled) {
            pollTimer = window.setTimeout(poll, 2500);
          }
        }
      };
      pollTimer = window.setTimeout(poll, 800);
    };

    if (typeof EventSource !== 'undefined') {
      try {
        setConnectionMode('sse');
        eventSource = new EventSource(`/api/jobs/${encodeURIComponent(jobId)}/events`, {
          withCredentials: true,
        });

        const handlePayload = (rawData: string) => {
          if (cancelled) return;
          try {
            const parsed = JSON.parse(rawData) as JobResponse;
            if (parsed && parsed.id) {
              updateJob(parsed);
              if (TERMINAL_STATES.has(parsed.status)) {
                eventSource?.close();
              }
            }
          } catch {
            // Ignorar heartbeat ou payload não JSON
          }
        };

        eventSource.onmessage = (event) => handlePayload(event.data);
        eventSource.addEventListener('progress', (event) =>
          handlePayload((event as MessageEvent).data)
        );
        eventSource.addEventListener('status', (event) =>
          handlePayload((event as MessageEvent).data)
        );
        eventSource.addEventListener('job', (event) =>
          handlePayload((event as MessageEvent).data)
        );

        eventSource.onerror = () => {
          eventSource?.close();
          eventSource = null;
          if (!cancelled) {
            startPolling();
          }
        };
      } catch {
        startPolling();
      }
    } else {
      startPolling();
    }

    return () => {
      cancelled = true;
      eventSource?.close();
      if (pollTimer !== null) {
        window.clearTimeout(pollTimer);
      }
    };
  }, [jobId, jobStatus, updateJob]);

  return {
    job,
    setActiveJob,
    updateJob,
    engineSwitchNotice,
    connectionMode,
  };
}
