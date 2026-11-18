import type { PlatformId } from '../types/api';

interface PlatformIconProps {
  platform?: PlatformId | string | null;
  className?: string;
  'aria-hidden'?: boolean;
  title?: string;
}

export function normalizePlatformIconId(platform?: string | null): PlatformId {
  const normalized = (platform ?? '').trim().toLowerCase();
  switch (normalized) {
    case 'threads':
      return 'threads';
    case 'tiktok':
      return 'tiktok';
    case 'twitter':
    case 'x':
      return 'twitter';
    case 'facebook':
      return 'facebook';
    case 'pinterest':
      return 'pinterest';
    case 'reddit':
      return 'reddit';
    case 'youtube':
      return 'youtube';
    case 'instagram':
    default:
      return 'instagram';
  }
}

export function PlatformIcon({
  platform,
  className = 'h-4 w-4',
  'aria-hidden': ariaHidden = true,
  title,
}: PlatformIconProps) {
  const id = normalizePlatformIconId(platform);
  const roleProps = ariaHidden
    ? { 'aria-hidden': true as const }
    : { role: 'img' as const, 'aria-label': title || id };

  switch (id) {
    case 'threads':
      return (
        <svg
          viewBox="0 0 192 192"
          fill="currentColor"
          className={className}
          {...roleProps}
        >
          {title ? <title>{title}</title> : null}
          <path d="M141.537 88.988a66.667 66.667 0 0 0-2.518-1.143c-1.482-27.307-16.403-42.94-41.457-43.1h-.34c-14.986 0-27.449 6.396-35.12 18.036l13.779 9.452c5.73-8.695 14.724-10.548 21.348-10.548h.229c8.249.053 14.474 2.452 18.503 7.129 2.932 3.405 4.893 8.111 5.864 14.05-7.314-1.243-15.224-1.626-23.68-1.14-23.82 1.371-39.134 15.264-38.105 34.568.522 9.792 5.4 18.216 13.735 23.719 7.047 4.652 16.124 6.927 25.557 6.412 12.458-.683 22.231-5.436 29.049-14.127 5.178-6.6 8.453-15.153 9.899-25.93 5.937 3.583 10.337 8.298 12.767 13.966 4.132 9.635 4.373 25.468-8.546 38.376-11.319 11.308-24.925 16.2-45.488 16.351-22.809-.169-40.06-7.484-51.275-21.742C35.236 139.966 29.808 120.682 29.605 96c.203-24.682 5.63-43.966 16.133-57.317C56.954 24.425 74.204 17.11 97.013 16.94c22.975.17 40.526 7.52 52.171 21.847 5.71 7.026 10.015 15.86 12.853 26.162l16.147-4.308c-3.44-12.68-8.853-23.606-16.219-32.668C147.036 9.607 125.202.195 97.07 0h-.113C68.882.195 47.292 9.643 32.788 28.08 19.882 44.485 13.224 67.315 13.001 95.932L13 96v.067c.224 28.617 6.882 51.447 19.788 67.854C47.292 182.358 68.882 191.806 96.957 192h.113c24.96-.173 42.554-6.708 57.048-21.189 18.963-18.945 18.392-42.692 12.142-57.27-4.484-10.454-13.033-18.945-24.723-24.553Zm-43.096 40.519c-10.44.588-21.286-4.098-21.82-14.135-.397-7.442 5.296-15.746 22.461-16.735 1.966-.113 3.895-.169 5.79-.169 6.235 0 12.068.606 17.371 1.765-1.978 24.702-13.58 28.713-23.802 29.274Z" />
        </svg>
      );

    case 'tiktok':
      return (
        <svg viewBox="0 0 24 24" fill="currentColor" className={className} {...roleProps}>
          {title ? <title>{title}</title> : null}
          <path d="M19.59 6.69a4.83 4.83 0 0 1-3.77-4.25V2h-3.45v13.67a2.89 2.89 0 0 1-2.88 2.5 2.89 2.89 0 0 1-2.89-2.89 2.89 2.89 0 0 1 2.89-2.89c.28 0 .54.04.79.1v-3.5a6.37 6.37 0 0 0-.79-.05A6.34 6.34 0 0 0 3.15 15.2a6.34 6.34 0 0 0 6.34 6.34 6.34 6.34 0 0 0 6.34-6.34V8.81a8.26 8.26 0 0 0 4.76 1.5v-3.4a4.85 4.85 0 0 1-1-.22Z" />
        </svg>
      );

    case 'twitter':
      return (
        <svg viewBox="0 0 24 24" fill="currentColor" className={className} {...roleProps}>
          {title ? <title>{title}</title> : null}
          <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231Zm-1.161 17.52h1.833L7.084 4.126H5.117Z" />
        </svg>
      );

    case 'facebook':
      return (
        <svg viewBox="0 0 24 24" fill="currentColor" className={className} {...roleProps}>
          {title ? <title>{title}</title> : null}
          <path d="M12 2C6.477 2 2 6.477 2 12c0 4.991 3.657 9.128 8.438 9.879V14.89h-2.54V12h2.54V9.797c0-2.506 1.492-3.89 3.777-3.89 1.094 0 2.238.195 2.238.195v2.46h-1.26c-1.243 0-1.63.771-1.63 1.562V12h2.773l-.443 2.89h-2.33v6.989C18.343 21.129 22 16.99 22 12c0-5.523-4.477-10-10-10Z" />
        </svg>
      );

    case 'pinterest':
      return (
        <svg viewBox="0 0 24 24" fill="currentColor" className={className} {...roleProps}>
          {title ? <title>{title}</title> : null}
          <path d="M12 2C6.477 2 2 6.477 2 12c0 4.237 2.636 7.855 6.356 9.312-.088-.791-.167-2.005.035-2.868.181-.78 1.172-4.97 1.172-4.97s-.299-.6-.299-1.486c0-1.39.806-2.428 1.81-2.428.852 0 1.264.64 1.264 1.408 0 .858-.546 2.14-.828 3.33-.236.995.5 1.807 1.48 1.807 1.778 0 3.144-1.874 3.144-4.58 0-2.393-1.72-4.068-4.176-4.068-2.845 0-4.516 2.135-4.516 4.34 0 .859.331 1.781.745 2.281a.3.3 0 0 1 .069.288l-.278 1.133c-.044.183-.145.223-.335.134-1.249-.581-2.03-2.407-2.03-3.874 0-3.154 2.292-6.052 6.608-6.052 3.469 0 6.165 2.473 6.165 5.776 0 3.447-2.173 6.22-5.19 6.22-1.013 0-1.965-.525-2.291-1.148l-.623 2.378c-.226.869-.835 1.958-1.244 2.621.937.29 1.931.446 2.962.446 5.523 0 10-4.477 10-10S17.523 2 12 2Z" />
        </svg>
      );

    case 'reddit':
      return (
        <svg viewBox="0 0 24 24" fill="currentColor" className={className} {...roleProps}>
          {title ? <title>{title}</title> : null}
          <path
            fillRule="evenodd"
            d="M12 2C6.48 2 2 6.48 2 12c0 1.96.57 3.79 1.55 5.33L2.3 20.9a.6.6 0 0 0 .77.77l3.57-1.25A9.95 9.95 0 0 0 12 22c5.52 0 10-4.48 10-10S17.52 2 12 2Zm3.52 3.25a1.55 1.55 0 1 1-.33 3.07l-2.06-.44-.48 2.28c1.12.08 2.13.4 2.95.92a1.65 1.65 0 0 1 2.73 1.24 1.65 1.65 0 0 1-.87 1.45c.03.2.05.41.05.62 0 2.38-2.47 4.31-5.51 4.31s-5.51-1.93-5.51-4.31c0-.21.02-.42.05-.62a1.65 1.65 0 0 1-.87-1.45 1.65 1.65 0 0 1 2.73-1.24c.82-.52 1.83-.84 2.95-.92l.58-2.75a.55.55 0 0 1 .65-.42l2.58.55a1.55 1.55 0 0 1 .94-.29Z"
          />
          <circle cx="9.8" cy="13.2" r="1.05" />
          <circle cx="14.2" cy="13.2" r="1.05" />
          <path d="M9.45 15.25h5.1c-.48 1.05-1.48 1.6-2.55 1.6s-2.07-.55-2.55-1.6Z" />
        </svg>
      );

    case 'youtube':
      return (
        <svg viewBox="0 0 24 24" fill="currentColor" className={className} {...roleProps}>
          {title ? <title>{title}</title> : null}
          <path
            fillRule="evenodd"
            d="M21.58 7.19a2.75 2.75 0 0 0-1.94-1.95C17.93 4.78 12 4.78 12 4.78s-5.93 0-7.64.46a2.75 2.75 0 0 0-1.94 1.95A28.8 28.8 0 0 0 2 12a28.8 28.8 0 0 0 .42 4.81 2.75 2.75 0 0 0 1.94 1.95c1.71.46 7.64.46 7.64.46s5.93 0 7.64-.46a2.75 2.75 0 0 0 1.94-1.95A28.8 28.8 0 0 0 22 12a28.8 28.8 0 0 0-.42-4.81ZM10 15.2V8.8l5.5 3.2-5.5 3.2Z"
          />
        </svg>
      );

    case 'instagram':
    default:
      return (
        <svg viewBox="0 0 24 24" fill="currentColor" className={className} {...roleProps}>
          {title ? <title>{title}</title> : null}
          <path d="M7.5 2h9A5.5 5.5 0 0 1 22 7.5v9a5.5 5.5 0 0 1-5.5 5.5h-9A5.5 5.5 0 0 1 2 16.5v-9A5.5 5.5 0 0 1 7.5 2Zm0 2A3.5 3.5 0 0 0 4 7.5v9A3.5 3.5 0 0 0 7.5 20h9a3.5 3.5 0 0 0 3.5-3.5v-9A3.5 3.5 0 0 0 16.5 4h-9Zm4.5 3a5 5 0 1 1 0 10 5 5 0 0 1 0-10Zm0 2a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z" />
          <circle cx="17.25" cy="6.75" r="1.25" />
        </svg>
      );
  }
}
