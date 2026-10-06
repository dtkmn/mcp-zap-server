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
Render the ZAP image with an optional immutable digest.
*/}}
{{- define "mcp-zap-server.zap.image" -}}
{{- $digest := default "" .Values.zap.image.digest | toString -}}
{{- if $digest -}}
{{- if not (regexMatch "^sha256:[0-9a-f]{64}$" $digest) -}}
{{- fail "zap.image.digest must be sha256: followed by exactly 64 lowercase hexadecimal characters" -}}
{{- end -}}
{{- printf "%s@%s" .Values.zap.image.repository $digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.zap.image.repository .Values.zap.image.tag -}}
{{- end -}}
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
Keep both RWO workspace users on one node, including engine replacement.
Self-affinity lets the first workspace pod bootstrap without a peer dependency.
*/}}
{{- define "mcp-zap-server.workspaceAffinity" -}}
{{- $affinity := deepCopy (default dict .affinity) -}}
{{- $persistence := .root.Values.zap.persistence -}}
{{- if and $persistence.enabled $persistence.shareWithMcp (eq $persistence.accessMode "ReadWriteOnce") -}}
{{- $podAffinity := default dict $affinity.podAffinity -}}
{{- $required := default list $podAffinity.requiredDuringSchedulingIgnoredDuringExecution -}}
{{- $labels := dict "mcp-zap-server.io/workspace" (include "mcp-zap-server.fullname" .root) -}}
{{- $sameNode := dict "labelSelector" (dict "matchLabels" $labels) "topologyKey" "kubernetes.io/hostname" -}}
{{- $_ := set $podAffinity "requiredDuringSchedulingIgnoredDuringExecution" (append $required $sameNode) -}}
{{- $_ := set $affinity "podAffinity" $podAffinity -}}
{{- end -}}
{{- with $affinity -}}
{{ toYaml . }}
{{- end -}}
{{- end }}

{{/*
Prepare PVC subdirectories without granting ZAP write access to import staging.
The volume root is provisioned by fsGroup; application directories stay owned
by the shared nonroot UID. OnRootMismatch preserves their restrictive modes.
*/}}
{{- define "mcp-zap-server.workspaceInitialization" -}}
{{- $persistence := .Values.zap.persistence -}}
{{- if or $persistence.workspaceSubPath $persistence.contentImport.enabled -}}
initContainers:
- name: initialize-shared-directories
  image: {{ include "mcp-zap-server.zap.image" . | quote }}
  imagePullPolicy: {{ .Values.zap.image.pullPolicy }}
  command:
  - /bin/sh
  - -ec
  - |
    umask 027
    if [ -L /shared ]; then
      echo "Shared volume mount must not be a symlink" >&2
      exit 1
    fi
    workspace=/shared/{{ $persistence.workspaceSubPath }}
    if [ -L "$workspace" ]; then
      echo "Shared workspace must not be a symlink" >&2
      exit 1
    fi
    mkdir -p "$workspace"
    if [ ! -d "$workspace" ] || [ -L "$workspace" ]; then
      echo "Shared workspace must be a directory without symlinks" >&2
      exit 1
    fi
    {{- if $persistence.contentImport.enabled }}
    imports=/shared/{{ $persistence.contentImport.subPath }}
    if [ -L "$imports" ]; then
      echo "Content import directory must not be a symlink" >&2
      exit 1
    fi
    mkdir -p "$imports"
    if [ ! -d "$imports" ] || [ -L "$imports" ] || [ "$(stat -c '%u:%g' "$imports")" != "1000:1000" ]; then
      echo "Content import directory must be owned by UID/GID 1000" >&2
      exit 1
    fi
    chmod u=rwx,g=rx,o=,u-s,g-s "$imports"
    {{- end }}
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    runAsGroup: 1000
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop: [ALL]
    seccompProfile:
      type: RuntimeDefault
  volumeMounts:
  - name: zap-data
    mountPath: /shared
{{- end -}}
{{- end }}

{{/*
Recognize chart-owned variables and their direct Spring property aliases.
*/}}
{{- define "mcp-zap-server.isSecurityProperty" -}}
{{- $name := regexReplaceAll "[^a-z0-9]" (lower .) "" -}}
{{- if or (hasPrefix "jwtrevocationstore" $name) (hasPrefix "mcpserverauthjwt" $name) (has $name (list "mcpsecuritymode" "mcpsecurityenabled" "mcpapikey" "mcpsecurityallowplaceholderapikey" "mcpserversecuritymode" "mcpserversecurityenabled" "mcpserversecurityallowplaceholderapikey" "jwtenabled" "jwtsecret" "jwtissuer" "jwtaccesstokenexpiration" "jwtrefreshtokenexpiration")) -}}
true
{{- end -}}
{{- end }}

