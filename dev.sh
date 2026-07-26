#!/bin/bash
# Quick development workflow with JVM image (2-3 min builds)

echo "🚀 Starting development environment (JVM - fast builds)"
echo "⏱️  Build time: ~2-3 minutes"
echo ""

docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

echo ""
echo "✅ Development environment ready!"
echo "📊 Services:"
echo "   - Open WebUI:  http://localhost:3000"
echo "   - MCP Server:  http://localhost:7456"
echo "   - ZAP:         http://localhost:8090"
