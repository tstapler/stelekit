// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// https://astro.build/config
export default defineConfig({
	site: 'https://tstapler.github.io',
	base: '/stelekit',
	integrations: [
		starlight({
			title: 'SteleKit',
			description: 'A local-first outliner for Desktop and Android. Reads your Logseq markdown.',
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/tstapler/stelekit' },
			],
			sidebar: [
				{
					label: 'User Guide',
					items: [
						{ label: 'Getting Started', slug: 'user/getting-started' },
						{ label: 'Outliner', slug: 'user/outliner' },
						{ label: 'Journals', slug: 'user/journals' },
						{ label: 'Backlinks', slug: 'user/backlinks' },
						{ label: 'Search', slug: 'user/search' },
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
