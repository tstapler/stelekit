// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// https://astro.build/config
export default defineConfig({
	site: 'https://stelekit.stapler.dev',
	base: '/',
	integrations: [
		starlight({
			title: 'SteleKit',
			description: 'A local-first outliner for Desktop and Android. Reads your Logseq markdown.',
			logo: {
				src: './src/assets/stelekit-mark.svg',
				alt: 'SteleKit',
			},
			favicon: '/favicon.svg',
			customCss: ['./src/styles/custom.css'],
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/tstapler/stelekit' },
			],
			sidebar: [
				{ label: 'Try in Browser →', link: '/demo/' },
				{
					label: 'User Guide',
					items: [
						{ label: 'Getting Started', slug: 'user/getting-started' },
						{ label: 'Outliner', slug: 'user/outliner' },
						{ label: 'Daily Notes', slug: 'user/daily-notes' },
						{ label: 'Block Editing', slug: 'user/block-editing' },
						{ label: 'Backlinks', slug: 'user/backlinks' },
						{ label: 'Search', slug: 'user/search' },
						{ label: 'Flashcards', slug: 'user/flashcards' },
						{ label: 'All Pages', slug: 'user/all-pages' },
						{ label: 'Unlinked References', slug: 'user/unlinked-references' },
					],
				},
				{
					label: 'Developer',
					items: [
						{ label: 'Architecture', slug: 'developer/architecture' },
						{ label: 'Build', slug: 'developer/build' },
						{ label: 'Contributing', slug: 'developer/contributing' },
						{ label: 'Module Structure', slug: 'developer/module-structure' },
					],
				},
			],
		}),
	],
});