{{/*
Recognize chart-owned content import variables and their Spring property aliases.
*/}}
{{- define "mcp-zap-server.isContentImportProperty" -}}
{{- $name := regexReplaceAll "[^a-z0-9]" (lower .) "" -}}
{{- if has $name (list "zapopenapicontentenabled" "zapopenapicontentlocaldirectory" "zapopenapicontentzapdirectory" "zapopenapicontentimportenabled" "zapopenapicontentimportlocaldirectory" "zapopenapicontentimportzapdirectory") -}}
true
{{- end -}}
{{- end }}

{{/*
Reject explicit Spring JSON overrides of chart-owned security controls.
Other JSON configuration, including bootstrap authentication profiles, remains supported.
*/}}
{{- define "mcp-zap-server.validateSecurityJson" -}}
{{- range $key, $value := .values -}}
{{- $property := printf "%s%s" $.prefix $key -}}
{{- $normalized := regexReplaceAll "[^a-z0-9]" (lower $property) "" -}}
{{- if eq (include "mcp-zap-server.isSecurityProperty" $property) "true" -}}
{{- fail (printf "SPRING_APPLICATION_JSON must not override %s; configure chart-owned security through mcp.security" $property) -}}
{{- end -}}
{{- if and $.sharedWorkspace (has $normalized (list "zapreportdirectory" "zapautomationlocaldirectory" "zapautomationzapdirectory")) -}}
{{- fail (printf "SPRING_APPLICATION_JSON must not override shared workspace property %s; use zap.persistence.mountPath and zap.persistence.automationSubdirectory" $property) -}}
{{- end -}}
{{- if and $.contentImport (eq (include "mcp-zap-server.isContentImportProperty" $property) "true") -}}
{{- fail (printf "SPRING_APPLICATION_JSON must not override content import property %s; use zap.persistence.contentImport" $property) -}}
{{- end -}}
{{- if kindIs "map" $value -}}
{{- include "mcp-zap-server.validateSecurityJson" (dict "values" $value "prefix" (printf "%s." $property) "sharedWorkspace" $.sharedWorkspace "contentImport" $.contentImport) -}}
{{- end -}}
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
{{- $multiReplicaMcp := ternary (gt (int .Values.mcp.autoscaling.maxReplicas) 1) (gt (int .Values.mcp.replicaCount) 1) .Values.mcp.autoscaling.enabled -}}
{{- $revocation := .Values.mcp.security.jwt.revocation -}}
{{- $revocationBackend := lower (trim $revocation.backend) -}}
{{- $sharedWorkspace := and .Values.zap.persistence.enabled .Values.zap.persistence.shareWithMcp -}}
{{- $contentImport := .Values.zap.persistence.contentImport -}}
{{- $workspaceSubPath := .Values.zap.persistence.workspaceSubPath -}}
{{- if not (kindIs "bool" $contentImport.enabled) -}}
{{- fail "zap.persistence.contentImport.enabled must be a boolean" -}}
{{- end -}}
{{- if hasKey .Values.podLabels "mcp-zap-server.io/workspace" -}}
{{- fail "podLabels must not override the chart-owned mcp-zap-server.io/workspace label" -}}
{{- end -}}
{{- if eq $effectiveZapClientApiKey "" -}}
{{- $effectiveZapClientApiKey = $zapApiKey -}}
{{- end -}}
{{- if $contentImport.enabled -}}
{{- if or .Values.mcp.autoscaling.enabled (ne (int .Values.mcp.replicaCount) 1) -}}
{{- fail "content import staging supports one MCP writer; set mcp.replicaCount=1 and mcp.autoscaling.enabled=false" -}}
{{- end -}}
{{- if ne .Values.mcp.deploymentStrategy.type "Recreate" -}}
{{- fail "content import staging requires mcp.deploymentStrategy.type=Recreate so MCP writers do not overlap during rollout" -}}
{{- end -}}
{{- end -}}

