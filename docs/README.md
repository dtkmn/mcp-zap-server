# MCP ZAP Server Documentation

This directory contains the source files for the [GitHub Pages documentation site](https://dtkmn.github.io/mcp-zap-server/).

## Structure

- `index.md` - Main landing page
- `_config.yml` - Jekyll configuration
- `*.md` - Documentation pages (auto-linked)
- `demo.html` - Demo video page

## Documentation Pages

### Security & Authentication
- `SECURITY_MODES.md` - Overview of three security modes
- `JWT_AUTHENTICATION.md` - Complete JWT setup guide
- `QUICK_START_JWT.md` - Fast JWT setup
- `MCP_CLIENT_AUTHENTICATION.md` - Client configuration
- `AUTHENTICATION_QUICK_START.md` - Quick auth setup
- `SECURITY_MODE_EXAMPLES.md` - Real-world examples

### Features
- `AJAX_SPIDER.md` - WAF bypass using browser-based scanning

### Implementation Details
- `JWT_IMPLEMENTATION_SUMMARY.md` - JWT technical details
- `SECURITY_MODES_IMPLEMENTATION_SUMMARY.md` - Security implementation

## Local Development

### Preview site locally:

```bash
# Install Jekyll
gem install bundler jekyll

# Create Gemfile (first time only)
cd docs
cat > Gemfile << EOF
source "https://rubygems.org"
gem "github-pages", group: :jekyll_plugins
gem "webrick"
EOF

# Install dependencies
bundle install

# Serve locally
bundle exec jekyll serve

# View at http://localhost:4000/mcp-zap-server/
```

## GitHub Pages Setup

1. Go to **Repository Settings → Pages**
2. Source: **GitHub Actions**
3. Site will auto-deploy on push to main/master

Site URL: `https://dtkmn.github.io/mcp-zap-server/`

## Adding New Pages

1. Create `new-page.md` in `/docs`
2. Add frontmatter:
   ```yaml
   ---
   layout: default
   title: Page Title
   ---
   ```
3. Link from `index.md`
4. Commit and push - auto-deploys!

## Theme

Using **Cayman** theme. To change:
- Edit `theme:` in `_config.yml`
- [Available themes](https://pages.github.com/themes/)

## Notes

- Markdown files automatically converted to HTML
- Demo video available at `/demo.html`
- Jekyll ignores files starting with `_`
- Build output in `_site/` (gitignored)
