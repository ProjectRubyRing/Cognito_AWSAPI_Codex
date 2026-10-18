import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

try {
    def host = props.getProperty('api_host', '').trim()
    def path = props.getProperty('api_path', '/test/health')
    if (!host || host.contains('REPLACE') || !(host ==~ /[A-Za-z0-9.-]+/)) {
        throw new IllegalArgumentException('Set api_host to an approved private API hostname')
    }
    if (!path.startsWith('/') || path.startsWith('//')) throw new IllegalArgumentException('Invalid API path')
    def token = vars.get('access_token')
    if (!token || System.nanoTime() >= Long.parseLong(vars.get('refreshAtNs'))) {
        throw new IllegalStateException('No current token')
    }
    def builder = HttpRequest.newBuilder(URI.create('https://' + host + path))
        .timeout(Duration.ofSeconds(15)).header('Authorization', 'Bearer ' + token).GET()
    def apiId = props.getProperty('api_id', '').trim()
    if (apiId) builder.header('x-apigw-api-id', apiId)
    def response = vars.getObject('strictHttpClient').send(
        builder.build(), HttpResponse.BodyHandlers.discarding())
    int status = response.statusCode()
    SampleResult.setResponseCode(Integer.toString(status))
    SampleResult.setResponseMessage('Private API response')
    SampleResult.setSuccessful(status >= 200 && status < 300)
    if (status == 401) {
        vars.remove('access_token') // Diagnose; do not automatically replay writes.
        SampleResult.setStopThread(true)
    }
} catch (Exception e) {
    SampleResult.setSuccessful(false)
    SampleResult.setResponseCode('API_ERROR')
    SampleResult.setResponseMessage(e.getClass().getSimpleName())
    SampleResult.setStopThread(true)
} finally {
    SampleResult.setResponseData('API response body suppressed', 'UTF-8')
    SampleResult.setSamplerData('Authorization header suppressed')
}
