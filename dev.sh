#!/bin/bash
# Quick development workflow with JVM image (2-3 min builds)

echo "🚀 Starting development environment (JVM - fast builds)"
echo "⏱️  Build time: ~2-3 minutes"
echo ""

docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

echo ""
echo "✅ Development environment ready!"
echo "📊 Services:"
echo "   - MCP endpoint: http://localhost:7456/mcp"
echo "   - ZAP:         http://localhost:8090"
echo "Connect your MCP client using X-API-Key from MCP_API_KEY in .env."
echo "Client setup: https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/"
