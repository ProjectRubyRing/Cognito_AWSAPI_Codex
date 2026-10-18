// JSR223 / Groovy. Script variables are accessed through vars/props, not substitution.
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.SocketAddress
import java.net.http.HttpClient
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

try {
    if (vars.getObject('strictHttpClient') == null) {
        ['AWS_REGION', 'COGNITO_CLIENT_ID', 'COGNITO_CLIENT_SECRET', 'COGNITO_SCOPES'].each { n ->
            if (!System.getenv(n)?.trim()) throw new IllegalArgumentException('Missing setting: ' + n)
        }
        def ssl = SSLContext.getInstance('TLS')
        ssl.init(null, null, null) // Platform trust managers; no trust-all manager.
        def parameters = new SSLParameters()
        parameters.setEndpointIdentificationAlgorithm('HTTPS')
        def noProxy = new ProxySelector() {
            List<Proxy> select(URI uri) { return [Proxy.NO_PROXY] }
            void connectFailed(URI uri, SocketAddress address, IOException error) { }
        }
        def client = HttpClient.newBuilder().sslContext(ssl).sslParameters(parameters)
            .proxy(noProxy).connectTimeout(java.time.Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build()
        vars.putObject('strictHttpClient', client)
        vars.put('refreshAtNs', '0')
    }
    def refresh = !vars.get('access_token') ||
        System.nanoTime() >= Long.parseLong(vars.get('refreshAtNs'))
    vars.put('need_token', refresh.toString())
    SampleResult.setIgnore()
} catch (Exception e) {
    vars.remove('access_token')
    SampleResult.setSuccessful(false)
    SampleResult.setResponseCode('CONFIG_ERROR')
    SampleResult.setResponseMessage(e.getClass().getSimpleName())
    SampleResult.setStopThread(true)
}
