import {
	DocsPage,
	MarkdownCopyButton,
	ViewOptionsPopover,
} from "fumadocs-ui/layouts/spacious/page";
import { createRelativeLink } from "fumadocs-ui/mdx";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getMDXComponents } from "@/components/mdx";
import { getPageImageUrl, gitConfig } from "@/lib/shared";
import { source } from "@/lib/source";

export const revalidate = false;

export default async function Page(props: PageProps<"/docs/[[...slug]]">) {
	const params = await props.params;
	const page = source.getPage(params.slug);
	if (!page) notFound();

	const MDX = page.data.body;
	const markdownUrl = `/llms.mdx${page.url}.md`;

	return (
		<DocsPage toc={page.data.toc}>
			<h1 className="text-[1.75em] font-semibold">{page.data.title}</h1>
			{page.data.description && (
				<p className="text-lg text-fd-muted-foreground mb-2">
					{page.data.description}
				</p>
			)}
			<div className="flex flex-row flex-wrap gap-2 items-center border-b pb-6 mb-4">
				<MarkdownCopyButton markdownUrl={markdownUrl} />
				<ViewOptionsPopover
					markdownUrl={markdownUrl}
					githubUrl={`https://github.com/${gitConfig.user}/${gitConfig.repo}/blob/${gitConfig.branch}/docs/${encodeURI(page.path)}`}
				/>
			</div>
			<div className="prose flex-1 text-fd-foreground/90">
				<MDX
					components={getMDXComponents({
						a: createRelativeLink(source, page),
					})}
				/>
			</div>
		</DocsPage>
	);
}

export async function generateStaticParams() {
	return source.generateParams();
}

export async function generateMetadata(
	props: PageProps<"/docs/[[...slug]]">,
): Promise<Metadata> {
	const params = await props.params;
	const page = source.getPage(params.slug);
	if (!page) notFound();

	return {
		title: page.data.title,
		description: page.data.description,
		openGraph: {
			images: getPageImageUrl(page).url,
		},
	};
}
