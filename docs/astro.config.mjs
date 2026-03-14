// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
	site: 'https://dtkmn.github.io',
	base: '/mcp-zap-server',
	trailingSlash: 'always',
	integrations: [
		starlight({
			title: 'MCP ZAP Server',
			description: 'Enterprise-grade security testing for AI agents and operators using OWASP ZAP over MCP.',
			tagline: 'Initialising MCP ZAP Server Protocol...',
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/dtkmn/mcp-zap-server' },
			],
			customCss: [
				'@fontsource/ibm-plex-mono/400.css',
				'@fontsource/ibm-plex-mono/500.css',
				'@fontsource/rajdhani/500.css',
				'@fontsource/rajdhani/700.css',
				'/src/styles/mr-robot.css',
			],
			credits: false,
			components: {
				Header: './src/components/CinematicHeader.astro',
				Footer: './src/components/CinematicFooter.astro',
				PageTitle: './src/components/FsPageTitle.astro',
				SiteTitle: './src/components/FsSiteTitle.astro',
			},
			sidebar: [
				{
					label: 'Mission Control',
					items: [
						{ label: 'root@localhost:~', link: '/' },
						{ slug: 'getting-started/authentication-quick-start' },
						{ slug: 'getting-started/mcp-client-authentication' },
						{ slug: 'getting-started/jwt-quick-start' },
					],
				},
				{
					label: 'Scanning',
					items: [
						{ slug: 'scanning/ajax-spider' },
						{ slug: 'scanning/authenticated-scanning-best-practices' },
					],
				},
				{
					label: 'Security Modes',
					items: [
						{ slug: 'security-modes' },
						{ slug: 'security-modes/jwt-authentication' },
						{ slug: 'security-modes/examples' },
						{ slug: 'security-modes/jwt-key-rotation-runbook' },
						{ slug: 'security-modes/implementation-summary' },
						{ slug: 'security-modes/jwt-implementation-summary' },
					],
				},
				{
					label: 'Operations',
					items: [
						{ slug: 'operations/production-checklist' },
						{ slug: 'operations/local-ha-compose' },
						{ slug: 'operations/queue-coordinator-leader-election' },
						{ slug: 'operations/scan-queue-retry-policy' },
						{ slug: 'operations/native-image-performance' },
					],
				},
				{
					label: 'Reference',
					collapsed: true,
					items: [{ slug: 'reference/security-policy' }],
				},
				{
					label: 'Project Links',
					collapsed: true,
					items: [
						{
							label: 'GitHub Repository',
							link: 'https://github.com/dtkmn/mcp-zap-server',
						},
						{
							label: 'Releases',
							link: 'https://github.com/dtkmn/mcp-zap-server/releases',
						},
					],
				},
			],
		}),
	],
});