{{- if and .Values.mcp.enabled $multiReplicaMcp (not $sessionAffinity.enabled) -}}
{{- fail "multi-replica streamable MCP requires mcp.streamableHttp.sessionAffinity.enabled=true; keep mcp.replicaCount/autoscaling.maxReplicas at 1 or choose a supported affinity provider" -}}
{{- end -}}
{{- if and .Values.mcp.enabled $multiReplicaMcp $sessionAffinity.enabled (not (or (eq $sessionAffinityProvider "aws-nlb") (eq $sessionAffinityProvider "ingress-nginx") (eq $sessionAffinityProvider "service-client-ip"))) -}}
{{- fail "mcp.streamableHttp.sessionAffinity.provider must be one of: aws-nlb, ingress-nginx, service-client-ip" -}}
{{- end -}}

{{- if and .Values.mcp.enabled .Values.mcp.autoscaling.enabled (or (lt (int .Values.mcp.autoscaling.minReplicas) 1) (lt (int .Values.mcp.autoscaling.maxReplicas) (int .Values.mcp.autoscaling.minReplicas))) -}}
{{- fail "MCP autoscaling requires 1 <= minReplicas <= maxReplicas" -}}
{{- end -}}
{{- if and .Values.zap.enabled (ne (int .Values.zap.replicaCount) 1) -}}
{{- fail "the chart supports one stateful ZAP replica; zap.replicaCount must be 1" -}}
{{- end -}}
{{- if not (kindIs "string" $workspaceSubPath) -}}
{{- fail "zap.persistence.workspaceSubPath must be empty or a single portable directory name" -}}
{{- end -}}
{{- if and $workspaceSubPath (not (regexMatch "^[A-Za-z0-9_-]+$" $workspaceSubPath)) -}}
{{- fail "zap.persistence.workspaceSubPath must be empty or a single portable directory name" -}}
{{- end -}}
{{- if or $contentImport.enabled $workspaceSubPath -}}
{{- if not .Values.zap.persistence.enabled -}}
{{- fail "PVC subdirectories require zap.persistence.enabled=true" -}}
{{- end -}}
{{- if or (ne (toString .Values.podSecurityContext.runAsUser) "1000") (ne (toString .Values.podSecurityContext.runAsGroup) "1000") (ne (toString .Values.podSecurityContext.fsGroup) "1000") (ne .Values.podSecurityContext.fsGroupChangePolicy "OnRootMismatch") (ne (toString .Values.securityContext.runAsUser) "1000") (ne (toString .Values.securityContext.runAsGroup) "1000") -}}
{{- fail "PVC subdirectories require pod and container UID/GID 1000, fsGroup=1000, and fsGroupChangePolicy=OnRootMismatch" -}}
{{- end -}}
{{- end -}}
{{- if $contentImport.enabled -}}
{{- if not (and .Values.mcp.enabled .Values.zap.enabled $sharedWorkspace) -}}
{{- fail "content import requires chart-managed MCP and ZAP with zap.persistence.enabled=true and shareWithMcp=true" -}}
{{- end -}}
{{- if not $workspaceSubPath -}}
{{- fail "content import requires zap.persistence.workspaceSubPath (e.g. workspace); migrate existing PVC-root reports and automation files before changing layout" -}}
{{- end -}}
{{- if not (kindIs "string" $contentImport.subPath) -}}
{{- fail "zap.persistence.contentImport.subPath must be a single portable directory name" -}}
{{- end -}}
{{- if not (regexMatch "^[A-Za-z0-9_-]+$" $contentImport.subPath) -}}
{{- fail "zap.persistence.contentImport.subPath must be a single portable directory name" -}}
{{- end -}}
{{- if eq $workspaceSubPath $contentImport.subPath -}}
{{- fail "content import staging and the report workspace must use separate sibling PVC subPaths" -}}
{{- end -}}
{{- $importMount := $contentImport.mountPath -}}
{{- if not (kindIs "string" $importMount) -}}
{{- fail "zap.persistence.contentImport.mountPath must be an absolute non-root portable directory without dot segments" -}}
{{- end -}}
{{- if or (not (regexMatch "^/[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$" $importMount)) (regexMatch "(^|/)[.][.]?(/|$)" $importMount) -}}
{{- fail "zap.persistence.contentImport.mountPath must be an absolute non-root portable directory without dot segments" -}}
{{- end -}}
{{- $workspaceMount := trimSuffix "/" .Values.zap.persistence.mountPath -}}
{{- if or (eq $importMount $workspaceMount) (hasPrefix (printf "%s/" $workspaceMount) $importMount) (hasPrefix (printf "%s/" $importMount) $workspaceMount) -}}
{{- fail "content import mountPath must be separate from the report and automation workspace mountPath" -}}
{{- end -}}
{{- end -}}
{{- if and .Values.mcp.enabled $sharedWorkspace -}}
{{- if not .Values.zap.enabled -}}
{{- fail "shared workspace requires chart-managed ZAP; set zap.persistence.shareWithMcp=false for an external engine" -}}
{{- end -}}
{{- if not (has .Values.zap.persistence.accessMode (list "ReadWriteOnce" "ReadWriteMany")) -}}
{{- fail "shared MCP/ZAP storage requires ReadWriteOnce or ReadWriteMany; ReadWriteOncePod cannot be shared by two pods" -}}
{{- end -}}
{{- if and $multiReplicaMcp (ne .Values.zap.persistence.accessMode "ReadWriteMany") -}}
{{- fail "multi-replica MCP with a shared workspace requires ReadWriteMany storage" -}}
{{- end -}}
{{- $mountPath := .Values.zap.persistence.mountPath -}}
{{- if or (not (hasPrefix "/" $mountPath)) (eq $mountPath "/") (regexMatch "(^|/)[.][.]?(/|$)" $mountPath) -}}
{{- fail "zap.persistence.mountPath must be an absolute non-root directory without dot segments" -}}
{{- end -}}
{{- $automationSubdirectory := .Values.zap.persistence.automationSubdirectory -}}
{{- if not (kindIs "string" $automationSubdirectory) -}}
{{- fail "zap.persistence.automationSubdirectory must be a nonempty portable relative directory without dot segments" -}}
{{- end -}}
{{- if or (not (regexMatch "^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$" $automationSubdirectory)) (regexMatch "(^|/)[.][.]?(/|$)" $automationSubdirectory) -}}
{{- fail "zap.persistence.automationSubdirectory must be a nonempty portable relative directory without dot segments" -}}
{{- end -}}
{{- end -}}

