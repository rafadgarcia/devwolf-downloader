import { useCallback, useEffect, useRef, useState } from 'react';
import { nativeBridge } from '../services/nativeBridge';
import { extractSupportedUrlsFromText, type ParsedPlatformUrl } from '../utils/url';

export type ClipboardPermissionStatus =
  | 'idle'
  | 'granted'
  | 'prompt'
  | 'denied'
  | 'unsupported'
  | 'empty';

interface UseClipboardAutoDetectOptions {
  enabled: boolean;
  isUserEditing: boolean;
  onSingleUrlDetected: (parsed: ParsedPlatformUrl, explicitUserGesture: boolean) => void;
  onMultipleUrlsDetected: (candidates: ParsedPlatformUrl[]) => void;
}

export function useClipboardAutoDetect({
  enabled,
  isUserEditing,
  onSingleUrlDetected,
  onMultipleUrlsDetected,
}: UseClipboardAutoDetectOptions) {
  const [permissionStatus, setPermissionStatus] = useState<ClipboardPermissionStatus>('idle');
  const [guidanceMessage, setGuidanceMessage] = useState<string | null>(null);
  const mutexRef = useRef(false);
  const debounceTimerRef = useRef<number | null>(null);
  const lastHandledClipboardTextRef = useRef<string | null>(null);

  const checkAndReadClipboard = useCallback(
    async (explicitUserGesture = false): Promise<boolean> => {
      if (!enabled && !explicitUserGesture) {
        return false;
      }

      if (mutexRef.current) {
        return false;
      }

      if (!explicitUserGesture && isUserEditing) {
        return false;
      }

      const isNative = nativeBridge.isNativePlatform();

      if (
        !isNative &&
        (typeof navigator === 'undefined' ||
          !navigator.clipboard ||
          typeof navigator.clipboard.readText !== 'function' ||
          !window.isSecureContext)
      ) {
        setPermissionStatus('unsupported');
        setGuidanceMessage('Toque em Colar para importar um link');
        return false;
      }

      if (!explicitUserGesture && document.visibilityState !== 'visible') {
        return false;
      }

      mutexRef.current = true;
      try {
        if (
          !isNative &&
          !explicitUserGesture &&
          navigator.permissions &&
          typeof navigator.permissions.query === 'function'
        ) {
          try {
            const status = await navigator.permissions.query({
              name: 'clipboard-read' as PermissionName,
            });
            if (status.state === 'denied') {
              setPermissionStatus('denied');
              setGuidanceMessage('Toque em Colar para importar um link');
              return false;
            }
            if (status.state === 'prompt') {
              setPermissionStatus('prompt');
              setGuidanceMessage('Toque em Colar para importar um link');
              return false;
            }
            setPermissionStatus('granted');
          } catch {
            // Navegador não implementa consulta 'clipboard-read'
          }
        }

        if (!isNative && !explicitUserGesture && !document.hasFocus()) {
          return false;
        }

        const rawText = isNative
          ? await nativeBridge.readClipboard()
          : await navigator.clipboard.readText();

        if (!rawText || !rawText.trim()) {
          setPermissionStatus('empty');
          if (explicitUserGesture) {
            setGuidanceMessage('Área de transferência vazia.');
          }
          return false;
        }

        if (!explicitUserGesture && rawText === lastHandledClipboardTextRef.current) {
          return false;
        }

        const candidates = extractSupportedUrlsFromText(rawText);
        if (candidates.length === 0) {
          if (explicitUserGesture) {
            setGuidanceMessage('Nenhum link de plataforma suportada foi encontrado no texto colado.');
          }
          return false;
        }

        lastHandledClipboardTextRef.current = rawText;
        setGuidanceMessage(null);
        setPermissionStatus('granted');

        if (candidates.length === 1) {
          onSingleUrlDetected(candidates[0], explicitUserGesture);
        } else {
          onMultipleUrlsDetected(candidates);
        }
        return true;
      } catch {
        setPermissionStatus('denied');
        setGuidanceMessage('Toque em Colar para importar um link');
        return false;
      } finally {
        mutexRef.current = false;
      }
    },
    [enabled, isUserEditing, onMultipleUrlsDetected, onSingleUrlDetected]
  );

  useEffect(() => {
    if (!enabled) return;

    const scheduleCheck = () => {
      if (debounceTimerRef.current !== null) {
        window.clearTimeout(debounceTimerRef.current);
      }
      debounceTimerRef.current = window.setTimeout(() => {
        void checkAndReadClipboard(false);
      }, 500);
    };

    scheduleCheck();

    const handleFocus = () => {
      scheduleCheck();
    };

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        scheduleCheck();
      }
    };

    window.addEventListener('focus', handleFocus);
    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      if (debounceTimerRef.current !== null) {
        window.clearTimeout(debounceTimerRef.current);
      }
      window.removeEventListener('focus', handleFocus);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [enabled, checkAndReadClipboard]);

  return {
    permissionStatus,
    guidanceMessage,
    setGuidanceMessage,
    readClipboardExplicitly: () => checkAndReadClipboard(true),
  };
}
