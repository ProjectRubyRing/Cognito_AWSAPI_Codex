// Excerpt for Java SDK v2; include this method in an application with SDK dependencies.
// Use the reviewed SDK BOM version that contains GetClientTokenRequest.
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetClientTokenRequest;

class GetTokenExample {
    static String obtainToken() {
        try (var cognito = CognitoIdentityProviderClient.builder()
                .region(Region.of(System.getenv("AWS_REGION")))
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .overrideConfiguration(c -> c
                    .apiCallTimeout(java.time.Duration.ofSeconds(30))
                    .apiCallAttemptTimeout(java.time.Duration.ofSeconds(15)))
                .build()) {
            var request = GetClientTokenRequest.builder()
                .clientId(System.getenv("COGNITO_CLIENT_ID"))
                .secret(System.getenv("COGNITO_CLIENT_SECRET"))
                .scopes(System.getenv("COGNITO_SCOPES").trim().split("\\s+"))
                .build();
            return cognito.getClientToken(request)
                .clientAuthenticationResult().accessToken();
        }
    }
}
