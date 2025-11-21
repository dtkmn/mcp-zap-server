#!/bin/bash
# Production deployment with native image (20+ min builds, 0.6s startup)

echo "🏭 Building production environment (Native - slow builds, fast startup)"
echo "⏱️  Build time: ~20-25 minutes"
echo "⚡ Startup: ~0.6 seconds"
echo ""
echo "⚠️  This will take a while. Go grab a coffee ☕"
echo ""

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

echo ""
echo "✅ Production environment ready!"
echo "📊 Services:"
echo "   - Open WebUI:  http://localhost:3000"
echo "   - MCP Server:  http://localhost:7456"
echo "   - ZAP:         http://localhost:8090"
echo ""
echo "💡 Performance: Native startup in ~0.6s vs JVM ~3-5s"
