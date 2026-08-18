// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import mermaid from 'astro-mermaid';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';

// https://astro.build/config
export default defineConfig({
	markdown: {
		remarkPlugins: [remarkMath],
		rehypePlugins: [rehypeKatex],
	},
	site: 'https://fortemate.com',
	base: '/dicechess-engine',
	integrations: [
		mermaid(),
		starlight({
			title: 'Dice Chess Engine',
			customCss: ['katex/dist/katex.min.css'],
			social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/fortemate/dicechess-engine' }],
			sidebar: [
				{
					label: 'Getting Started',
					items: [
						{ label: 'Roadmap & Milestones', slug: 'architecture/milestones' },
					],
				},
				{
					label: 'Core Architecture',
					items: [
						{ label: 'Glossary', slug: 'architecture/glossary' },
						{ label: 'Domain Modeling', slug: 'architecture/domain-modeling' },
						{ label: 'Dice Chess FEN (DFEN)', slug: 'architecture/dice-chess-fen' },
						{ label: 'Board Symmetry & Canonicalization', slug: 'architecture/board-symmetry' },
						{
							label: 'Search & AI Bots',
							collapsed: true,
							items: [{ autogenerate: { directory: 'architecture/search' } }],
						},
						{
							label: 'Move Generation',
							collapsed: true,
							items: [{ autogenerate: { directory: 'architecture/move-generation' } }],
						},
					],
				},
				{
					label: 'Engineering Quality',
					items: [
						{ label: 'Testing Strategy & DSL', slug: 'architecture/testing' },
						{ label: 'CI/CD & Releases', slug: 'architecture/releases' },
						{ label: 'Dependency Updates', slug: 'architecture/dependency-updates' },
						{ label: 'Automated Code Reviews', slug: 'architecture/code-reviews' },
						{ label: 'Security Policy', slug: 'architecture/security' },
					],
				},
				{
					label: 'Infrastructure & Ops',
					items: [
						{ label: 'Oracle Cloud Hosting', slug: 'infrastructure/oracle-cloud' },
					],
				},
				{
					label: 'Developer Experience',
					items: [
						{ label: 'JavaScript API Reference', slug: 'architecture/javascript-api' },
						{ label: 'JVM API Reference', slug: 'architecture/jvm-api' },
						{ label: 'NPM Packaging & Local Integration', slug: 'guidelines/npm-packaging' },
						{ label: 'Maven Artifact & JVM Integration', slug: 'guidelines/maven-artifact' },
					],
				},
			],
		}),
	],
});
