"""Direct AWS JSON protocol sample, independent of an installed SDK model."""
import argparse
import json
import os
import urllib.error
import urllib.parse
import urllib.request


def get_token():
    region = os.environ['AWS_REGION']
    endpoint = os.environ.get('COGNITO_ENDPOINT') or f'https://cognito-idp.{region}.amazonaws.com/'
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username:
        raise ValueError('An approved HTTPS Cognito endpoint is required')
    body = json.dumps({'ClientId': os.environ['COGNITO_CLIENT_ID'],
                       'Secret': os.environ['COGNITO_CLIENT_SECRET'],
                       'Scopes': os.environ['COGNITO_SCOPES'].split()}).encode('utf-8')
    request = urllib.request.Request(endpoint, data=body, headers={
        'Content-Type': 'application/x-amz-json-1.1',
        'X-Amz-Target': 'AWSCognitoIdentityProviderService.GetClientToken'}, method='POST')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=15) as response:
            auth = json.load(response)['ClientAuthenticationResult']
    except urllib.error.HTTPError as exc:
        # Do not print response payloads, request bodies, client secrets or tokens.
        raise RuntimeError(f'Cognito HTTP status={exc.code}') from None
    if not auth.get('AccessToken') or int(auth['ExpiresIn']) <= 0:
        raise ValueError('Invalid ClientAuthenticationResult')
    return auth


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--emit-token', action='store_true')
    args = parser.parse_args()
    result = get_token()
    print(result['AccessToken'] if args.emit_token else json.dumps({
        'token_received': True, 'expires_in': result['ExpiresIn']}))
