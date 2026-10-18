#!/usr/bin/env bash
# Linux/WSL example; not PowerShell syntax. Requires a GetClientToken-aware CLI.
set -euo pipefail
set +x
: "${AWS_REGION:?Set AWS_REGION}"
: "${COGNITO_CLIENT_ID:?Set COGNITO_CLIENT_ID}"
: "${COGNITO_CLIENT_SECRET:?Inject COGNITO_CLIENT_SECRET securely}"
: "${COGNITO_SCOPES:?Set space-separated custom scopes}"
export AWS_PAGER=''
export AWS_MAX_ATTEMPTS=3
export AWS_RETRY_MODE=standard
export NO_PROXY='*'
export no_proxy='*'

# The secret is sent on stdin, rather than as an argument visible in ps.
make_request() {
  python3 -c 'import json,os; print(json.dumps({"ClientId":os.environ["COGNITO_CLIENT_ID"],"Secret":os.environ["COGNITO_CLIENT_SECRET"],"Scopes":os.environ["COGNITO_SCOPES"].split()}))'
}
endpoint_args=()
if [[ -n "${COGNITO_ENDPOINT:-}" ]]; then
  [[ "$COGNITO_ENDPOINT" == https://* ]] || { echo 'HTTPS required' >&2; exit 2; }
  endpoint_args=(--endpoint-url "$COGNITO_ENDPOINT")
fi
token="$(make_request | aws cognito-idp get-client-token \
  --cli-input-json file:///dev/stdin --region "$AWS_REGION" \
  --no-sign-request --no-cli-pager --cli-connect-timeout 5 \
  --cli-read-timeout 15 "${endpoint_args[@]}" \
  --query ClientAuthenticationResult.AccessToken --output text)"
[[ -n "$token" && "$token" != None ]] || { echo 'No token received' >&2; exit 1; }
if [[ "${1:-}" == --emit-token ]]; then
  printf '%s\n' "$token"
else
  printf '%s\n' 'Token received; token value suppressed.'
fi
unset token
