'use client';

import Link from 'next/link';
import { type ComponentProps, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function Body({ children }: { children: ReactNode }): React.ReactElement {
	return (
		<body className={cn('relative flex min-h-screen flex-col')}>
			{children}
		</body>
	);
}

export function Logo() {
	return (
		<img
			src="/logo.svg"
			alt="BSCPCRAFT"
			className="h-4 w-auto invert dark:invert-0"
		/>
	);
}

/** the nav's title, never prefetched: every page shows it, and the landing page is a heavy one */
export function NavTitle({ href = '/', ...props }: ComponentProps<'a'>) {
	return (
		<Link href={href} prefetch={false} {...props}>
			<Logo />
			<span className="font-medium in-[.uwu]:hidden">BSCPCRAFT</span>
		</Link>
	);
}
