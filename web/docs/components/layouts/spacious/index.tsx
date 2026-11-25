import type { ReactNode } from 'react';
import { source } from '@/lib/source';
import { baseOptions, linkItems } from '@/components/layouts/shared';
import { ClientSpaciousLayout } from './client';

export function Spacious({ children }: { children: ReactNode }) {
  const base = baseOptions();

  return (
    <ClientSpaciousLayout
      {...base}
      tree={source.getPageTree()}
      links={linkItems.filter((item) => item.type === 'icon')}
    >
      {children}
    </ClientSpaciousLayout>
  );
}
