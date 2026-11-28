import { Inter, JetBrains_Mono } from 'next/font/google';
import type { Metadata, Viewport } from 'next';
import { Body } from '@/app/layout.client';
import { Provider } from '@/components/provider';
import { TreeContextProvider } from 'fumadocs-ui/contexts/tree';
import { source } from '@/lib/source';
import './global.css';

const sans = Inter({
  variable: '--font-sans',
  subsets: ['latin', 'cyrillic'],
});

const mono = JetBrains_Mono({
  variable: '--font-mono',
  subsets: ['latin', 'cyrillic'],
});

export const metadata: Metadata = {
  title: {
    template: '%s | BSCPCRAFT',
    default: 'BSCPCRAFT',
  },
  description: 'Документация BSCPCRAFT / Бскпкрафт',
  metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL ?? 'http://localhost:3000'),
};

export const viewport: Viewport = {
  themeColor: [
    { media: '(prefers-color-scheme: dark)', color: '#0A0A0A' },
    { media: '(prefers-color-scheme: light)', color: '#fff' },
  ],
};

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="ru" className={`${sans.variable} ${mono.variable}`} suppressHydrationWarning>
      <Body>
        <Provider>
          <TreeContextProvider tree={source.getPageTree()}>{children}</TreeContextProvider>
        </Provider>
      </Body>
    </html>
  );
}
