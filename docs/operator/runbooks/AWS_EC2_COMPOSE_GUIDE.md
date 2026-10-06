# AWS EC2 with Docker Compose

Run one private MCP ZAP Server on a dedicated Amazon Linux EC2 instance, connect
through AWS Session Manager, and verify scanning and report retrieval against an
API supplied with the example. This path is useful when you have an AWS account
and want to evaluate the server without creating a Kubernetes cluster.

The [example configuration](../../../examples/aws-ec2/) uses published MCP ZAP
Server **v0.14.0** and ZAP **2.17.0** images pinned by digest. The reference
configuration uses Linux x86_64, 4 vCPUs, 16 GiB RAM and an encrypted 40 GiB disk.
These are a starting point for a small evaluation, not measured production
capacity or a minimum requirement. Crawl, bounded active scan, report readback,
OpenAPI import, automation, container restart and EC2 reboot have been exercised
with this private deployment approach. Validate the supplied example on your own
instance before relying on it.

The upcoming HTTP depth correction, target-report metadata filtering and
`zap_report_read_chunk` tool are **not included in the pinned `v0.14.0` image**.
On that version, `ZAP_SPIDER_MAX_DEPTH` limits HTTP spider children rather than
actual depth, and `ZAP_SPIDER_MAX_CHILDREN` is not recognized. Do not rely on
those environment settings as a depth boundary until you upgrade to an image
containing the correction. Automation plans have their own explicit job limits.
See [HTTP crawl limits](https://danieltse.org/mcp-zap-server/scanning/scan-execution-modes/#http-crawl-limits-unreleased-correction).

This walkthrough serves one trusted client. Its example blocks external scan
traffic and contains an owned sample API. For Kubernetes, use the
[Helm deployment guide](../../../helm/mcp-zap-server/README.md).

## 1. Prepare your AWS access

On the computer where your MCP client runs, install:

- AWS CLI v2, configured for the account you intend to use
- the [Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html)
- a Streamable HTTP MCP client that supports the `X-API-Key` header

Check the selected account and region locally:

```bash
aws sts get-caller-identity
aws configure get region
```

Use `us-east-1` for the reference procedure, or select a region supported by your
account. Keep the account identity and runtime credentials out of public issue
reports.

Your operator permissions and the instance's role have different purposes:

- Your AWS user/role needs permission to create and terminate this EC2 instance,
  use its security group, pass the instance role, and start/end Session Manager
  sessions. Remote-host forwarding also needs access to the
  `AWS-StartPortForwardingSessionToRemoteHost` document. Follow AWS's
  [Session Manager access examples](https://docs.aws.amazon.com/systems-manager/latest/userguide/getting-started-restrict-access-quickstart.html)
  rather than granting unrestricted administrator access for this guide.
- The instance needs a role trusted by EC2 with
  `AmazonSSMManagedInstanceCore`. Create a dedicated role/profile if you need one,
  following AWS's [instance permissions guide](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-instance-permissions.html).
  Record which resources you create so cleanup can distinguish them from shared
  resources.

EC2 compute, EBS storage, public IPv4 and data transfer can incur charges. Check
[current EC2 pricing](https://aws.amazon.com/ec2/pricing/on-demand/),
[EBS pricing](https://aws.amazon.com/ebs/pricing/) and
[IPv4 pricing](https://aws.amazon.com/vpc/pricing/) before launching. Choose a
cleanup time. Stopping an instance does not remove its billable disk.

## 2. Launch a dedicated EC2 instance

Use the EC2 console with these settings:

| Setting | Reference value |
| --- | --- |
| AMI | Amazon Linux 2023 **standard x86_64** image |
| Instance | `m7i.xlarge`, or an x86_64 instance with equivalent capacity |
| Storage | 40 GiB encrypted `gp3`; root volume **Delete on termination** enabled |
| Instance role/profile | The EC2 role with `AmazonSSMManagedInstanceCore` |
| Security group | A dedicated group with **zero inbound rules** |
| Subnet | Existing public subnet with a route to an internet gateway |
| Public IPv4 | Automatically assigned; no Elastic IP needed |
| Metadata | IMDSv2 required; response hop limit `1`; IPv6 metadata disabled |

The host needs outbound connectivity for Session Manager, packages and image
pulls. The reference security group permits host outbound access; Docker and the
host firewall separately restrict the scanning containers. A public IP enables
host connectivity here; it is not an application endpoint.

Do not use the VPC's default security group: it normally allows traffic from
other resources using that group. This guide does not create a NAT gateway,
load balancer or database. If your account has no suitable public subnet, arrange
networking with your AWS administrator before proceeding. See AWS's
[default subnet documentation](https://docs.aws.amazon.com/vpc/latest/userguide/default-subnet.html).

IMDS settings reduce metadata exposure but do not replace network isolation.
The hop limit applies to the metadata token response. See
[AWS metadata options](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-options.html).
The application containers do not need AWS credentials.

Wait for the instance checks to pass and connect using **Session Manager** from
the EC2 console. No SSH inbound rule or key pair is required by this access path.
Remote-host forwarding needs SSM Agent version `3.1.1374.0` or newer. The standard
Amazon Linux image includes SSM Agent; if the instance is not available, check
its role, agent and outbound connectivity before changing inbound rules.

## 3. Install the example on the host

The following commands run in the instance's Session Manager shell. Use a root
shell for installation:

```bash
sudo -i
dnf install -y git
git clone --depth 1 https://github.com/dtkmn/mcp-zap-server.git /opt/mcp-zap-server
cd /opt/mcp-zap-server/examples/aws-ec2
bash setup-host.sh
```

Use a checkout containing this guide and `examples/aws-ec2`. The repository
provides configuration; the example runs the pinned published images and does
not build the Java application from the checkout.

The setup script checks Amazon Linux 2023/x86_64 and network-range conflicts,
installs Docker and iptables, verifies a pinned Docker Compose download, prepares
writable storage for UID/GID `1000`, and installs persistent firewall rules. It
does not provision AWS resources or start the containers. Review the script on a
dedicated host; it is not an installer for a host running unrelated Docker stacks.

The standard Amazon Linux image already includes AWS CLI. `awscli2` is not the
package to add to this Docker installation. AWS documents the
[standard AMI package set](https://docs.aws.amazon.com/linux/al2023/ug/amzn2-al2023-ami.html)
and [Docker installation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/create-container-image.html).

## 4. Configure credentials and shared storage

Create two independent secrets: one MCP API key for your client and one ZAP API
key for MCP-to-ZAP communication. For this example, use **64 random hexadecimal
characters** per key (32 random bytes, using only `0-9` and `a-f`). Store them in
your password manager and keep the MCP key available on your client computer.
This format avoids shell, dotenv and URL delimiters in the example configuration.

On EC2, enter those keys at hidden prompts. Session Manager can record commands
and terminal output, so do not put keys into command text or display them in a
terminal editor. AWS recommends [non-echoing secret input](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-logging.html).
The following Bash snippet disables tracing, validates the format, writes a
private file and clears the temporary variables:

```bash
{
  set +x
  umask 077
  install -m 0600 .env.example .env
  printf 'MCP API key (64 hex characters): ' >&2
  IFS= read -r -s MCP_EC2_KEY </dev/tty
  printf '\nZAP API key (different 64 hex characters): ' >&2
  IFS= read -r -s ZAP_EC2_KEY </dev/tty
  printf '\n' >&2
  if [[ $MCP_EC2_KEY =~ ^[0-9a-f]{64}$ && $ZAP_EC2_KEY =~ ^[0-9a-f]{64}$ &&
        $MCP_EC2_KEY != "$ZAP_EC2_KEY" ]]; then
    printf 'MCP_API_KEY=%s\nZAP_API_KEY=%s\n' "$MCP_EC2_KEY" "$ZAP_EC2_KEY" > .env
  else
    printf 'Keys must be different and each contain exactly 64 lowercase hex characters. Retry before starting.\n' >&2
  fi
  unset MCP_EC2_KEY ZAP_EC2_KEY
}
```

Keep `.env` private; never display it in session logs, commit it, or publish fully
resolved Compose configuration. The example requires both keys and refuses
missing values before containers start. Other secret-delivery systems can write
the same mode `0600` file without printing the values.

The example fixes the client identity to `ec2-operator`. It mounts the same
`workspace` directory as `/zap/wrk` in both containers and uses:

```text
Report workspace: /zap/wrk/workspaces/ec2-operator
Automation root:  /zap/wrk/workspaces/ec2-operator/automation
```

Both automation settings point at that same directory:

- `ZAP_AUTOMATION_LOCAL_DIRECTORY`: path visible to MCP
- `ZAP_AUTOMATION_ZAP_DIRECTORY`: path visible to ZAP

This places automation reports inside the caller's report-read boundary.
The report-read tools will not grant access to a file merely because ZAP generated it.
With different mount paths, map both settings to the same underlying storage.
See the [automation workspace requirements](https://danieltse.org/mcp-zap-server/scanning/automation-framework/#align-automation-and-report-workspaces).

The sample enables the expert tool surface for import and automation checks and
limits scan concurrency and duration. It permits private network targets solely
for the exact `smoke-target` hostname. Keep those settings for the isolated
example; they are not a general permission to scan your VPC.

## 5. Start the containers and check isolation

From the example directory on EC2:

```bash
docker compose config --quiet
docker compose pull
docker compose up -d --no-build
docker compose ps
```

Wait for ZAP and MCP to show healthy. Confirm the Docker network and firewall:

```bash
docker network inspect --format '{{.Internal}}' mcp-zap-ec2_isolated
iptables -C INPUT -i br-mcpzap -j MCPZAP-HOST
iptables -C DOCKER-USER -j MCPZAP-FWD
systemctl is-active mcp-zap-ec2-firewall.service
```

The network inspection must print `true`; both firewall checks must succeed.
There are no published host ports for MCP, ZAP or the sample API. Internal Docker
networking alone does not isolate host services, which is why the host firewall
is part of the example.

Check actual connections from the ZAP container. This probe only attempts TCP
connections; it does not fetch metadata or perform an external scan:

```bash
docker compose exec -T zap python3 - <<'PY'
import socket
checks = [('smoke-target', 8080, True), ('169.254.169.254', 80, False),
          ('1.1.1.1', 443, False), ('172.30.250.1', 22, False)]
for host, port, expected in checks:
    connected = False
    try:
        with socket.create_connection((host, port), timeout=3):
            connected = True
    except OSError:
        pass
    print(f'{host}:{port} connected={connected} expected={expected}')
    if connected != expected:
        raise SystemExit('Unexpected network access; stop and inspect configuration')
PY
```

The API must be reachable; metadata and unrelated connections must fail. The
host-port probe is a limited check, not a positive-control proof that a service
exists there. Inspect the firewall rules as well. HTTP health alone does not
prove scan-target connectivity or workspace permissions.

## 6. Open the private MCP connection

On EC2, discover MCP's current address:

```bash
docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
  "$(docker compose ps -q mcp-server)"
```

Copy only that address and your instance ID to your client computer. On that
computer, set your own values and keep this forwarding session running:

```bash
AWS_REGION=us-east-1
INSTANCE_ID='replace-with-your-instance-id'
MCP_CONTAINER_IP='replace-with-the-observed-container-address'

aws ssm start-session \
  --region "$AWS_REGION" \
  --target "$INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"$MCP_CONTAINER_IP\"],\"portNumber\":[\"7456\"],\"localPortNumber\":[\"17456\"]}"
```

Your client endpoint is now **`http://127.0.0.1:17456/mcp`**. Configure your MCP
client to send `X-API-Key` with the MCP key you stored earlier. The local HTTP
connection travels through the authenticated, encrypted SSM tunnel. Only clients
on this computer can use its localhost address.

Follow the [MCP client configuration guide](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/),
substituting port `17456` for the local example's `7456`. Use a client that handles
MCP initialization and session negotiation. Closing the forwarding session closes
client access. Discover the container address again after recreation or reboot;
do not assume it remains unchanged. See AWS's
[remote-host forwarding requirements](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-sessions-start.html).

A container attached only to an internal Docker network may not publish a
requested host port on the tested Docker version. This example uses the direct
private-container tunnel and does not depend on port publishing.

## 7. Verify a real scan and report

Inspect the server's actual tools and argument schemas through your MCP client.
Follow the [scan-to-evidence workflow](https://danieltse.org/mcp-zap-server/scanning/mcp-client-scan-to-evidence/)
with target **`http://smoke-target:8080`**:

1. Confirm missing/incorrect MCP keys are rejected before using the valid key.
2. Start an HTTP crawl with `zap_crawl_start`; poll `zap_crawl_status` until
   completed. A successful start response is not completion.
3. Run a bounded active scan with `zap_attack_start` on this sample only; poll
   `zap_attack_status` until completed.
4. Wait for passive scanning with `zap_passive_scan_wait`.
5. Request `zap_findings_summary`, then generate a JSON report with
   `zap_report_generate`.
6. Read the **exact returned report path** with `zap_report_read`, setting
   `maxChars` to `200000` (the default is only `20000`). Check the `Truncated`
   flag and confirm the content contains findings for `smoke-target`. A truncated
   preview does not prove complete report retrieval. Save the path and complete
   report content locally for the recovery check.

On a server exposing `zap_report_read_chunk`, retrieve all pages by following
`nextOffset` until `endOfFile` is true and pass the first page's `artifactSha256`
as `expectedSha256` on later reads. Append each page's `content` exactly. See
[complete report retrieval](https://danieltse.org/mcp-zap-server/scanning/findings-and-reports/#preview-or-complete-retrieval).
The pinned `v0.14.0` image supports previews only. If its preview is truncated,
an operator must retrieve the complete file privately from the mounted workspace;
do not treat the preview as the complete artifact or expose a public download port.

The sample intentionally omits security headers so reports can contain findings.
Those findings describe the sample API, not the MCP server's security.

For expert mode, import
`http://smoke-target:8080/openapi.json` with `zap_import_openapi_spec_url` and
`hostOverride` set to `http://smoke-target:8080`. The specification contains an
unlinked `/imported-only` operation. Check that it appears after import, rather
than counting a URL the crawl had already discovered as proof of import.

Host setup installs the supplied [Automation Framework plan](https://danieltse.org/mcp-zap-server/scanning/automation-framework/)
inside the shared workspace. It runs requestor, bounded HTTP spider,
passive-wait and JSON-report jobs against the sample. Call
`zap_automation_plan_run` with these arguments:

```json
{
  "planPath": "/zap/wrk/workspaces/ec2-operator/automation/example-plan.yaml"
}
```

Poll `zap_automation_plan_status` with the returned `Plan ID` until completion;
check the success result and any errors or warnings. Call
`zap_automation_plan_artifacts` with the returned `Plan File` as `planPath`, then
read the generated JSON report's exact path using the same preview or complete
retrieval procedure above. Both successful completion and complete report
retrieval matter. Native Automation Framework report jobs use ZAP's templates
and can include metadata from the shared session; the MCP target-report metadata
filter does not apply to those jobs.

## 8. Verify restart and reboot

On EC2:

```bash
docker compose restart
```

Wait for health to recover, connect with a new MCP client/session, read the saved
report and compare its content, then complete a fresh scan. Next, use the EC2
console to reboot the instance. Reconnect through Session Manager, return to the
example directory, check health and firewall rules, and reopen the private tunnel
using the newly observed container address. Repeat report readback and a new scan.

Docker and the firewall are enabled at boot; the firewall is also reapplied after
Docker starts. This recovery check verifies saved report files and new work.
In-memory sessions, findings and in-progress jobs are not guaranteed to survive
recreation. Work needing durable job/history state requires separately configured
and tested persistence.

## 9. Move beyond the isolated example deliberately

The example's internal network and firewall **block external scanning**.
Changing `ZAP_URL_WHITELIST` does not open a network route from ZAP.

For your own staging API, arrange ZAP-side egress to the approved target and the
DNS services it needs, while retaining metadata/host restrictions. Keep the MCP
endpoint private, map the approved hostname into the application's destination
policy, and prove actual target reachability and report delivery. Review the
[production checklist](https://danieltse.org/mcp-zap-server/operations/production-checklist/)
before shared or internet-facing use.

Public HTTPS ingress, authenticated target scanning, browser crawling,
multi-client isolation, concurrency, backup/restore and high availability each
need their own configuration and validation. An application whitelist validates
declared destinations; it is not an engine egress firewall. Do not remove the
sample firewall wholesale to reach a new target.

## 10. Clean up and verify billing resources

Save only the reports you need before cleanup. On EC2, stop and remove the sample
containers/network:

```bash
docker compose down
```

End your forwarding and shell sessions. In the EC2 console, **terminate** the
dedicated instance when the evaluation is finished. Verify:

- the instance is terminated
- its root disk was deleted, and no disk created for this example remains
- its automatically assigned public address is released
- the dedicated security group has no remaining interfaces and can be deleted
- the role/profile created solely for this example can be removed

Delete only resources you created for this example. Preserve shared roles,
security groups, VPCs and unrelated resources. Stopping an instance retains its
EBS disk; termination also retains any volume configured with Delete on
termination disabled. AWS explains [EBS termination behavior](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/preserving-volumes-on-termination.html).
Do not treat a stopped instance as completed cleanup.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Instance unavailable in Session Manager | Instance role, agent status, outbound route/DNS and operator permissions |
| Session starts but forwarding fails | Current container address, MCP health, host-to-container connectivity, remote-host session document and local plugin |
| API-key rejection | Use the MCP key rather than the ZAP key; recreate MCP after changing `.env` |
| No automation tools | Inspect the actual surface; this example uses expert, while the main local Compose setup defaults to guided |
| Healthy services but report readback fails | Shared mount permissions, client workspace and both automation directory settings |
| External scan cannot connect | Expected for this example; approve and validate engine egress before changing scope |
| Need a shell/curl inside MCP | The released Java image is distroless; use health status, logs and supported MCP tools |
| Unexpected storage charges | Look for retained EBS volumes; stopping does not delete them |

When requesting help, provide versions, the failed step and sanitized error text.
Remove API keys, tokens, account/resource identifiers and private report content.
