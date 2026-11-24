import { useCallback, useEffect, useRef, useState } from 'react';
import { applyLocalProgressToJob, upsertLocalJob } from '../services/localQueue';
import { nativeBridge } from '../services/nativeBridge';
import type { JobResponse } from '../types/api';
import { clearActiveJobById } from '../utils/dedupe';

const TERMINAL_STATES = new Set(['ready', 'failed', 'cancelled', 'expired']);

export function useJobProgress(initialJob: JobResponse | null) {
  const [job, setJob] = useState<JobResponse | null>(initialJob);
  const [engineSwitchNotice, setEngineSwitchNotice] = useState<string | null>(null);
  const [connectionMode, setConnectionMode] = useState<'local' | 'idle'>('idle');
  const lastEngineRef = useRef<string | null>(null);
  const jobRef = useRef<JobResponse | null>(initialJob);

  const updateJob = useCallback((nextJob: JobResponse) => {
    jobRef.current = nextJob;

    setJob((prev) => {
      const prevEngine = prev?.current_engine ?? lastEngineRef.current;
      const nextEngine = nextJob.current_engine;
      if (nextJob.fallback_notice) {
        setEngineSwitchNotice(nextJob.fallback_notice);
      } else if (prevEngine && nextEngine && prevEngine !== nextEngine) {
        setEngineSwitchNotice(`Alternando motor: ${prevEngine} → ${nextEngine}`);
      }
      if (nextEngine) {
        lastEngineRef.current = nextEngine;
      }
      return nextJob;
    });

    upsertLocalJob(nextJob, { throttleProgress: true });

    if (TERMINAL_STATES.has(nextJob.status)) {
      clearActiveJobById(nextJob.id);
    }
  }, []);

  const setActiveJob = useCallback((newJob: JobResponse | null) => {
    jobRef.current = newJob;
    lastEngineRef.current = newJob?.current_engine ?? null;
    setEngineSwitchNotice(newJob?.fallback_notice ?? null);
    setJob(newJob);
    if (newJob) {
      upsertLocalJob(newJob);
    }
  }, []);

  const jobId = job?.id ?? null;
  const jobStatus = job?.status ?? null;

  useEffect(() => {
    if (!jobId || !jobStatus || TERMINAL_STATES.has(jobStatus)) {
      setConnectionMode('idle');
      return;
    }

    setConnectionMode('local');
    let cancelled = false;
    let removeListener: (() => void) | null = null;

    void nativeBridge
      .onLocalDownloadProgress((event) => {
        if (cancelled || event.jobId !== jobId) return;
        const next = applyLocalProgressToJob(jobRef.current, event);
        updateJob(next);
      })
      .then((unsub) => {
        if (cancelled) {
          unsub();
        } else {
          removeListener = unsub;
        }
      });

    return () => {
      cancelled = true;
      removeListener?.();
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