{{- if .Values.mcp.enabled -}}
{{- range .Values.mcp.env -}}
{{- $name := regexReplaceAll "[^a-z0-9]" (lower .name) "" -}}
{{- if eq (include "mcp-zap-server.isSecurityProperty" .name) "true" -}}
{{- fail (printf "mcp.env must not override chart-owned security variable %s; use mcp.security" .name) -}}
{{- end -}}
{{- if and $sharedWorkspace (has $name (list "zapreportdirectory" "zapautomationlocaldirectory" "zapautomationzapdirectory")) -}}
{{- fail (printf "mcp.env must not override shared workspace variable %s; use zap.persistence.mountPath and zap.persistence.automationSubdirectory" .name) -}}
{{- end -}}
{{- if and $contentImport.enabled (eq (include "mcp-zap-server.isContentImportProperty" .name) "true") -}}
{{- fail (printf "mcp.env must not override content import variable %s; use zap.persistence.contentImport" .name) -}}
{{- end -}}
{{- if eq $name "springapplicationjson" -}}
{{- if not (hasKey . "value") -}}
{{- fail "SPRING_APPLICATION_JSON in mcp.env requires a literal JSON object so chart-owned security can be validated" -}}
{{- end -}}
{{- $json := mustFromJson .value -}}
{{- if not (kindIs "map" $json) -}}
{{- fail "SPRING_APPLICATION_JSON must be a JSON object" -}}
{{- end -}}
{{- include "mcp-zap-server.validateSecurityJson" (dict "values" $json "prefix" "" "sharedWorkspace" $sharedWorkspace "contentImport" $contentImport.enabled) -}}
{{- end -}}
{{- if has $name (list "javatooloptions" "jdkjavaoptions" "javaoptions") -}}
{{- if not (hasKey . "value") -}}
{{- fail (printf "%s in mcp.env requires literal JVM options so chart-owned properties can be validated" .name) -}}
{{- end -}}
{{- range regexFindAll "-D[^=[:space:]]+" .value -1 -}}
{{- $property := trimPrefix "-D" . -}}
{{- $normalized := regexReplaceAll "[^a-z0-9]" (lower $property) "" -}}
{{- if or (eq (include "mcp-zap-server.isSecurityProperty" $property) "true") (eq $normalized "springapplicationjson") -}}
{{- fail (printf "JVM options must not override chart-owned security property %s; use mcp.security or validated SPRING_APPLICATION_JSON" $property) -}}
{{- end -}}
{{- if and $sharedWorkspace (has $normalized (list "zapreportdirectory" "zapautomationlocaldirectory" "zapautomationzapdirectory")) -}}
{{- fail (printf "JVM options must not override shared workspace property %s; use zap.persistence.mountPath and zap.persistence.automationSubdirectory" $property) -}}
{{- end -}}
{{- if and $contentImport.enabled (eq (include "mcp-zap-server.isContentImportProperty" $property) "true") -}}
{{- fail (printf "JVM options must not override content import property %s; use zap.persistence.contentImport" $property) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if not (has $revocationBackend (list "in-memory" "postgres")) -}}
{{- fail "mcp.security.jwt.revocation.backend must be in-memory or postgres" -}}
{{- end -}}
{{- if and $jwtEnabled $multiReplicaMcp (ne $revocationBackend "postgres") -}}
{{- fail "multi-replica JWT requires mcp.security.jwt.revocation.backend=postgres; affinity and migrations do not share revocation state" -}}
{{- end -}}
{{- if eq $revocationBackend "postgres" -}}
{{- if eq (trim $revocation.postgres.url) "" -}}
{{- fail "mcp.security.jwt.revocation.postgres.url is required for the postgres backend" -}}
{{- end -}}
{{- if and $revocation.postgres.existingSecret.name (or (eq (trim $revocation.postgres.existingSecret.usernameKey) "") (eq (trim $revocation.postgres.existingSecret.passwordKey) "")) -}}
{{- fail "JWT revocation existingSecret requires usernameKey and passwordKey" -}}
{{- end -}}
{{- end -}}
{{- if and $multiReplicaMcp $sessionAffinity.enabled -}}
{{- if and (eq $sessionAffinityProvider "ingress-nginx") (not .Values.mcp.ingress.enabled) -}}
{{- fail "ingress-nginx affinity requires mcp.ingress.enabled=true" -}}
{{- end -}}
{{- if eq $sessionAffinityProvider "aws-nlb" -}}
{{- if ne .Values.mcp.service.type "LoadBalancer" -}}
{{- fail "aws-nlb affinity requires mcp.service.type=LoadBalancer" -}}
{{- end -}}
{{- $annotations := include "mcp-zap-server.mcp.serviceAnnotations" . | fromYaml -}}
{{- if get $annotations "service.beta.kubernetes.io/aws-load-balancer-ssl-cert" -}}
{{- fail "NLB TLS listeners do not support source-IP stickiness; use TLS ingress for multi-replica MCP" -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if .Values.mcp.ingress.enabled -}}
{{- if not .Values.mcp.ingress.tls -}}
{{- fail "MCP ingress requires TLS; configure mcp.ingress.tls before non-local exposure" -}}
{{- end -}}
{{- range .Values.mcp.ingress.tls -}}
{{- if or (not .secretName) (not .hosts) -}}
{{- fail "each MCP ingress TLS entry requires secretName and hosts" -}}
{{- end -}}
{{- end -}}
{{- $annotations := include "mcp-zap-server.mcp.ingressAnnotations" . | fromYaml -}}
{{- if and (eq .Values.mcp.ingress.className "nginx") (eq (toString (get $annotations "nginx.ingress.kubernetes.io/ssl-redirect")) "false") -}}
{{- fail "MCP nginx ingress must not disable HTTPS redirection" -}}
{{- end -}}
{{- if and $multiReplicaMcp (eq $sessionAffinityProvider "ingress-nginx") (contains "$http_mcp_session_id" (toString (get $annotations "nginx.ingress.kubernetes.io/upstream-hash-by"))) -}}
{{- fail "MCP nginx affinity must use a stable client hash; the MCP session ID is absent during initialization" -}}
{{- end -}}
{{- end -}}
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
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/upstream-hash-by" (default "$remote_addr$http_user_agent" $sessionAffinity.ingressNginx.upstreamHashBy) -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/proxy-read-timeout" (default "3600" $sessionAffinity.ingressNginx.proxyReadTimeout) -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/proxy-send-timeout" (default "3600" $sessionAffinity.ingressNginx.proxySendTimeout) -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/proxy-buffering" (default "off" $sessionAffinity.ingressNginx.proxyBuffering) -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/proxy-request-buffering" (default "off" $sessionAffinity.ingressNginx.proxyRequestBuffering) -}}
{{- end -}}
{{- if .Values.mcp.ingress.tls -}}
{{- $_ := set $annotations "nginx.ingress.kubernetes.io/ssl-redirect" "true" -}}
{{- end -}}
{{- range $key, $value := .Values.mcp.ingress.annotations }}
{{- $_ := set $annotations $key $value -}}
{{- end -}}
{{- if gt (len $annotations) 0 -}}
{{ toYaml $annotations }}
{{- end -}}
{{- end }}
