import { Capacitor } from '@capacitor/core';
import { useMemo, useState } from 'react';
import type { CarouselMediaType, PlatformId } from '../types/api';
import { PLATFORM_META } from '../utils/format';
import { PlatformIcon } from './PlatformIcon';

interface MediaThumbnailProps {
  thumbnailDataUrl?: string | null;
  mediaUri?: string | null;
  filePath?: string | null;
  mediaType?: CarouselMediaType;
  platform?: PlatformId | string | null;
  alt?: string;
  className?: string;
  compact?: boolean;
}

const PLATFORM_GRADIENT: Record<string, string> = {
  instagram: 'from-[#4F378B] via-[#311F6B] to-[#1A133B]',
  threads: 'from-[#362C63] via-[#211A45] to-[#120E29]',
  youtube: 'from-[#4E235C] via-[#2D1748] to-[#140E2E]',
  tiktok: 'from-[#2A336D] via-[#1E1D4D] to-[#110E2A]',
  twitter: 'from-[#293D75] via-[#1C2352] to-[#101230]',
  facebook: 'from-[#2A3A7A] via-[#1D2254] to-[#111230]',
  reddit: 'from-[#512B68] via-[#2F1A4B] to-[#150E2E]',
  pinterest: 'from-[#59265E] via-[#341848] to-[#160E2C]',
};

function resolveFallbackSrc(
  mediaType: CarouselMediaType,
  mediaUri?: string | null,
  filePath?: string | null
): { kind: 'image' | 'video'; src: string } | null {
  if (filePath) {
    try {
      const converted = Capacitor.convertFileSrc(filePath);
      if (converted && !converted.startsWith('file://')) {
        return { kind: mediaType === 'video' ? 'video' : 'image', src: converted };
      }
    } catch {
      // Ignorar se Capacitor.convertFileSrc não estiver disponível
    }
  }
  if (mediaUri && /^(https?:|data:|blob:|capacitor:)/i.test(mediaUri)) {
    return { kind: mediaType === 'video' ? 'video' : 'image', src: mediaUri };
  }
  return null;
}

export function MediaThumbnail({
  thumbnailDataUrl,
  mediaUri,
  filePath,
  mediaType = 'video',
  platform = 'instagram',
  alt = 'Prévia da mídia',
  className = '',
  compact = false,
}: MediaThumbnailProps) {
  const [imgFailed, setImgFailed] = useState(false);

  const resolved = useMemo(() => {
    if (thumbnailDataUrl && thumbnailDataUrl.startsWith('data:image/')) {
      return { kind: 'image' as const, src: thumbnailDataUrl };
    }
    if (mediaType === 'audio') return null;
    return resolveFallbackSrc(mediaType, mediaUri, filePath);
  }, [filePath, mediaType, mediaUri, thumbnailDataUrl]);

  const normalizedPlatform = (platform ?? 'instagram').toLowerCase();
  const gradient = PLATFORM_GRADIENT[normalizedPlatform] ?? PLATFORM_GRADIENT.instagram;
  const meta =
    PLATFORM_META[(normalizedPlatform as PlatformId) in PLATFORM_META ? (normalizedPlatform as PlatformId) : 'instagram'];

  if (resolved && !imgFailed) {
    if (resolved.kind === 'video') {
      return (
        <video
          src={resolved.src}
          preload="metadata"
          muted
          playsInline
          aria-label={alt}
          onError={() => setImgFailed(true)}
          className={`h-full w-full object-cover ${className}`}
        />
      );
    }

    return (
      <img
        src={resolved.src}
        alt={alt}
        loading="lazy"
        onError={() => setImgFailed(true)}
        className={`h-full w-full object-cover ${className}`}
      />
    );
  }

  return (
    <div
      className={`relative flex h-full w-full items-center justify-center overflow-hidden bg-gradient-to-br ${gradient} ${className}`}
    >
      <div
        className="pointer-events-none absolute -right-8 -top-8 h-28 w-28 rounded-full bg-[#7C5CFF]/30 blur-2xl"
        aria-hidden="true"
      />
      <div
        className="pointer-events-none absolute -bottom-8 -left-8 h-28 w-28 rounded-full bg-[#A78BFA]/20 blur-2xl"
        aria-hidden="true"
      />
      <div className="relative flex flex-col items-center gap-1.5 text-center">
        <div
          className={`flex items-center justify-center rounded-2xl border border-white/15 bg-white/10 text-[#E0DBFF] backdrop-blur-md ${
            compact ? 'h-9 w-9' : 'h-12 w-12'
          }`}
        >
          <PlatformIcon
            platform={normalizedPlatform}
            className={compact ? 'h-5 w-5 text-white' : 'h-6 w-6 text-white'}
          />
        </div>
        {!compact && (
          <span className="rounded-full border border-white/10 bg-black/25 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-[#C4B5FD]">
            {meta.label}
          </span>
        )}
      </div>
    </div>
  );
}
