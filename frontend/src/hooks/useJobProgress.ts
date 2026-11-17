import { useCallback, useEffect, useRef, useState } from 'react';
import {
  api,
  buildApiUrl,
  getAuthHeaders,
  getBearerToken,
  getJobFileDownloadUrl,
  isCrossOriginVps,
} from '../services/api';
import { decorateVpsJob } from '../services/hybridOrchestrator';
import {
  applyLocalProgressToJob,
  mapLocalFilesToJobFiles,
  upsertLocalJob,
} from '../services/localQueue';
import { nativeBridge } from '../services/nativeBridge';
import type { JobResponse } from '../types/api';
import { clearActiveJobById } from '../utils/dedupe';

const TERMINAL_STATES = new Set(['ready', 'failed', 'cancelled', 'expired']);
const AUTO_DOWNLOADED_KEY = 'devwolf_auto_downloaded_jobs_v2';

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

interface UseJobProgressOptions {
  onRecoverableFailure?: (failedJob: JobResponse) => Promise<boolean> | boolean;
}

export function useJobProgress(
  initialJob: JobResponse | null,
  options?: UseJobProgressOptions
) {
  const [job, setJob] = useState<JobResponse | null>(initialJob);
  const [engineSwitchNotice, setEngineSwitchNotice] = useState<string | null>(null);
  const [connectionMode, setConnectionMode] = useState<'sse' | 'polling' | 'local' | 'idle'>('idle');
  const lastEngineRef = useRef<string | null>(null);
  const jobRef = useRef<JobResponse | null>(initialJob);
  const onRecoverableFailureRef = useRef(options?.onRecoverableFailure);
  onRecoverableFailureRef.current = options?.onRecoverableFailure;

  const updateJob = useCallback((rawNextJob: JobResponse) => {
    const nextJob =
      rawNextJob.execution_origin === 'local'
        ? rawNextJob
        : decorateVpsJob(rawNextJob, {
            statusMode: jobRef.current?.status_mode,
            segmentDuration: jobRef.current?.segment_duration,
            keepOriginal: jobRef.current?.keep_original,
            fromShareIntent: jobRef.current?.from_share_intent,
            fallbackNotice: jobRef.current?.fallback_notice,
          });

    jobRef.current = nextJob;

    setJob((prev) => {
      const prevEngine = prev?.current_engine ?? lastEngineRef.current;
      const nextEngine = nextJob.current_engine;
      if (nextJob.fallback_notice) {
        setEngineSwitchNotice(nextJob.fallback_notice);
      } else if (prevEngine && nextEngine && prevEngine !== nextEngine) {
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

    if (nextJob.execution_origin === 'local') {
      upsertLocalJob(nextJob);
    }

    if (TERMINAL_STATES.has(nextJob.status)) {
      clearActiveJobById(nextJob.id);
    }

    if (
      nextJob.status === 'ready' &&
      nextJob.files?.length > 0 &&
      nextJob.execution_origin !== 'local' &&
      !hasTriggeredAutoDownload(nextJob.id)
    ) {
      markAutoDownloadTriggered(nextJob.id);
      const primaryFile = nextJob.files[0];

      if (nativeBridge.isNativePlatform()) {
        // No Android, baixa o arquivo pronto da VPS direto para o álbum MediaStore "Devwolf Downloader"
        void nativeBridge
          .downloadRemoteFileToGallery({
            url: getJobFileDownloadUrl(nextJob.id, primaryFile.id),
            headers: getAuthHeaders(),
            fileName: primaryFile.filename,
            mimeType: primaryFile.mime_type,
            statusMode: nextJob.status_mode ?? false,
            segmentDurationSeconds: nextJob.segment_duration ?? null,
            keepOriginal: nextJob.keep_original ?? false,
          })
          .then((savedFiles) => {
            if (savedFiles.length > 0) {
              const mapped = mapLocalFilesToJobFiles(savedFiles);
              setJob((curr) => {
                if (!curr || curr.id !== nextJob.id) return curr;
                const updated: JobResponse = { ...curr, files: mapped };
                jobRef.current = updated;
                upsertLocalJob(updated);
                return updated;
              });
            }
          })
          .catch(() => {
            // Se falhar o salvamento automático, usuário ainda pode clicar em Baixar arquivo
          });
      } else if (document.visibilityState === 'visible') {
        triggerBrowserDownload(nextJob.id, primaryFile.id, primaryFile.filename);
      }
    }
  }, []);

  const setActiveJob = useCallback((newJob: JobResponse | null) => {
    jobRef.current = newJob;
    lastEngineRef.current = newJob?.current_engine ?? null;
    setEngineSwitchNotice(newJob?.fallback_notice ?? null);
    setJob(newJob);
    if (newJob?.execution_origin === 'local') {
      upsertLocalJob(newJob);
    }
  }, []);

  const jobId = job?.id ?? null;
  const jobStatus = job?.status ?? null;
  const executionOrigin = job?.execution_origin ?? 'vps';

  // Assinar eventos locais nativos do Android quando o job for local
  useEffect(() => {
    if (!jobId || executionOrigin !== 'local' || !jobStatus || TERMINAL_STATES.has(jobStatus)) {
      return;
    }

    setConnectionMode('local');
    let cancelled = false;
    let removeListener: (() => void) | null = null;

    void nativeBridge
      .onLocalDownloadProgress((event) => {
        if (cancelled || event.jobId !== jobId) return;
        const next = applyLocalProgressToJob(jobRef.current, event);

        if (next.status === 'failed' && onRecoverableFailureRef.current) {
          void Promise.resolve(onRecoverableFailureRef.current(next)).then((handled) => {
            if (!handled && !cancelled) {
              updateJob(next);
            }
          });
          return;
        }

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
  }, [executionOrigin, jobId, jobStatus, updateJob]);

  // Assinar SSE / Polling para jobs da VPS
  useEffect(() => {
    if (
      !jobId ||
      executionOrigin === 'local' ||
      !jobStatus ||
      TERMINAL_STATES.has(jobStatus)
    ) {
      if (executionOrigin !== 'local') {
        setConnectionMode('idle');
      }
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

    const useEventSource =
      typeof EventSource !== 'undefined' && !(isCrossOriginVps() && Boolean(getBearerToken()));

    if (useEventSource) {
      try {
        setConnectionMode('sse');
        const sseUrl = buildApiUrl(`/api/jobs/${encodeURIComponent(jobId)}/events`);
        eventSource = new EventSource(sseUrl, {
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
  }, [executionOrigin, jobId, jobStatus, updateJob]);

  return {
    job,
    setActiveJob,
    updateJob,
    engineSwitchNotice,
    connectionMode,
  };
}
