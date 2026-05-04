{{/*
Expand the name of the chart.
*/}}
{{- define "mcp-zap-server.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "mcp-zap-server.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "mcp-zap-server.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "mcp-zap-server.labels" -}}
helm.sh/chart: {{ include "mcp-zap-server.chart" . }}
{{ include "mcp-zap-server.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "mcp-zap-server.selectorLabels" -}}
app.kubernetes.io/name: {{ include "mcp-zap-server.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
ZAP labels
*/}}
{{- define "mcp-zap-server.zap.labels" -}}
helm.sh/chart: {{ include "mcp-zap-server.chart" . }}
{{ include "mcp-zap-server.zap.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/component: zap-proxy
{{- end }}

{{/*
ZAP selector labels
*/}}
{{- define "mcp-zap-server.zap.selectorLabels" -}}
app.kubernetes.io/name: zap-proxy
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
MCP labels
*/}}
{{- define "mcp-zap-server.mcp.labels" -}}
helm.sh/chart: {{ include "mcp-zap-server.chart" . }}
{{ include "mcp-zap-server.mcp.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/component: mcp-server
{{- end }}

{{/*
MCP selector labels
*/}}
{{- define "mcp-zap-server.mcp.selectorLabels" -}}
app.kubernetes.io/name: mcp-server
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "mcp-zap-server.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "mcp-zap-server.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
MCP service annotations with optional OSS streamable MCP affinity presets.
User-provided annotations win over generated defaults.
*/}}
{{- define "mcp-zap-server.mcp.serviceAnnotations" -}}
{{- $annotations := dict -}}
{{- $sessionAffinity := .Values.mcp.streamableHttp.sessionAffinity -}}
{{- $sessionAffinityProvider := trimAll " " (default "" $sessionAffinity.provider) -}}
{{- if and $sessionAffinity.enabled (eq $sessionAffinityProvider "aws-nlb") -}}
{{- $_ := set $annotations "service.beta.kubernetes.io/aws-load-balancer-type" (default "nlb" $sessionAffinity.awsLoadBalancer.type) -}}
{{- with $sessionAffinity.awsLoadBalancer.scheme }}
{{- $_ := set $annotations "service.beta.kubernetes.io/aws-load-balancer-scheme" . -}}
{{- end -}}
{{- $_ := set $annotations "service.beta.kubernetes.io/aws-load-balancer-target-group-attributes" (default "stickiness.enabled=true,stickiness.type=source_ip" $sessionAffinity.awsLoadBalancer.targetGroupAttributes) -}}
{{- end -}}
{{- range $key, $value := .Values.mcp.service.annotations }}
{{- $_ := set $annotations $key $value -}}
{{- end -}}
{{- if gt (len $annotations) 0 -}}
{{ toYaml $annotations }}
{{- end -}}
{{- end }}

{{/*
Validate security-sensitive Helm values before rendering resources.
*/}}
{{- define "mcp-zap-server.validate" -}}
{{- $securityMode := lower (default "api-key" .Values.mcp.security.mode) -}}
{{- $mcpSecurityEnabled := .Values.mcp.security.enabled -}}
{{- $zapSecretName := trimAll " " (default "" .Values.zap.config.existingSecret.name) -}}
{{- $mcpSecretName := trimAll " " (default "" .Values.mcp.security.existingSecret.name) -}}
{{- $mcpZapSecretName := trimAll " " (default "" .Values.mcp.zapClient.existingSecret.name) -}}
{{- $jwtSecretKeyRef := trimAll " " (default "" .Values.mcp.security.existingSecret.jwtSecretKey) -}}
{{- $zapApiKey := trimAll " " (default "" .Values.zap.config.apiKey) -}}
{{- $mcpApiKey := trimAll " " (default "" .Values.mcp.security.apiKey) -}}
{{- $mcpZapApiKey := trimAll " " (default "" .Values.mcp.zapClient.apiKey) -}}
{{- $jwtEnabled := .Values.mcp.security.jwt.enabled -}}
{{- $jwtSecret := trimAll " " (default "" .Values.mcp.security.jwt.secret) -}}
{{- $effectiveZapClientApiKey := $mcpZapApiKey -}}
{{- $sessionAffinity := .Values.mcp.streamableHttp.sessionAffinity -}}
{{- $sessionAffinityProvider := trimAll " " (default "" $sessionAffinity.provider) -}}
{{- $multiReplicaMcp := or (gt (int .Values.mcp.replicaCount) 1) (and .Values.mcp.autoscaling.enabled (gt (int .Values.mcp.autoscaling.minReplicas) 1)) -}}
{{- if eq $effectiveZapClientApiKey "" -}}
{{- $effectiveZapClientApiKey = $zapApiKey -}}
{{- end -}}

{{- if and .Values.mcp.enabled $multiReplicaMcp (not $sessionAffinity.enabled) -}}
{{- fail "multi-replica streamable MCP requires mcp.streamableHttp.sessionAffinity.enabled=true; keep mcp.replicaCount/autoscaling.minReplicas at 1 or choose a supported affinity provider" -}}
{{- end -}}
{{- if and .Values.mcp.enabled $multiReplicaMcp $sessionAffinity.enabled (not (or (eq $sessionAffinityProvider "aws-nlb") (eq $sessionAffinityProvider "ingress-nginx") (eq $sessionAffinityProvider "service-client-ip"))) -}}
{{- fail "mcp.streamableHttp.sessionAffinity.provider must be one of: aws-nlb, ingress-nginx, service-client-ip" -}}
{{- end -}}

{{- if and (eq $zapSecretName "") (eq $zapApiKey "") -}}
{{- fail "zap.config.apiKey is required when zap.config.existingSecret.name is not set" -}}
{{- end -}}
{{- if and (eq $zapSecretName "") (contains "changeme" $zapApiKey) -}}
{{- fail "zap.config.apiKey must not use a placeholder value; set a real key or use zap.config.existingSecret" -}}
{{- end -}}

{{- if and $mcpSecurityEnabled (ne $securityMode "none") (eq $mcpSecretName "") (eq $mcpApiKey "") -}}
{{- fail "mcp.security.apiKey is required when mcp.security.existingSecret.name is not set and MCP security is enabled" -}}
{{- end -}}
{{- if and $mcpSecurityEnabled (ne $securityMode "none") (eq $mcpSecretName "") (not .Values.mcp.security.allowPlaceholderApiKey) (contains "changeme" $mcpApiKey) -}}
{{- fail "mcp.security.apiKey must not use a placeholder value when mcp.security.allowPlaceholderApiKey=false" -}}
{{- end -}}

{{- if and (eq $mcpZapSecretName "") (eq $effectiveZapClientApiKey "") -}}
{{- fail "mcp.zapClient.apiKey is required when mcp.zapClient.existingSecret.name is not set and zap.config.apiKey is blank" -}}
{{- end -}}
{{- if and (eq $mcpZapSecretName "") (contains "changeme" $effectiveZapClientApiKey) -}}
{{- fail "mcp.zapClient.apiKey must not use a placeholder value; set a real key or use mcp.zapClient.existingSecret" -}}
{{- end -}}

{{- if and (eq $securityMode "jwt") (not $jwtEnabled) -}}
{{- fail "mcp.security.jwt.enabled must be true when mcp.security.mode=jwt" -}}
{{- end -}}
{{- if $jwtEnabled -}}
{{- if and (or (eq $mcpSecretName "") (eq $jwtSecretKeyRef "")) (eq $jwtSecret "") -}}
{{- fail "mcp.security.jwt.secret is required when mcp.security.jwt.enabled=true and no existing JWT secret is configured" -}}
{{- end -}}
{{- if and (or (eq $mcpSecretName "") (eq $jwtSecretKeyRef "")) (contains "changeme" $jwtSecret) -}}
{{- fail "mcp.security.jwt.secret must not use a placeholder value" -}}
{{- end -}}
{{- if and (or (eq $mcpSecretName "") (eq $jwtSecretKeyRef "")) (lt (len $jwtSecret) 32) -}}
{{- fail "mcp.security.jwt.secret must be at least 32 characters long" -}}
{{- end -}}
{{- end -}}
{{- end }}

{{/*
MCP ingress annotations with optional OSS streamable MCP affinity presets.
User-provided annotations win over generated defaults.
*/}}
{{- define "mcp-zap-server.mcp.ingressAnnotations" -}}
{{- $annotations := dict -}}
{{- $sessionAffinity := .Values.mcp.streamableHttp.sessionAffinity -}}
{{- $sessionAffinityProvider := trimAll " " (default "" $sessionAffinity.provider) -}}
{{- if and $sessionAffinity.enabled (eq $sessionAffinityProvider "ingress-nginx") -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/upstream-hash-by" (default "$http_mcp_session_id$remote_addr$http_user_agent" $sessionAffinity.ingressNginx.upstreamHashBy) -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/proxy-read-timeout" (default "3600" $sessionAffinity.ingressNginx.proxyReadTimeout) -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/proxy-send-timeout" (default "3600" $sessionAffinity.ingressNginx.proxySendTimeout) -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/proxy-buffering" (default "off" $sessionAffinity.ingressNginx.proxyBuffering) -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/proxy-request-buffering" (default "off" $sessionAffinity.ingressNginx.proxyRequestBuffering) -}}
{{- end -}}
{{- range $key, $value := .Values.mcp.ingress.annotations }}
{{- $_ := set $annotations $key $value -}}
{{- end -}}
{{- if gt (len $annotations) 0 -}}
{{ toYaml $annotations }}
{{- end -}}
{{- end }}
