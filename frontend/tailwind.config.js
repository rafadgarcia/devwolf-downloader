/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        bg: {
          dark: '#0D0B1E',
          indigo: '#14112E',
          light: '#F5F3FF',
        },
        surface: {
          dark: '#1E1940',
          elevated: '#262052',
          light: '#FFFFFF',
        },
        accent: {
          DEFAULT: '#7C5CFF',
          deep: '#5E43F3',
          hover: '#8F73FF',
          lavender: '#A78BFA',
          soft: '#C4B5FD',
        },
      },
      fontFamily: {
        sans: [
          'Inter',
          'system-ui',
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'Roboto',
          'sans-serif',
        ],
      },
    },
  },
  plugins: [],
};
