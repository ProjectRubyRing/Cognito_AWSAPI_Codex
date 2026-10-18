"""GetClientToken sample. A private route and private DNS are prerequisites."""
import argparse
import json
import os
import random
import threading
import time
import urllib.parse
import urllib.request

import boto3
from botocore import UNSIGNED
from botocore.config import Config


def required(name):
    value = os.environ.get(name, '').strip()
    if not value:
        raise ValueError(f'Missing environment variable: {name}')
    return value


class TokenProvider:
    """One cache per process/client/scope set; synchronize concurrent refreshes."""
    def __init__(self, client, client_id, secret, scopes):
        self.client, self.client_id = client, client_id
        self.secret, self.scopes = secret, tuple(sorted(set(scopes)))
        self.lock = threading.Lock()
        self.token, self.refresh_at, self.expires_at = None, 0.0, 0.0

    def get(self):
        with self.lock:
            if self.token and time.monotonic() < self.refresh_at:
                return self.token
            # Start the lifetime clock before the request, conservatively.
            started = time.monotonic()
            response = self.client.get_client_token(
                ClientId=self.client_id, Secret=self.secret,
                Scopes=list(self.scopes))
            auth = response['ClientAuthenticationResult']
            lifetime = int(auth['ExpiresIn'])
            if lifetime <= 0 or auth.get('TokenType', '').lower() != 'bearer':
                raise ValueError('Invalid token response metadata')
            token = auth['AccessToken']
            if not isinstance(token, str) or not token:
                raise ValueError('Missing access token')
            # Refresh at 75-80% of TTL; do not serve an expired token on failure.
            self.token = token
            self.refresh_at = started + lifetime * random.uniform(0.75, 0.80)
            self.expires_at = started + lifetime
            if time.monotonic() >= self.refresh_at:
                self.token = None
                raise TimeoutError('Token response arrived too late')
            return token


def make_provider():
    region = required('AWS_REGION')
    scopes = required('COGNITO_SCOPES').split()
    endpoint = os.environ.get('COGNITO_ENDPOINT', '').strip()
    if endpoint:
        parsed = urllib.parse.urlsplit(endpoint)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username:
            raise ValueError('COGNITO_ENDPOINT must be an approved HTTPS endpoint')
    # UNSIGNED prevents credential lookup, including unintended IMDS/STS calls.
    client = boto3.client(
        'cognito-idp', region_name=region,
        endpoint_url=endpoint or None,
        config=Config(signature_version=UNSIGNED, connect_timeout=5,
                      read_timeout=15, proxies={},
                      retries={'mode': 'standard', 'total_max_attempts': 3}))
    if 'GetClientToken' not in client.meta.service_model.operation_names:
        raise RuntimeError('This boto3/botocore model lacks GetClientToken; update it')
    return TokenProvider(client, required('COGNITO_CLIENT_ID'),
                         required('COGNITO_CLIENT_SECRET'), scopes)


def call_api(token):
    url = required('API_URL')
    if urllib.parse.urlsplit(url).scheme != 'https':
        raise ValueError('API_URL must use HTTPS')
    headers = {'Authorization': 'Bearer ' + token}
    if os.environ.get('API_ID'):
        headers['x-apigw-api-id'] = os.environ['API_ID']
    request = urllib.request.Request(url, headers=headers, method='GET')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    # No automatic business-request retry; this sample only sends GET.
    with opener.open(request, timeout=15) as response:
        return response.status


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--emit-token', action='store_true',
                        help='Emit the secret token to stdout for a protected pipe')
    parser.add_argument('--call-api', action='store_true')
    args = parser.parse_args()
    provider = make_provider()
    token = provider.get()
    if args.emit_token:
        print(token)
    elif args.call_api:
        print(json.dumps({'api_status': call_api(token)}))
    else:
        print(json.dumps({'token_received': True,
                          'expires_in_remaining': max(0, int(provider.expires_at - time.monotonic()))}))


if __name__ == '__main__':
    main()
