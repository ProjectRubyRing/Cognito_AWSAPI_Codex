# Windows PowerShell 5.1 / PowerShell 7. HTTPS certificate verification stays enabled.
[CmdletBinding()]
param([switch]$EmitToken)
$ErrorActionPreference = 'Stop'
foreach ($name in @('AWS_REGION','COGNITO_CLIENT_ID','COGNITO_CLIENT_SECRET','COGNITO_SCOPES')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Missing environment variable: $name"
    }
}
$endpoint = $env:COGNITO_ENDPOINT
if ([string]::IsNullOrWhiteSpace($endpoint)) {
    $endpoint = 'https://cognito-idp.{0}.amazonaws.com/' -f $env:AWS_REGION
}
$uri = [Uri]$endpoint
if ($uri.Scheme -ne 'https' -or $uri.UserInfo) { throw 'Approved HTTPS endpoint required' }
$body = @{
    ClientId = $env:COGNITO_CLIENT_ID
    Secret = $env:COGNITO_CLIENT_SECRET
    Scopes = @($env:COGNITO_SCOPES -split '\s+' | Where-Object { $_ })
} | ConvertTo-Json -Compress
Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)
$request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
$request.Headers.Add('X-Amz-Target','AWSCognitoIdentityProviderService.GetClientToken')
$request.Content = [System.Net.Http.StringContent]::new(
    $body, [System.Text.Encoding]::UTF8, 'application/x-amz-json-1.1')
$response = $null
try {
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw ('Cognito HTTP status={0}' -f [int]$response.StatusCode)
    }
    $auth = ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() |
        ConvertFrom-Json).ClientAuthenticationResult
    if (-not $auth.AccessToken -or [int]$auth.ExpiresIn -le 0) { throw 'Invalid token response' }
    if ($EmitToken) { $auth.AccessToken }
    else { [pscustomobject]@{ TokenReceived = $true; ExpiresIn = $auth.ExpiresIn } }
}
finally {
    if ($response) { $response.Dispose() }
    $request.Dispose()
    $client.Dispose()
    $handler.Dispose()
    $body = $null
    $auth = $null
}
