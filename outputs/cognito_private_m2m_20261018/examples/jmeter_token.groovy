import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

try {
    def host = props.getProperty('cognito_host', '').trim()
    if (!host) host = 'cognito-idp.' + System.getenv('AWS_REGION') + '.amazonaws.com'
    // These are reviewed configuration values; never take them from an API response.
    if (!(host ==~ /[A-Za-z0-9.-]+/)) throw new IllegalArgumentException('Invalid host')
    def payload = JsonOutput.toJson([
        ClientId: System.getenv('COGNITO_CLIENT_ID'),
        Secret: System.getenv('COGNITO_CLIENT_SECRET'),
        Scopes: System.getenv('COGNITO_SCOPES').trim().split(/\s+/).toList()])
    long started = System.nanoTime()
    def request = HttpRequest.newBuilder(URI.create('https://' + host + '/'))
        .timeout(Duration.ofSeconds(15))
        .header('Content-Type', 'application/x-amz-json-1.1')
        .header('X-Amz-Target', 'AWSCognitoIdentityProviderService.GetClientToken')
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build()
    def response = vars.getObject('strictHttpClient').send(
        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    SampleResult.setResponseCode(Integer.toString(response.statusCode()))
    if (response.statusCode() != 200) throw new IllegalStateException('Token request rejected')
    def auth = new JsonSlurper().parseText(response.body()).ClientAuthenticationResult
    long ttl = (auth.ExpiresIn as Number).longValue()
    if (!auth.AccessToken || ttl <= 0 || auth.TokenType?.toLowerCase() != 'bearer') {
        throw new IllegalStateException('Invalid token response')
    }
    double ratio = ThreadLocalRandom.current().nextDouble(0.75d, 0.80d)
    long refreshAt = started + (long)(ttl * 1_000_000_000d * ratio)
    if (System.nanoTime() >= refreshAt) throw new IllegalStateException('Token arrived too late')
    vars.put('access_token', auth.AccessToken as String)
    vars.put('refreshAtNs', Long.toString(refreshAt))
    SampleResult.setResponseMessage('Token received')
    SampleResult.setSuccessful(true)
} catch (Exception e) {
    vars.remove('access_token')
    SampleResult.setSuccessful(false)
    if (SampleResult.getResponseCode() == '200' || !SampleResult.getResponseCode()) {
        SampleResult.setResponseCode('TOKEN_ERROR')
    }
    SampleResult.setResponseMessage(e.getClass().getSimpleName())
    SampleResult.setStopThread(true)
} finally {
    // Do not retain credentials or bearer tokens in SampleResult / JTL / listeners.
    SampleResult.setResponseData('Token payload suppressed', 'UTF-8')
    SampleResult.setSamplerData('GetClientToken payload suppressed')
}
