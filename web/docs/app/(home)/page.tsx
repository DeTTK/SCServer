import Link from "fumadocs-core/link";
import { buttonVariants } from "fumadocs-ui/components/ui/button";
import { cn } from "@/lib/cn";

export default function HomePage() {
	return (
		<main className="relative flex flex-1 flex-col items-center justify-center overflow-hidden px-6 py-24 text-center">
			<div className="pointer-events-none absolute inset-0 -z-1 bg-[radial-gradient(circle_at_50%_20%,var(--color-fd-primary),transparent_55%)] opacity-10" />
			<h1 className="max-w-3xl font-mono text-4xl font-semibold md:text-6xl">
				Локальный сервер и клиент{" "}
				<span className="text-fd-primary">BSCPCRAFT</span>
			</h1>
			<p className="mt-6 max-w-xl text-fd-muted-foreground">
				Собственный сервер, клиентская обвязка и моды для BSCP. Инструкции по
				сборке, запуску, SDK и устройству мира собраны в одном месте.
			</p>

			<div className="mt-8 flex flex-wrap items-center justify-center gap-3">
				<Link
					href="/docs"
					className={cn(
						buttonVariants({ size: "sm", className: "h-11 px-6 text-base" }),
					)}
				>
					Открыть документацию
				</Link>
				<Link
					href="https://github.com/DeTTK/SCServer"
					className={cn(
						buttonVariants({
							variant: "secondary",
							size: "sm",
							className: "h-11 px-6 text-base",
						}),
					)}
				>
					GitHub
				</Link>
			</div>
		</main>
	);
}
