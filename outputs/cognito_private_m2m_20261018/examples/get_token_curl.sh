#!/usr/bin/env bash
# Linux/WSL; direct AWS API, not the public OAuth /oauth2/token endpoint.
set -euo pipefail
set +x
: "${AWS_REGION:?}"
: "${COGNITO_CLIENT_ID:?}"
: "${COGNITO_CLIENT_SECRET:?}"
: "${COGNITO_SCOPES:?}"
endpoint="${COGNITO_ENDPOINT:-https://cognito-idp.${AWS_REGION}.amazonaws.com/}"
[[ "$endpoint" == https://* ]] || { echo 'HTTPS required' >&2; exit 2; }
python3 -c 'import json,os; print(json.dumps({"ClientId":os.environ["COGNITO_CLIENT_ID"],"Secret":os.environ["COGNITO_CLIENT_SECRET"],"Scopes":os.environ["COGNITO_SCOPES"].split()}))' |
  curl -q --silent --show-error --fail --noproxy '*' --connect-timeout 5 --max-time 15 \
    --proto '=https' --request POST "$endpoint" \
    --header 'Content-Type: application/x-amz-json-1.1' \
    --header 'X-Amz-Target: AWSCognitoIdentityProviderService.GetClientToken' \
    --data-binary @- |
  python3 -c 'import json,sys; a=json.load(sys.stdin)["ClientAuthenticationResult"]; assert a["AccessToken"] and int(a["ExpiresIn"])>0; print(json.dumps({"token_received":True,"expires_in":a["ExpiresIn"]}))'
