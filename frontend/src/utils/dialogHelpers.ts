import { useEffect, useRef } from 'react';

/**
 * Gerencia abertura/fechamento de <dialog> nativo via showModal(),
 * redefine returnValue ao fechar e aplica fallback de light-dismiss
 * quando `closedBy` não é suportado pelo navegador/WebView.
 */
export function useModalDialog(open: boolean, onClose: () => void) {
  const dialogRef = useRef<HTMLDialogElement | null>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    if (open) {
      if (!dialog.open) {
        if (typeof dialog.showModal === 'function') {
          dialog.showModal();
        } else {
          dialog.setAttribute('open', '');
        }
      }
    } else if (dialog.open) {
      if (typeof dialog.close === 'function') {
        dialog.close();
      } else {
        dialog.removeAttribute('open');
      }
    }
  }, [open]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    const handleClose = () => {
      dialog.returnValue = '';
      onCloseRef.current();
    };

    dialog.addEventListener('close', handleClose);

    // Fallback obrigatório para navegadores sem suporte a <dialog closedby="any">
    let removePointerListeners: (() => void) | null = null;
    if (!('closedBy' in HTMLDialogElement.prototype)) {
      const outside = (e: PointerEvent) => {
        const r = dialog.getBoundingClientRect();
        return (
          e.clientX < r.left ||
          e.clientX > r.right ||
          e.clientY < r.top ||
          e.clientY > r.bottom
        );
      };
      let pressedOutside = false;
      const onPointerDown = (e: PointerEvent) => {
        pressedOutside = outside(e);
      };
      const onPointerUp = (e: PointerEvent) => {
        if (pressedOutside && outside(e) && dialog.open) {
          if (typeof dialog.close === 'function') {
            dialog.close();
          } else {
            dialog.removeAttribute('open');
            onCloseRef.current();
          }
        }
        pressedOutside = false;
      };
      dialog.addEventListener('pointerdown', onPointerDown);
      dialog.addEventListener('pointerup', onPointerUp);
      removePointerListeners = () => {
        dialog.removeEventListener('pointerdown', onPointerDown);
        dialog.removeEventListener('pointerup', onPointerUp);
      };
    }

    return () => {
      dialog.removeEventListener('close', handleClose);
      removePointerListeners?.();
    };
  }, []);

  return dialogRef;
}
