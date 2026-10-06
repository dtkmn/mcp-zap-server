#!/usr/bin/env bash
# Run as root. Scope all rules to this example's dedicated Docker bridge.
set -euo pipefail

if [[ $(id -u) != 0 ]]; then
  echo "Run this script as root." >&2
  exit 1
fi

# Create DOCKER-USER before Docker starts, then reattach after each Docker restart.
iptables -w -N DOCKER-USER 2>/dev/null || iptables -w -L DOCKER-USER -n >/dev/null

# --noflush preserves other chains. Declaring only our two user chains replaces
# their rules in one commit, avoiding a temporarily empty chain during reruns.
# Permit responses to host-initiated connections, including private SSM forwarding.
iptables-restore --wait --noflush <<'RULES'
*filter
:MCPZAP-HOST - [0:0]
:MCPZAP-FWD - [0:0]
-A MCPZAP-HOST -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
-A MCPZAP-HOST -j DROP
-A MCPZAP-FWD -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
-A MCPZAP-FWD -i br-mcpzap -o br-mcpzap -j RETURN
-A MCPZAP-FWD -i br-mcpzap -j DROP
-A MCPZAP-FWD -j RETURN
COMMIT
RULES

iptables -w -C INPUT -i br-mcpzap -j MCPZAP-HOST 2>/dev/null ||
  iptables -w -I INPUT 1 -i br-mcpzap -j MCPZAP-HOST
iptables -w -C DOCKER-USER -j MCPZAP-FWD 2>/dev/null ||
  iptables -w -I DOCKER-USER 1 -j MCPZAP-FWD
