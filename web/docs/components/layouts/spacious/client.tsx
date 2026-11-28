'use client';

import { BookOpen, Code2 } from 'lucide-react';
import { DocsLayout, type DocsLayoutProps } from 'fumadocs-ui/layouts/spacious';

export function ClientSpaciousLayout(props: DocsLayoutProps) {
  return (
    <DocsLayout
      {...props}
      tabs={{
        transform: (option) => ({
          ...option,
          icon:
            option.title === 'Гайды' ? (
              <BookOpen className="size-4" />
            ) : option.title === 'SDK' ? (
              <Code2 className="size-4" />
            ) : (
              option.icon
            ),
        }),
      }}
    />
  );
}
