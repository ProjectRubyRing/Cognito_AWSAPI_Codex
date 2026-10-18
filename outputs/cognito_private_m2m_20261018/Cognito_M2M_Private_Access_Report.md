# Cognito M2M認証の閉域接続に関する検討結果と運用提案

調査基準日：2026年10月18日（日本時間）

対象：Cognito User PoolsでM2M認証を構成し、Salesforce連携用Private API Gateway REST APIをNLB／VPCE経由で利用する構成。

**結論：GetClientTokenとCognito用PrivateLinkを使えば、M2Mトークン取得とPrivate REST API呼出を公衆網なしで構成できる。既存の`/oauth2/token`をSDKで呼び直すだけでは実現しない。** [S01](https://aws.amazon.com/about-aws/whats-new/2026/08/amazon-cognito-get-client-token/) [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html)

「公式確認」「設計提案」「試算例」「ローカル確認」「実環境未検証」を区別する。既存環境の設定変更やAWSへの発行リクエストは行っていない。

## 目次

- 00 検討結果
- 01 基礎と歴史
- 02 構成と経路
- 03 設定と移行
- 04 呼出方法
- 05 実装コード
- 06 JMeter手順
- 07 運用と検証
- 08 負荷と費用
- 09 根拠と未確定

## 00 検討結果

調査基準日：2026-10-18（日本時間）。結論は条件付きで可能。GetClientTokenとCognito用VPCEを使い、Private REST APIへの経路も個別に閉域化する。AWS・Salesforceの実環境には接続していない。

### 質問への回答

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| SDKを使えば自動で閉域になるか | ならない。SDKは依頼を組み立てる道具。接続先・DNS・ルートが通信経路を決める。 | CognitoのGetClientTokenに対応したSDKと、cognito-idp用Interface VPCE、private DNSを組み合わせる。 | [S01](https://aws.amazon.com/about-aws/whats-new/2026/08/amazon-cognito-get-client-token/) [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) |
| M2Mトークンを閉域で取得できるか | できる。2026-08-31公開のGetClientTokenがPrivateLinkに対応する。従来のOAuth /oauth2/tokenとは別の入口。 | ドメインなしの専用プール／専用アプリクライアントを検証構成の基準にする。既存のclient_credentials設定だけでは利用条件を満たさない。 | [S01](https://aws.amazon.com/about-aws/whats-new/2026/08/amazon-cognito-get-client-token/) [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| 既存のOAuthトークンURLをそのまま使えるか | /oauth2/tokenはPrivateLink対象外。curl・JMeter・SDKラッパーで呼び方を変えても、同じURLなら閉域化できない。 | JSON形式のGetClientTokenに切り替える。NATやHTTPプロキシで中継しても、公開入口への接続は残る。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) [S06](https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html) |
| Private REST APIも公衆網なしで呼べるか | できる。JMeter実行機からexecute-api用VPCEに到達し、Private REST APIを呼ぶ。 | トークン用VPCEとAPI用VPCEは別に必要。APIのリソースポリシー・認可スコープ・TLS・DNSを確認する。 | [S07](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-apis.html) [S08](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-api-test-invoke-url.html) [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) |
| Salesforce接続がある場合 | Private Connect outbound等でSalesforceからAWSへの経路を閉域化できる。ただし業務APIへの経路だけではトークン取得先の閉域性を証明できない。 | Salesforce側のトークン取得を別に設計。GetClientTokenは標準OAuthフォームと違うため、Named Credentialでの利用可否を個別検証する。 | [S25](https://aws.amazon.com/blogs/apn/private-integration-salesforce-and-amazon-api-gateway/) [S26](https://trailhead.salesforce.com/content/learn/modules/private-connect-outbound-connections/create-an-outbound-connection) [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) |
| NLBがあればCognitoも通れるか | NLBはL4の中継装置。API向けNLBを置くだけではCognito向け経路もOAuth→AWS JSONの変換も生まれない。 | 必要なら認証済みの内部トークンブローカーを別途設計。接続先を自由指定できる汎用プロキシにしない。 | 設計提案 [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S26](https://trailhead.salesforce.com/content/learn/modules/private-connect-outbound-connections/create-an-outbound-connection) |
| この調査の確度 | GetClientTokenの存在・API契約・PrivateLink対応は公式資料で確認。利用リージョン・既存プール設定・Salesforce契約・実通信は未確認。 | 資料のコードは設計・ローカル検証の例。実環境の閉域性と互換性は受入試験で確定する。 | [S01](https://aws.amazon.com/about-aws/whats-new/2026/08/amazon-cognito-get-client-token/) [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |

### 推奨する運用判断

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 第一候補 | AWS VPC内のJMeterから、GetClientTokenとPrivate REST APIをそれぞれのVPCEで直接呼ぶ。 | 本番とは別の検証用M2Mクライアント、最小スコープ、少数スレッドから開始する。 | 設計提案 |
| 既存環境を保つ移行 | 既存OAuth用プールは継続し、閉域M2M専用プールを新設する案を優先する。 | APIの既存Cognitoオーソライザーに新プールを追加できるか確認。必要なら検証用API／メソッドから移行する。 | 設計提案 [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) |
| 既存プールを再利用する案 | ドメインが不要な専用プールなら候補。ドメインありプールは公式資料に不整合があるため、現状維持のまま閉域化できると断言しない。 | 実トークン発行をVPCE経由で検証し、必要ならAWS Supportにドメイン共存条件を確認する。既存ドメインを無断削除しない。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| 完了とみなす条件 | インターネット出口遮断下でトークン取得とAPI呼出が成功し、失敗すべき認可・経路が拒否されること。 | DNS・ルート・Flow Logs・APIログ・Salesforce接続設定を証跡として保存する。 | 設計提案 |


## 01 基礎と歴史

たとえると、Cognitoは入場券を出す受付、アクセストークンは期限付き入場券、API Gatewayは券を調べる門、VPCEは建物内の専用通路。券をもらう道と、券を使って門へ行く道は別である。

### ことばを身近な例で理解する

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| M2M | 機械と機械のやり取り。人が毎回ログインせず、プログラム自身の身分で利用する。 | Salesforce連携・定期バッチ・JMeter等。人間のユーザー名／パスワードに置き換えて解決しない。 | [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) |
| Client ID | 機械につけた受付番号。番号だけでは入場券をもらえない。 | 環境・用途ごとに分ける。単なるIDなので、client secretと区別する。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) |
| Client secret | 機械だけが持つ合言葉。盗まれると機械になりすまされる。 | Secrets Manager等で管理。ソース・Excel・JMX・コマンド履歴へ実値を埋め込まない。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S22](https://docs.aws.amazon.com/secretsmanager/latest/userguide/vpc-endpoint-overview.html) |
| Access token | APIでできることと有効期限が書かれた署名付きの券。JWTという形式を使う。 | M2Mはaccess tokenだけ。ID tokenやrefresh tokenを期待しない。 | [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) [S06](https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html) [S14](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html) |
| Bearer | 券を持っている人が使える方式。本人の別の合言葉は毎回必要ない。 | Authorization: Bearer <token>。券そのものを秘密として扱う。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S15](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html) |
| Scope | 入れる部屋を決める券の印。例：orders/readなら注文を読む権利。 | APIの要求スコープ、クライアントの許可スコープ、トークン内scopeを一致させる。 | [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) |
| JWTの署名 | 受付の本物の印鑑にあたる。中身の文字を読めることと、本物と確かめられることは別。 | 署名・issuer・期限・token_use・scope等を検証する。Base64デコードだけで信用しない。 | [S15](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html) |
| User Pool | Cognitoの受付所と台帳。M2Mでも設定の入れ物として使う。 | M2Mに架空の人間ユーザーを作る必要はない。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) |
| Identity Pool | 身分証と引き換えに一時的AWS認証情報を渡す別の仕組み。 | 今回のCognito JWT取得はUser Pools。cognito-identity用VPCEを代わりに作らない。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| AWS IAM認証情報 | AWSの管理作業等の権限を示す別の鍵。JWTとは形式も用途も違う。 | GetClientToken自体はclient secretで認証。設定作成・Secrets Manager取得などはIAMが別途必要。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S22](https://docs.aws.amazon.com/secretsmanager/latest/userguide/vpc-endpoint-overview.html) [S35](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_CreateUserPoolClient.html) |
| SDK／CLI／HTTP | 受付への伝言を作る道具。SDKはプログラム、CLIはコマンド、HTTPは通信の約束。 | 道具を替えるだけでは通路は変わらない。入口とネットワークの両方を選ぶ。 | [S01](https://aws.amazon.com/about-aws/whats-new/2026/08/amazon-cognito-get-client-token/) [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) |
| DNS | 建物の名前から住所を調べる案内係。 | amazonaws.comという名前でもprivate DNSによりVPCEのプライベートIPを返せる。名前の見た目だけで公開経路と決めない。 | [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) [S21](https://docs.aws.amazon.com/vpc/latest/privatelink/interface-endpoints.html) |
| VPCE／PrivateLink | VPC内にAWSサービスへの専用入口を設ける仕組み。 | cognito-idpとexecute-apiを使い分ける。Interface型のENI・SG・AZを確認する。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) [S07](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-apis.html) [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) |
| NAT／IGW | VPCから外の公開入口へ出るための経路を構成する装置。 | NATを使う公開AWSサービス接続は、今回の「公開入口も使わない」要件と分けて評価する。 | [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) |
| 公衆網なしの意味 | この資料では、対象リクエストが公開サービス入口・インターネット出口・インターネットVPNへ依存しないこと。 | AWS網内を通る公開AWSエンドポイント通信もあり得るが、それをPrivateLinkと同一扱いにしない。 | [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) [S23](https://docs.aws.amazon.com/directconnect/latest/UserGuide/Welcome.html) |
| 閉域と非公開化 | 自分たちの通信が専用通路を使うことと、受付が外から絶対に使えないことは別。 | VPCE作成だけでCognitoの公開APIは消えない。必要なら対象アクションに絞ったRCP等も検討する。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| VPNとDirect Connect | 普通のインターネットVPNは公道の中を暗号化して走る。Direct Connectは専用の接続を使う。 | オンプレ実行機も含め公衆網ゼロなら、閉域回線の実契約・バックアップ経路まで確認。VPNという名前だけで判断しない。 | [S23](https://docs.aws.amazon.com/directconnect/latest/UserGuide/Welcome.html) |
| JMeter | 大勢のお客さんが同時に来る様子を再現する試験道具。 | API性能試験と券を発行する受付の試験を分ける。毎回券を取り直すと別の試験になる。 | [S28](https://jmeter.apache.org/usermanual/get-started.html) [S29](https://jmeter.apache.org/usermanual/best-practices.html) 設計提案 |

### 何がいつ登場し、なぜ必要になったか

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 2012-10：OAuth 2.0 | RFC 6749でClient Credentialsを含む枠組みを標準化。機械自身が権限を得る方法を整理した。 | 機械の合言葉を業務APIへ毎回渡す代わりに、期限付きトークンで権限を伝える。 | [S31](https://www.rfc-editor.org/rfc/rfc6749.html) |
| 2014-07-10：Cognitoの登場 | モバイルアプリの利用者認証やデータ同期を簡単にするサービスとして登場。複数の外部IDプロバイダーやゲスト利用に対応した。 | 当初から現在のM2M発行APIが存在したわけではない。Identity PoolsとUser Poolsを区別する背景にもなる。 | [S39](https://aws.amazon.com/blogs/aws/aws-mobile-services/) |
| 2016-07-28：User Pools正式提供 | アプリ向けの利用者台帳、サインアップ、サインインを管理サービスとして提供。API Gatewayとの連携も加わった。 | ユーザー管理や認証基盤を各アプリで一から作る負担を減らした。後のOAuth／M2Mの設定もこのUser Poolを使う。 | [S40](https://aws.amazon.com/about-aws/whats-new/2016/07/amazon-cognito-your-user-pools-is-now-generally-available/) |
| 2017-08-10：Cognito OAuth拡充 | Client Credentials、カスタムスコープ、リソースサーバーが提供された。 | 機械に「注文を読む」などの細かな権限を与えられる。ただし取得先はドメインのOAuth入口だった。 | [S32](https://aws.amazon.com/about-aws/whats-new/2017/08/amazon-cognito-launches-general-availability-of-a-built-in-customizable-user-experience-for-sign-in-oauth-2-0-support-and-federation-with-facebook-login-with-amazon-google-and-saml-providers-for-user-pools/) |
| 2018-06-14：Private API | API GatewayにPrivate Endpointが登場。業務APIそのものを公開せずに利用する要求へ対応した。 | APIが閉域化しても、トークン取得の経路は別に残る。このずれが今回の疑問の背景。 | [S33](https://aws.amazon.com/blogs/compute/introducing-amazon-api-gateway-private-endpoints/) |
| 2024-02-29：Salesforce連携例 | AWSとSalesforceの公式構成例が、Private ConnectとPrivate APIの同期連携を紹介した。 | これは接続構成の例。記事の認証はIAM／STSを利用し、Cognito M2M閉域化の証拠と混同しない。 | [S25](https://aws.amazon.com/blogs/apn/private-integration-salesforce-and-amazon-api-gateway/) |
| 2025-11-07：Cognito PrivateLink | User PoolsのSDK API等に閉域接続を追加。公開発表時点ではOAuth client credentialsは対象外だった。 | 当時の「M2Mは閉域不可」という説明には時点依存の理由がある。 | [S34](https://aws.amazon.com/about-aws/whats-new/2025/11/amazon-cognito-user-pools-private-connectivity-aws-privatelink/) |
| 2026-08-31：GetClientToken | ドメインなしでSDK・CLI・APIからM2M access tokenを取得する専用APIを追加し、PrivateLinkに対応した。 | この新しい入口を選ぶことで、券をもらう道も業務APIを呼ぶ道も閉域にそろえられる。 | [S01](https://aws.amazon.com/about-aws/whats-new/2026/08/amazon-cognito-get-client-token/) |
| 今回なぜ必要か（設計上の整理） | 業務APIだけ閉じても、認証だけ公開経路へ出ると、ネットワーク制約・監査・障害切分けが複雑になる。 | 発行と利用を別々に設計して、両方を同じ閉域要件で検証する。これは本構成に対する考察であり、AWSの開発理由の断定ではない。 | 設計提案 |


## 02 構成と経路

以下は推奨案と想定構成。現行NLBの配置・ターゲット・TLS終端・Salesforce接続方式は未提示のため、現状図として断定しない。

### 構成A：AWS内JMeterから二つのVPCEを使う（第一候補）

```mermaid
flowchart LR
  J["VPC内 JMeter"] -->|"HTTPS GetClientToken"| C["cognito-idp Interface VPCE"]
  C --> P["Cognito 専用M2Mプール"]
  P -->|"Access token"| J
  J -->|"HTTPS + Bearer"| E["execute-api Interface VPCE"]
  E --> A["Private REST API / scope認可"]
  A --> B["Lambda または閉域バックエンド"]
```

### 構成Aの経路を一つずつ確認

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 発行経路 | JMeter → VPC DNS → cognito-idp VPCEのENI → GetClientToken。応答にJWTとExpiresIn。 | ユーザープールドメインを経由しない。通常のリージョンAPI名をprivate DNSで解決する。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) |
| API経路 | JMeter → execute-api VPCE → Private REST API → バックエンド。 | 同じトークンを期限内で再利用し、対象メソッドでscopeを確認する。 | [S07](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-apis.html) [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) |
| バックエンド経路 | API Gateway → Lambda等。VPC Linkを使う場合は別の後段接続がある。 | Private API入口と、API GatewayからNLB等へのprivate integrationを混同しない。後段の外部通信も監査範囲なら別途確認。 | [S33](https://aws.amazon.com/blogs/compute/introducing-amazon-api-gateway-private-endpoints/) |
| NLBの必要性 | 同じVPC内のJMeterからexecute-api VPCEへ直接行けるなら、入口用NLBは必須ではない。 | NLBを含むSalesforce相当の経路を測りたいときだけ、その経路を通る試験を追加する。 | 設計提案 |

### 構成B：Salesforce側のNLB経路を想定した業務API接続

```mermaid
flowchart LR
  S["Salesforce / Private Connect outbound"] --> V["Salesforce管理VPCE"]
  V --> ES["利用者側Endpoint Service"]
  ES --> N["内部NLB"]
  N --> EV["execute-api VPCE / 必要なら中継"]
  EV --> A["Private REST API"]
  J["AWS内JMeter"] --> EV
  J --> CV["cognito-idp VPCE"]
  CV --> C["Cognito GetClientToken"]
```

### 構成Bで見落としやすい点

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 二種類のVPCE | Salesforceが利用者のEndpoint ServiceにつなぐVPCEと、AWS側のexecute-api VPCEは役割が違う。 | 「VPCE経由」とだけ記録せず、所有者・サービス名・ID・VPC・SGを記録する。 | [S25](https://aws.amazon.com/blogs/apn/private-integration-salesforce-and-amazon-api-gateway/) [S26](https://trailhead.salesforce.com/content/learn/modules/private-connect-outbound-connections/create-an-outbound-connection) |
| NLBの転送 | NLBはトークン取得リクエストを自動でCognitoへ分岐したり、フォームからJSONへ変換したりしない。 | L4の単純転送ならTLSのSNIとHTTP Hostが送り先に合う必要がある。HTTP Hostだけを書き換えて解決としない。 | 設計提案 [S08](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-api-test-invoke-url.html) |
| JMeter試験の代表性 | VPC内からの直接呼出はSalesforce管理網・Private Connect・NLBの負荷や遅延を含まない。 | ①API単体の閉域性能と、②Salesforce相当の入口経路の性能を別結果で示す。Salesforce内部処理時間はJMeterだけで再現できない。 | 設計提案 |
| トークン取得の独立性 | APIのNamed CredentialだけがPrivate Connectを使っても、OAuthサーバーへの通信まで同じ経路になる保証はない。 | 認証用コールアウト先・リダイレクト先・Named／External Credentialの適用範囲を実測する。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S25](https://aws.amazon.com/blogs/apn/private-integration-salesforce-and-amazon-api-gateway/) [S26](https://trailhead.salesforce.com/content/learn/modules/private-connect-outbound-connections/create-an-outbound-connection) |
| NLBのIPターゲット | NLBからexecute-api VPCEのENI IPへ転送する方式なら、対象ENIとターゲットの対応管理が必要になる。 | VPCE再作成・サブネット変更等でのターゲット更新とAZ障害を試験。Cognitoの公開ドメインのIPを固定登録して閉域化したことにしない。 | 設計提案 [S21](https://docs.aws.amazon.com/vpc/latest/privatelink/interface-endpoints.html) |
| NLBヘルスチェック | 認可が必要なPrivate APIへのHTTPチェックは、Hostや認可が足りず401／403となる場合がある。 | 健全性の確認方法を設計する。TCP成功は認可成功を意味しない。ヘルスチェックのために業務APIの認可を広く解除しない。 | 設計提案 |

### 構成C：SalesforceにAWS JSON形式を直接扱わせにくい場合の案

```mermaid
flowchart LR
  S["Salesforce"] -->|"Private Connect"| N["内部NLB / 閉域受入口"]
  N --> T["認証済みトークンブローカー"]
  T --> V["cognito-idp VPCE"]
  V --> C["Cognito GetClientToken"]
  C --> T
  T -->|"トークン / 有効期限"| S
```

### ブローカー案の成立条件

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 用途 | Salesforce標準OAuth機能でGetClientTokenのAWS JSON形式を扱えない場合に、内部サービスで変換する設計案。 | 標準OAuth互換のフォーム入力／応答を出すなら、CognitoそのもののOAuthエンドポイントとは別サービスだと明記する。 | 設計提案 |
| 入口の認証 | 券を発行する窓口が無認証だと、閉域内の誰でもCognitoの券を取得できる。 | mTLS・専用クライアント認証・接続元制約等を設計。業務API用JWTそのものを必須にすると初回取得が循環する。 | 設計提案 |
| 秘密とスコープ | Cognitoのsecretはブローカー内で管理する。外部入力のclient_id／scope／宛先URLを無制限に信用しない。 | 呼出元ごとに許可クライアントとscopeを固定。TLSを維持し、トークンをログへ出さない。 | 設計提案 |
| キャッシュと運用負担 | 共有キャッシュは発行回数を減らすが、secret・tokenを扱う新しい重要コンポーネントになる。 | 冗長化、更新の排他、キャッシュ分離、レート制限、監査、秘密の更新を用意。直接GetClientTokenを使える場合は追加しない。 | 設計提案 |

### 閉域で必要になる周辺接続

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 必須：cognito-idp | com.amazonaws.ap-northeast-1.cognito-idp（東京の例） | Interface VPCE。Private DNS、複数AZ、SGの443を検証する。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| 必須：execute-api | com.amazonaws.ap-northeast-1.execute-api（東京の例） | Private REST APIを利用。HTTP API用JWTオーソライザーとは別の製品・設定。 | [S07](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-apis.html) |
| 条件付き：Secrets Manager | シークレットを実行時に取得する場合。 | secretsmanager用VPCEと実行ロールのGetSecretValue権限。署名付き管理APIであり、GetClientTokenの無署名呼出と区別する。 | [S22](https://docs.aws.amazon.com/secretsmanager/latest/userguide/vpc-endpoint-overview.html) |
| 条件付き：STS／SSM／Logs／S3等 | AssumeRole、SSM管理、ログ送信、配布物取得などを行う場合。 | 利用する機能に対応した閉域経路を追加。全サービスを推測で追加せず、実際の通信依存を棚卸しする。 | 設計提案 |
| 条件付き：JWKS／OIDC | 自作Lambda／アプリがJWT検証のため公開鍵を取得する場合。 | GetClientTokenのPrivateLink対応だけからJWKSの閉域取得まで断定しない。初回取得・未知kid・鍵更新時の経路を個別検証する。 | [S15](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html) [S38](https://docs.aws.amazon.com/cognito/latest/developerguide/federation-endpoints.html) |
| 条件付き：オンプレDNSと回線 | 実行機がAWS外にある場合。 | Direct Connect等の実経路とResolver inboundを確認。通常のインターネットVPNは公衆網ゼロの条件を満たさない。 | [S23](https://docs.aws.amazon.com/directconnect/latest/UserGuide/Welcome.html) [S24](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/resolver-forwarding-inbound-queries.html) |
| 条件付き：パッケージ・時刻・証明書 | CLI／JDK／JMeter／SDKの配布、NTP、CA更新、証明書失効確認など。 | 実行時通信の境界を明示。閉域配布／社内ミラーを準備し、試験時に外へ取得しない。 | 設計提案 |


## 03 設定と移行

リージョンは未提示のためコードは東京 ap-northeast-1の例。ID・ARN・DNS・scopeは実環境の値へ置換する。以下の作成・更新コマンドは手順例であり、今回AWSへは実行していない。

### 実装前の確認順序

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 1.現状を取得 | User Pool ID、ドメイン、IssuerConfiguration、App Client、認証フロー、許可scope、API authorizer、VPCEを確認する。 | describe系で確認する際もClientSecretを出力しないqueryを使う。最新CLIの対応確認を先に行う。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S05](https://docs.aws.amazon.com/cli/latest/reference/cognito-idp/get-client-token.html) [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| 2.リージョンの対応 | GetClientTokenのモデル対応と、対象リージョン・プール構成での実行可否は別。 | describe-vpc-endpoint-servicesでcognito-idpの提供を確認。OperationNotEnabledExceptionならリージョン・複製先等を確認する。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S34](https://aws.amazon.com/about-aws/whats-new/2025/11/amazon-cognito-user-pools-private-connectivity-aws-privatelink/) |
| 3.プールの方針 | 閉域専用でドメインなしのプールを基準にする。既存のHosted UIやOAuth利用と分離する。 | 既存ドメイン削除を移行の第一手にしない。新プールならissuer／JWKS／API信頼先も変わる。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) [S38](https://docs.aws.amazon.com/cognito/latest/developerguide/federation-endpoints.html) 設計提案 |
| 4.リソースサーバー | ordersという識別子とreadというscopeを定義し、orders/readとして使用する。 | scope識別子は実URLでなくてもよい。APIのメソッド設定との完全一致を確認する。 | [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) |
| 5.専用クライアント | secretを持ち、ExplicitAuthFlowsはALLOW_CLIENT_TOKEN_AUTHのみとする。ユーザー用認証フローと混在させない。 | 従来のAllowedOAuthFlows=[client_credentials]だけではGetClientTokenの条件にならない。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) |
| 6.バージョンと設定文書 | 新しいM2M専用ガイドS04には専用フローとscopeの作成例がある。汎用CreateUserPoolClient文書S35の一部は新フローに追いついていない。 | M2M専用ガイドを基準にし、対応CLIで設定結果を照合。エラー時に旧フローへ黙って置き換えない。 | [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) [S35](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_CreateUserPoolClient.html) |
| 7.トークンVPCE | cognito-idp用Interface VPCEを用意。private DNSとVPCのDNS support／hostnamesを有効にする。 | SGは実行機のSG／必要CIDRから443のみ。複数AZを推奨。NACLの戻り通信も確認する。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) [S21](https://docs.aws.amazon.com/vpc/latest/privatelink/interface-endpoints.html) |
| 8.API VPCEとポリシー | execute-api用VPCE、APIのPrivate endpoint type、APIリソースポリシーを確認。 | BearerはVPCEポリシー上anonymousとして扱われる。PrincipalをIAMロールだけに絞ると誤拒否する。 | [S07](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-apis.html) [S09](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-vpc-endpoint-policies.html) [S11](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-resource-policies-examples.html) |
| 9.Cognito認可 | REST APIのCOGNITO_USER_POOLS authorizerを選び、メソッドのAuthorization Scopesにorders/readを設定する。 | スコープ未設定だとID token扱いになる。M2MでID tokenを取りに行く方向へ修正しない。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) |
| 10.反映と移行 | 新しいpool/clientを信頼するauthorizer設定と、変更が必要なAPIデプロイを検証環境で適用する。 | 既存scope・既存API利用の回帰確認。段階移行後に旧secret／旧経路を整理する。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) 設計提案 |

### 新規M2M用プール・クライアントのCLI設定例

```bash
# Linux/WSL表記の設定例。承認済み検証環境でのみ実行する。
# 既存ドメイン・既存クライアントを削除／更新しない新規構成。
export AWS_REGION=ap-northeast-1
POOL_ID=$(aws cognito-idp create-user-pool \
  --pool-name private-m2m-poc --region "$AWS_REGION" \
  --query UserPool.Id --output text)
aws cognito-idp create-resource-server \
  --user-pool-id "$POOL_ID" --identifier orders --name Orders \
  --scopes ScopeName=read,ScopeDescription=ReadOrders \
  --region "$AWS_REGION" --no-cli-pager
# S04のM2M専用設定例に従う。生成されたsecretを表示しない。
aws cognito-idp create-user-pool-client \
  --user-pool-id "$POOL_ID" --client-name private-jmeter \
  --generate-secret --explicit-auth-flows ALLOW_CLIENT_TOKEN_AUTH \
  --allowed-o-auth-scopes orders/read --region "$AWS_REGION" \
  --query 'UserPoolClient.{ClientId:ClientId,Flows:ExplicitAuthFlows,Scopes:AllowedOAuthScopes}' \
  --output json --no-cli-pager
# secretの安全な取得・登録はSecrets Manager等の既存手順で行う。
```

### ポリシー例の読み方

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| Cognito VPCE | 次のJSONはGetClientTokenだけを、対象プールのARNに限定する設計例。 | 匿名扱いのためPrincipalは*。client secretによる認証は別途必須。新アクションのresource適用は実VPCEで正負試験する。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) 設計提案 |
| execute-api VPCE | 次のJSONは対象API・stage・method・pathだけを許可する例。 | Principal=*でも無認証APIになるわけではない。Cognito authorizerとAPI側ポリシーが引き続き評価される。 | [S09](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-vpc-endpoint-policies.html) |
| APIリソースポリシー | 対象メソッドへの許可に加え、指定VPCE以外を明示Denyする例。 | IDを実値へ置換。複数VPCEから使う場合は許可一覧を設計する。APIの反映・デプロイを確認する。 | [S11](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-resource-policies-examples.html) |
| Cognito公開利用の制約 | VPCEポリシーはそのVPCEを通る通信だけを制御する。公開GetClientTokenを止めるものではない。 | 公開発行も禁止したい場合は、対象プール・対象アクションに絞ったRCP等を検討。全cognito-idp:*の一括Denyは管理や既存認証にも影響する。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |

### Cognito VPCEポリシー例

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "OnlyM2MTokenForApprovedPool",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "cognito-idp:GetClientToken",
      "Resource": "arn:aws:cognito-idp:ap-northeast-1:123456789012:userpool/ap-northeast-1_REPLACE"
    }
  ]
}
```

### execute-api VPCEポリシー例

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": "*",
      "Action": "execute-api:Invoke",
      "Resource": "arn:aws:execute-api:ap-northeast-1:123456789012:REPLACE_API_ID/test/GET/health"
    }
  ]
}
```

### APIリソースポリシー例

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": "*",
      "Action": "execute-api:Invoke",
      "Resource": "execute-api:/test/GET/health"
    },
    {
      "Effect": "Deny",
      "Principal": "*",
      "Action": "execute-api:Invoke",
      "Resource": "execute-api:/test/GET/health",
      "Condition": {
        "StringNotEquals": {
          "aws:SourceVpce": "vpce-REPLACE_EXECUTE_API"
        }
      }
    }
  ]
}
```

### 既存APIとの互換性

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| Issuerを変える必要はあるか | GetClientTokenのためにUPDATED issuerへ変更する必要性は確認できない。取得APIの追加とissuer更新を同時に行う理由はない。 | 現行issuerを維持して検証。新プールでは署名・iss・信頼先の変更として扱う。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S38](https://docs.aws.amazon.com/cognito/latest/developerguide/federation-endpoints.html) 設計提案 |
| audの正規表現 | REST API authorizerのID token用aud正規表現は、通常のM2M access tokenを拒否する原因になる。 | ID token用の設定をそのまま流用しない。access tokenのclient_idとscope等を必要な認可方式で評価する。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) [S14](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html) |
| 複数scopeの意味 | API Gatewayのメソッドに複数scopeを設定した場合は、いずれか一致で許可される。全scope必須というAND条件ではない。 | 全権限を同時に必須にする要件はLambda authorizer／バックエンド等で別に評価する。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) |
| client_idの制限 | 標準Cognito authorizerでscopeを確認するだけで、特定client_idだけを許可できたと決めない。 | 特定の発行元クライアント制限が必要なら専用scope・専用pool・追加認可を設計し、別クライアントの負試験を行う。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) [S14](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html) 設計提案 |


## 04 呼出方法

同じGetClientTokenを呼ぶ限り、認証の中身はClientId＋Secret。SDK、CLI、curl、PowerShell、JMeterで変わるのは呼出の書き方。閉域の成立条件はすべて共通。

### 呼び出し方の比較

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| Python SDK | boto3.client("cognito-idp").get_client_token(ClientId=..., Secret=..., Scopes=[...]) | botocoreモデルにGetClientTokenがあること。UNSIGNEDで資格情報の余計な探索を避ける。付属get_token_sdk.py。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S12](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-identity-provider_example_cognito-identity-provider_GetClientToken_section.html) |
| Java SDK v2 | CognitoIdentityProviderClient.getClientToken(GetClientTokenRequest.builder()...) | 対応SDKのBOMとHTTP実装を固定して配布。AnonymousCredentialsProviderでAWS IAM鍵を取得せずに呼ぶ。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S13](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/cognitoidentityprovider/model/GetClientTokenRequest.html) |
| AWS CLI v2 | aws cognito-idp get-client-token --client-id ... --secret ... --scopes orders/read | これは引数名の説明。実secretをコマンド引数へ書かず、保護したJSON入力／stdinを利用する。--no-sign-requestを付ける。 | [S05](https://docs.aws.amazon.com/cli/latest/reference/cognito-idp/get-client-token.html) |
| Bash＋CLI | JSONを標準入力で渡す。Unix側のfile:///dev/stdinを使う。 | Linux／WSL用。Windows PowerShellにはこのパスや行継続記号をそのまま転記しない。付属get_token_cli.sh。 | [S05](https://docs.aws.amazon.com/cli/latest/reference/cognito-idp/get-client-token.html) 設計例 |
| Bash＋curl | CognitoのAWS JSONプロトコルへPOST。フォーム形式ではない。 | CLIが未対応でもサービスAPIを直接呼べる。curl -kは使わない。付属get_token_curl.sh。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) 設計例 |
| PowerShell | .NET HttpClientでHTTPS POSTし、JSON応答を取得する。 | 今回確認したWindows PowerShell 5.1で解析可能な例。secretをプロセス引数へ渡さない。付属get_token.ps1。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) 設計例 |
| JMeter標準HTTP Request | POST /、application/x-amz-json-1.1、X-Amz-Target、JSON本文。 | JSON Extractorのパスは$.ClientAuthenticationResult.AccessToken。TLSと秘密の記録設定に注意。後述の厳格TLS方式を配布例に採用。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S27](https://jmeter.apache.org/usermanual/component_reference.html) [S28](https://jmeter.apache.org/usermanual/get-started.html) |
| JMeter JSR223 | GroovyからJava標準HttpClientを利用し、GetClientTokenとAPIを呼ぶ。 | Java 11以上。配布JMXはTLSの証明書チェーンとホスト名を検証し、応答からtokenをログに残さず取り出す。 | [S27](https://jmeter.apache.org/usermanual/component_reference.html) 設計例 |
| 従来のOAuth／curl | POST https://<domain>/oauth2/token、grant_type=client_credentials、フォーム本文。 | 比較のための従来方式。公開経路が必要なので厳密な閉域要件の採用案にはしない。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) [S06](https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html) |
| InitiateAuth／AdminInitiateAuth | ユーザー認証のAPI。M2M用GetClientTokenの代用品ではない。 | ユーザー用パスワード認証に置き換えない。GetTokensFromRefreshTokenもM2M初回発行ではない。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) |

### SDK・CLI・JMeterに共通するHTTP契約

```http
POST / HTTP/1.1
Host: cognito-idp.ap-northeast-1.amazonaws.com
Content-Type: application/x-amz-json-1.1
X-Amz-Target: AWSCognitoIdentityProviderService.GetClientToken

{"ClientId":"REPLACE_CLIENT_ID","Secret":"REPLACE_WITH_SECURE_SECRET","Scopes":["orders/read"]}

# 応答例（値はダミー。UserPoolIdパラメーターは不要）
{"ClientAuthenticationResult":{"AccessToken":"REDACTED","ExpiresIn":3600,"TokenType":"Bearer"}}
```

### 取り違え防止

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 入力名 | GetClientTokenはSecret。ClientSecretやSECRET_HASHではない。Scopesは配列。 | grant_typeやUserPoolIdは不要。JSONはライブラリで生成し、文字列連結でsecretを埋め込まない。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) |
| 応答名 | GetClientTokenはClientAuthenticationResult.AccessTokenとExpiresIn。 | OAuthのaccess_token／expires_inや、InitiateAuthのAuthenticationResultと区別する。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S06](https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html) |
| scopeを省略したとき | クライアントに設定済みのscopeが許可対象となる。 | 意図せず広い権限を求めないよう、運用例では必要scopeを明示する。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) |
| IAMの不要範囲 | GetClientTokenではidentity-based IAM policyを使って認証しない。client secretが必要。 | 設定API・EC2照会・Secrets Manager等のIAM権限まで不要になるわけではない。VPCEポリシー／RCPも別に効く。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| SDKの対応確認 | 公式資料に新APIがあっても、実行機のローカルモデルにはない場合がある。 | CLIはget-client-token --generate-cli-skeleton inputで通信せず確認。Pythonはoperation_namesで確認する。 | [S05](https://docs.aws.amazon.com/cli/latest/reference/cognito-idp/get-client-token.html) 設計例 |
| このPCでの結果 | aws-cli/2.28.21ではget-client-tokenがInvalid choiceとなった。GetClientToken API自体の不在を意味しない。 | 実環境への呼出前にCLIを承認済み版へ更新。資料のPython／Javaも依存バージョンと対応モデルを導入先で確認する。 | ローカル確認 |
| Private DNSを使う場合 | 通常のcognito-idp.<region>.amazonaws.comを指定したままでよい。 | 実行機のDNS解決先が対象VPCEのENI IPであることを確かめる。FIPS／dual-stack／他リージョン名への自動切替も確認する。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) |
| Private DNSを使わない場合 | 対象VPCEが返すDnsEntriesの正確なDNS名を使う。 | CLIは--endpoint-url、Pythonはendpoint_url。VPCE IDからDNS名を推測して組み立てない。TLSホスト名も一致させる。 | [S05](https://docs.aws.amazon.com/cli/latest/reference/cognito-idp/get-client-token.html) [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) |

### Private REST APIの呼出URL

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| Private DNS有効 | https://REPLACE_API_ID.execute-api.ap-northeast-1.amazonaws.com/test/health | 通常はAPI識別のためのHost上書き／x-apigw-api-idは不要。Authorization: Bearerは別途必要。 | [S07](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-apis.html) [S08](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-api-test-invoke-url.html) |
| VPCE固有DNS名 | https://<DnsEntriesで取得したexecute-api用のVPCE DNS名>/test/health | x-apigw-api-id: REPLACE_API_IDを追加する方式を配布JMXで利用できる。API GatewayがどのAPIかを識別するためのヘッダー。 | [S08](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-api-test-invoke-url.html) |
| Private custom domain | https://<構成済みの専用ドメイン>/health 等 | 証明書、API mapping、ドメイン用resource policy、VPCEとのdomain name access association、DNSをそろえる。任意の名前を付けるだけでは動かない。 | [S08](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-api-test-invoke-url.html) 設計整理 |
| Private DNSの影響範囲 | execute-apiのprivate DNSを有効にすると、同じVPCから公開APIの標準execute-apiエンドポイントへ接続できなくなる場合がある。 | 他システムへの影響を確認。必要ならAPIごとのprivate hosted zoneやVPCE固有名を使い分ける。 | [S07](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-apis.html) |

### Windows CLIでの取得例

```powershell
# Windows PowerShell。token-request.jsonは事前にACLを限定して安全に作成する。
# ファイルにはClientId / Secret / Scopesを設定。Gitや共有フォルダへ置かない。
$token = aws cognito-idp get-client-token `
  --region ap-northeast-1 --no-sign-request --no-cli-pager `
  --cli-input-json file://token-request.json `
  --cli-connect-timeout 5 --cli-read-timeout 15 `
  --query ClientAuthenticationResult.AccessToken --output text
if ($LASTEXITCODE -ne 0) { throw 'GetClientToken failed' }
if ([string]::IsNullOrWhiteSpace($token) -or $token -eq 'None') { throw 'No token' }
# $tokenを表示せず、Authorizationヘッダーへ渡す。
# 秘密の平文ファイルを使いたくない場合は付属get_token.ps1を使う。
```

### 従来OAuth方式の比較用例（閉域案では使用しない）

```http
# 公開のCognitoドメインが必要。実secretを引数へ直書きしない。
# HTTP要求の形を説明する例であり、このURLはVPCEへ変更できない。
POST https://REPLACE_DOMAIN.auth.ap-northeast-1.amazoncognito.com/oauth2/token
Content-Type: application/x-www-form-urlencoded
Authorization: Basic BASE64_CLIENT_ID_COLON_SECRET

grant_type=client_credentials&scope=orders%2Fread
```


## 05 実装コード

コード例は実secretを含まない。環境変数は安全な実行基盤から注入する前提。実行前に承認済みのendpointを設定し、DNS・ルート・SGを確認する。HTTP例は各試行1要求、Python SDK例は最大3試行。Java抜粋は全体30秒の上限を示す。

### Python SDKと期限付きキャッシュ：get_token_sdk.py

```python
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
```

### Python標準HTTPによる直接呼出：get_token_http.py

```python
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
```

### BashとAWS CLI：get_token_cli.sh

```bash
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
```

### Bashとcurl：get_token_curl.sh

```bash
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
```

### Windows PowerShellによる直接呼出：get_token.ps1

```powershell
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
```

### Java SDK v2の抜粋：get_token_java.java

```java
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
```


## 06 JMeter手順

配布するprivate-m2m.jmxは標準プラグインだけを使うJSR223方式。Java 11以上が必要。この環境ではJava 17を確認。GUIは編集用、本番負荷はCLIで実行する。

### 手順と試験設計

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 1.配布物をそろえる | private-m2m.jmx、jmeter_check.groovy、jmeter_token.groovy、jmeter_api.groovy、jmeter-private.propertiesを同じ作業ディレクトリに置く。 | 実行時のカレントディレクトリもその場所にする。分散実行では全workerへ同じファイルを配る。 | 設計例 [S27](https://jmeter.apache.org/usermanual/component_reference.html) [S28](https://jmeter.apache.org/usermanual/get-started.html) |
| 2.秘密を注入 | AWS_REGION、COGNITO_CLIENT_ID、COGNITO_CLIENT_SECRET、COGNITO_SCOPESをworkerのプロセス環境へ設定。 | secretやtokenを-J/-Gに渡さない。-Gは環境変数を配布しない。ローカル環境変数はリモートworkerへ自動継承されない。 | 設計例 [S28](https://jmeter.apache.org/usermanual/get-started.html) |
| 3.宛先を設定 | propertiesのapi_hostとapi_pathをPrivate REST APIへ変更。必要ならapi_idとcognito_hostも設定する。 | api_hostにhttps://やパスを混ぜない。API名のprivate DNS、VPCE名＋x-apigw-api-id、または正しく構成したprivate custom domainを使う。 | [S08](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-api-test-invoke-url.html) 設計例 |
| 4.少数で起動 | threads=1、loops=1でtoken取得とAPIが1回ずつ成功することを確認する。 | APIは認可付きのGET /test/healthを例示。実環境の安全な検証用メソッドへ置換。実行機の出口を遮断した条件でも確認する。 | 設計提案 |
| 5.期限内の再利用 | Token cache checkは通信せず、varsに保持したtokenと単調時計を確認する。取得が必要なときだけIf Controllerが動く。 | 有効期間の75～80%で更新し、各threadの更新にゆらぎを入れる。各threadのvarsは他threadと共有しない。 | 設計例 [S16](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-caching-tokens.html) |
| 6.測定を分ける | Token_GetClientTokenとAPI_Privateを別のsamplerラベルで記録する。キャッシュ確認処理は結果集計から除外する。 | APIのp95／p99と、トークン発行の件数・遅延・失敗を分けて報告。初回のTLS／JITウォームアップも区別する。 | 設計例 [S27](https://jmeter.apache.org/usermanual/component_reference.html) [S29](https://jmeter.apache.org/usermanual/best-practices.html) |
| JSR223の測定範囲 | 配布例のelapsedはスクリプト処理とHTTP完了までの時間。標準HTTP samplerの接続時間・Latency・送受信バイト数は同じ精度で記録しない。 | JTLのbytesは抑止メッセージ由来で、実通信量ではない。応答本文は捨てるため業務内容のassertionは別途追加する。API status成功だけで業務正当性まで保証しない。 | 設計例 |
| 7.エラー時 | 発行に失敗したthreadは停止する。401後のAPI要求を自動で再送しない。 | 業務の書込要求は二重実行に注意。負荷試験でエラーを隠す再試行を勝手に追加しない。必要なら別シナリオにする。 | 設計例 |
| 8.負荷を増やす | 少数から段階的に増やし、負荷生成機のCPU・メモリ・ネットワークとCognito／APIの制限を監視する。 | 配布例のthread単位cacheではthreads分の初回発行がある。ramp-upを十分に取り、開始時集中を避ける。 | [S17](https://docs.aws.amazon.com/cognito/latest/developerguide/quotas.html) [S29](https://jmeter.apache.org/usermanual/best-practices.html) 設計提案 |
| 9.長時間試験 | token寿命を超えて継続し、2回目以降の取得と認可が成功するか確認する。 | 数分の試験だけでは更新処理を検証できない。期限・時刻ずれ・更新時障害を別々に試す。 | 設計提案 |
| 10.分散実行 | 各workerがprivate DNSと両VPCEへ到達できる必要がある。JMeter controllerだけの疎通では不足。 | RMIの暗号化と必要ポートを設計。各workerのキャッシュとsecretを分離。propsも通常はJVM単位であり全worker共有ではない。 | [S28](https://jmeter.apache.org/usermanual/get-started.html) [S30](https://jmeter.apache.org/usermanual/properties_reference.html) |

### Windowsからの起動例（秘密は安全に注入済み）

```powershell
# examplesディレクトリをカレントにする。jmeter.batは実インストール先へ置換。
& 'C:\tools\apache-jmeter\bin\jmeter.bat' -n `
  -t .\private-m2m.jmx -q .\jmeter-private.properties `
  -l .\result.jtl -j .\jmeter.log
if ($LASTEXITCODE -ne 0) { throw 'JMeter process failed' }
# プロセス終了コード0だけではsample成功を意味しない。
# result.jtlのsuccess列、error率、responseCodeも確認する。
```

### Linuxからの起動例

```bash
# examplesディレクトリで実行。実secretをexportコマンド履歴へ書かない。
jmeter -n -t private-m2m.jmx -q jmeter-private.properties \
  -l result.jtl -j jmeter.log
# 大量負荷時にView Results Treeや応答本文の記録を有効にしない。
```

### 標準HTTP Requestでも取得する場合の具体的設定

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| Protocol / Server / Port / Path | https / cognito-idp.ap-northeast-1.amazonaws.com / 443 / / | private DNSでCognito VPCEへ向くことをworkerから確認。Follow Redirectsと自動redirectは無効。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S27](https://jmeter.apache.org/usermanual/component_reference.html) |
| Methodと本文 | POST。Body DataにJsonOutput等で生成したClientId / Secret / ScopesのJSONを置く。 | Parametersタブのフォーム送信にしない。secretをJMXへ保存せず、JSR223でSystem.getenvから読みJSON文字列を作る。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S27](https://jmeter.apache.org/usermanual/component_reference.html) |
| Header Manager | Content-Type: application/x-amz-json-1.1、X-Amz-Target: AWSCognitoIdentityProviderService.GetClientToken | GetClientTokenにBasicやSigV4ヘッダーは不要。業務API用Bearerをトークンsamplerへ全体適用しない。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) |
| JSON Extractor | access_token = $.ClientAuthenticationResult.AccessToken、ttl = $.ClientAuthenticationResult.ExpiresIn | Match No.=1、Default=__MISSING__。HTTP 200だけで成功とせず、tokenとTTLの存在もassertする。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S27](https://jmeter.apache.org/usermanual/component_reference.html) |
| API用Header Manager | Authorization: Bearer ${access_token}。必要な場合だけx-apigw-api-idを設定。 | AuthorizationはAPI sampler配下に置く。トークン取得とAPIのヘッダーを混在させない。 | [S08](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-api-test-invoke-url.html) [S27](https://jmeter.apache.org/usermanual/component_reference.html) |
| 試験の継続 | Once Only Controllerだけなら、長時間試験でtokenが失効する。 | 各ループで更新時刻を判定してIf Controllerで再取得。JSON抽出失敗時は古いtokenを残さずthreadを停止する。 | 設計例 [S27](https://jmeter.apache.org/usermanual/component_reference.html) |
| TLSの注意 | 公式説明では標準HTTP samplerは有効性を含め広く証明書を受け入れる。成功だけではTLS相手の正当性の証拠にならない。 | 配布例は別途初期化したJava標準SSLContextとHTTPSホスト名検証を使用。JMeterのtruststore設定だけで標準samplerの厳格検証を保証したと書かない。 | [S28](https://jmeter.apache.org/usermanual/get-started.html) 設計例 |

### 配布JMXの構造

```text
Test Plan
  Thread Group（on_sample_error = stopthread）
    JSR223: Token cache check（通信なし、結果非集計）
    If Controller: ${need_token}
      JSR223: Token_GetClientToken（TLS検証あり、token応答は非記録）
    JSR223: API_Private（Bearer、TLS検証あり、応答本文は非記録）
```

### jmeter_check.groovy

```groovy
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
```

### jmeter_token.groovy

```groovy
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
```

### jmeter_api.groovy

```groovy
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
```

### jmeter-private.properties

```properties
# Inject secrets through the process environment, never through -J or this file.
threads=1
loops=1
ramp_seconds=1
api_host=REPLACE_API_ID.execute-api.ap-northeast-1.amazonaws.com
api_path=/test/health
# For a VPCE-specific hostname, set api_id to the REST API ID. Otherwise leave empty.
api_id=
# Empty means cognito-idp.${AWS_REGION}.amazonaws.com, resolved through private DNS.
cognito_host=
jmeter.save.saveservice.output_format=csv
jmeter.save.saveservice.response_data=false
jmeter.save.saveservice.response_data.on_error=false
jmeter.save.saveservice.samplerData=false
jmeter.save.saveservice.requestHeaders=false
jmeter.save.saveservice.responseHeaders=false
jmeter.save.saveservice.url=false
httpsampler.max_redirects=0
```


## 07 運用と検証

APIに到達できること、正しい権限だけが通ること、通信が閉域に収まることを別々に検証する。DNSがprivate IPだったという一点だけで完了にしない。

### 推奨運用

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| secretの管理 | Secrets Manager等に保存し、実行ロールには対象secretの取得だけを許可する。 | 環境変数も同権限のプロセスや管理者から見られ得る。検証時の簡便な注入例であり、完全な秘匿機構ではない。 | [S22](https://docs.aws.amazon.com/secretsmanager/latest/userguide/vpc-endpoint-overview.html) 設計提案 |
| secretの更新 | 最新のAPIには追加secretを使うローテーション経路がある。旧secretと新secretを段階的に切り替える。 | 対応機能・最大数を実環境で確認。新secretで発行を確認後に旧secretを廃止。既に発行済みJWTの即時無効化とは分けて扱う。 | [S36](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_AddUserPoolClientSecret.html) [S15](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html) |
| tokenの再利用 | 期限内で再利用し、更新を早めに行う。キャッシュキーはpool／client／scope集合／必要なmetadataで分離する。 | 利用者や権限が異なるtokenを共有しない。全thread共有の場合はロック等で更新集中を防ぐ。配布JMXはthreadごとの単純な例。 | [S16](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-caching-tokens.html) 設計提案 |
| 有効期限 | access tokenの一般的な設定範囲は5分～1日。実際は発行応答のExpiresInを読む。 | 長くすると発行負荷が減る一方、盗難tokenが使える時間が延びる。原則短めから、要件と測定結果で決める。 | [S14](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html) |
| M2Mの更新 | refresh tokenで更新する方式ではなく、client secretでGetClientTokenを再実行する。 | 再取得失敗時は有効期限を過ぎたtokenを使わない。401でsecretが間違っていると即断せず、issuer・scope等も確認する。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S06](https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html) |
| 再試行 | タイムアウト・5xx・スロットリング等は上限付き指数バックオフとゆらぎ。設定不正やsecret不正を延々再試行しない。 | GetClientTokenのTooManyRequestsExceptionはHTTP 400として定義される。429だけを見る実装にしない。取得再試行でtoken発行回数が増える可能性を見込む。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) |
| 時刻 | JWTのexp等はサーバー時刻に関係する。キャッシュ経過時間には単調時計を使う。 | workerのNTPを閉域で維持し、時計の進み・遅れを試験。配布例はtime.monotonic／System.nanoTimeで更新判定する。 | [S14](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html) 設計例 |
| ログ | token・secret・Authorization・リクエスト本文・認証応答は記録しない。 | 件数、HTTP status、例外分類、レイテンシ、対象環境の識別子などを記録。AWS CLI --debug、JMeter Debug Sampler、PowerShell transcriptも見直す。 | 設計提案 |
| JWT検証 | 署名・iss・exp・token_use=access・必要scopeを確認。必要な場合client_id等も追加で照合する。 | 通常のM2M tokenのsubを人間ユーザーIDだと仮定しない。鍵のkid変更と未知鍵時の動作を試験する。 | [S14](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html) [S15](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html) |
| JWKSと管理面 | ネイティブCognito authorizerでは、利用者VPCのJMeterがAPI呼出ごとにCognitoへ問い合わせる設計にはしない。 | AWS管理authorizerの内部通信が利用者VPCEを通ると主張しない。自作検証器のJWKS取得は別経路として検証する。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) [S15](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html) 設計整理 |
| 障害切分け | DNS→TCP→TLS→Cognito発行→VPCE policy→API policy→authorizer→backendの順に切り分ける。 | エラーstatusだけで原因を一意に断定しない。request ID、実行時刻、VPCE ID、認可設定を照合し、secretをログに追加しない。 | 設計提案 |

### 受入試験（期待結果と証跡）

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| T01：モデル対応 | CLIのgenerate-cli-skeleton inputが成功／SDKにGetClientTokenがある。 | 実行機ごとの版と出力を保存。通信不要。今回のCLI 2.28.21は未対応だった。 | [S05](https://docs.aws.amazon.com/cli/latest/reference/cognito-idp/get-client-token.html) ローカル確認 |
| T02：Cognito DNS | cognito-idpのA/AAAAが対象VPCEのENIへ向く。 | Resolve-DnsNameとEC2のNetworkInterfaceIds／PrivateIpAddressを照合。IPv6やproxyも確認。 | [S20](https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html) |
| T03：API DNS | api-id.execute-api等が想定VPCEへ向く。 | workerごとに解決。通常名／VPCE名／private custom domainの選択とHost条件を記録。 | [S08](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-api-test-invoke-url.html) |
| T04：公開出口遮断 | 専用の検証サブネットでNAT／IGW／外部proxyへの経路を使えなくしても両要求が成功。 | 本番共有ルートを無断変更せず、最初からno-egressの検証サブネットを使う。ルート表・SG・Flow Logsを保存。 | 設計提案 |
| T05：発行正常 | 正しいclient＋secret＋scopeでtokenを取得。 | 200、TokenType、ExpiresInを確認。token値は証跡に含めない。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) |
| T06：secret不正 | 不正secretでは発行できない。 | GetClientTokenのNotAuthorizedExceptionはHTTP 400。OAuthのinvalid_clientと形式が違う。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) |
| T07：flow不正 | ALLOW_CLIENT_TOKEN_AUTH未設定や混在設定を検出する。 | Cognitoの設定検証／発行失敗を記録。user-password方式へ自動フォールバックしない。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) |
| T08：scope不正 | 未許可scopeを要求した際に権限の広いtokenが発行されない。 | 入力・クライアント許可・応答scopeを照合。発行エラーの型とstatusを実測する。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) |
| T09：ドメイン共存 | 既存ドメインありプールを再利用する案ではVPCEからの発行可否を実測。 | S03の不整合が残るため、成功しなければ閉域専用プールへ切り替える。既存ドメイン削除を試験手順に含めない。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| T10：API正常 | 発行tokenでscope付きメソッドが成功。 | デプロイ済みstageへHTTPS呼出。authorizerのコンソールTest機能だけでM2Mの成功判定をしない。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) |
| T11：期限切れ | 失効tokenでは認可されない。 | 通常401を想定。実応答とauthorizerログで確認し、再取得処理も別に検証。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) [S14](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html) |
| T12：scope不足 | 要求scopeを持たないtokenが対象メソッドを通らない。 | Cognito authorizerのscope不一致は公式例では401。リソース／VPCE policy拒否の403等と混同しない。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) [S09](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-vpc-endpoint-policies.html) |
| T13：pool／clientの違い | 信頼外poolを拒否。特定client制限が必要なら他clientも拒否。 | scopeが同じ他clientを通してしまわないか、要件に合わせて確認する。 | [S10](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html) [S14](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html) 設計提案 |
| T14：VPCE policy | 許可したAPI／poolだけ通り、許可しない資源・操作は通らない。 | Principal=*の意味を誤解してIAMロール限定へ書き換えない。GetClientTokenのresource制限も実測。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) [S09](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-vpc-endpoint-policies.html) |
| T15：別VPCE | API側のSourceVpce許可外からは拒否される。 | 異なる認証と異なるネットワークの負試験を分け、拒否理由を特定する。 | [S11](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-resource-policies-examples.html) |
| T16：公開からの発行 | 公開経路での発行禁止も要件なら、VPCEを通らないGetClientTokenを拒否できる。 | VPCEを作っただけの構成では禁止されない。対象RCP等を適用した検証環境で確認する。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| T17：TLS | 未信頼CA・期限切れ・ホスト名不一致の相手を拒否。 | IP直指定＋Host書換えやtrust-allで試験を通さない。配布JMXのstrict HTTP方式も負試験する。 | [S28](https://jmeter.apache.org/usermanual/get-started.html) 設計例 |
| T18：期限超過試験 | token寿命を超えてAPIを呼び続けても適切に更新。 | 取得回数が期待値に近く、旧tokenを期限後に使わない。 | [S16](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-caching-tokens.html) 設計例 |
| T19：集中・上限 | 起動集中と同時更新で過剰発行を起こさず、スロットリングを認識できる。 | ramp-up、cache粒度、retry設定、実クォータを保存。 | [S17](https://docs.aws.amazon.com/cognito/latest/developerguide/quotas.html) 設計提案 |
| T20：secret更新 | 新secretで発行成功し、旧secret廃止後に旧secretで発行できない。 | 既発行tokenの残存期間とAPI側の緊急遮断方法も確認。 | [S36](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_AddUserPoolClientSecret.html) 設計提案 |
| T21：鍵更新 | 自作JWT検証器は未知kid・JWKS更新に安全に対応する。 | 鍵が取得できない場合に署名検証をスキップしない。公開鍵配布の閉域性・更新運用を確認。 | [S15](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html) |
| T22：Salesforce | 認証先と業務APIの両方がPrivate Connect等の意図した経路を使う。 | Named／External Credential設定、接続状態、認証更新・リダイレクト、NLB・VPCE経路を記録する。 | [S25](https://aws.amazon.com/blogs/apn/private-integration-salesforce-and-amazon-api-gateway/) [S26](https://trailhead.salesforce.com/content/learn/modules/private-connect-outbound-connections/create-an-outbound-connection) 設計提案 |
| T23：分散worker | 全workerが秘密・TLS・DNS・VPCE条件を満たす。 | controllerだけでなく全workerの宛先とログを確認。secretを-Gで配らない。 | [S28](https://jmeter.apache.org/usermanual/get-started.html) |
| T24：秘密の非記録 | JTL／JMeter log／実行ログ／レポートにtoken・secretが残らない。 | ダミーsecretで検索して確認。本番secretの文字列を検索コマンド引数に渡さない。 | 設計提案 |

### Windowsの読み取り確認コマンド例

```powershell
# 通信を実行するのは利用者の承認済み環境。値は置換する。
Resolve-DnsName cognito-idp.ap-northeast-1.amazonaws.com
Resolve-DnsName REPLACE_API_ID.execute-api.ap-northeast-1.amazonaws.com
Test-NetConnection cognito-idp.ap-northeast-1.amazonaws.com -Port 443
aws ec2 describe-vpc-endpoints --region ap-northeast-1 --vpc-endpoint-ids vpce-REPLACE --query 'VpcEndpoints[].{Id:VpcEndpointId,Service:ServiceName,PrivateDNS:PrivateDnsEnabled,DNS:DnsEntries,ENI:NetworkInterfaceIds}'
aws cognito-idp describe-user-pool --region ap-northeast-1 --user-pool-id ap-northeast-1_REPLACE --query 'UserPool.{Id:Id,Domain:Domain,CustomDomain:CustomDomain,Issuer:IssuerConfiguration}'
aws cognito-idp describe-user-pool-client --region ap-northeast-1 --user-pool-id ap-northeast-1_REPLACE --client-id REPLACE --query 'UserPoolClient.{Id:ClientId,Flows:ExplicitAuthFlows,OAuth:AllowedOAuthFlows,Scopes:AllowedOAuthScopes}'
# Test-NetConnectionの成功はTCP疎通のみ。TLS・scope・閉域経路の証明ではない。
```


## 08 負荷と費用

Excelでは入力を変えると発行件数の概算を再計算する。以下は試算例であり利用者の実測値ではない。金額単価はリージョン・契約・段階料金が未確認のため空欄。

### 発行負荷と料金の考え方

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 毎回取得する場合 | API 100回/秒 × 3,600秒 = 360,000回のtoken取得。 | 業務API性能にtoken発行の遅延・上限・料金が混ざる。M2M発行性能試験として意図的に行う場合を除き避ける。 | 試算例 |
| threadごとに再利用 | 100 cache group、TTL=3,600秒、更新率75%なら更新間隔2,700秒。1時間に各group最大2回、合計200回を概算。 | API呼出360,000回に対し約99.944%削減。全groupが試験開始から継続稼働する前提。再試行や再起動による増分は含まない。 | 試算例 |
| 共有cacheの場合 | cache group数を減らすほど発行回数を減らせる。共有の可否は同じclientと同じscopeかで判断。 | clientや権限が異なるtokenを共有しない。workerをまたぐ共有には別の安全な実装が必要。 | 設計提案 |
| クォータ | 公式クォータ表のClientAuthenticationにはclient_credentialsとGetClientTokenが含まれ、取得時点では150 RPSの記載。 | 口座・リージョンの実適用値、共有される範囲、引上げ可否を運用時に再確認。150を1client専用の保証値として扱わない。 | [S17](https://docs.aws.amazon.com/cognito/latest/developerguide/quotas.html) |
| M2M料金の最新確認 | 調査時点の料金ページは成功token応答に応じた月額課金、登録app client数への追加料金なしと説明。 | 開発ガイドにはactive client数にも触れる記述が残る。金額判断は公式料金ページ・請求条件を優先し、旧固定費を無条件に加えない。 | [S18](https://aws.amazon.com/cognito/pricing/) [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) |
| PrivateLink料金 | Interface VPCEはAZごとの時間と処理データ等に応じて料金が発生する。 | Cognito用とexecute-api用の両方、必要AZ数、NLB・Private Connect・API・Logs等の追加費用を含める。 | [S19](https://aws.amazon.com/privatelink/pricing/) |
| 概算式の限界 | 一定のAPI負荷、一定TTL、等しい更新率、試験開始時の初回発行を仮定する。 | 75～80%のゆらぎ・停止時間・cache miss・retry・engine再起動・分散共有の詳細は実測に置き換える。 | 試算例 |

### Excelの入力と式

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| API呼出率 | 100 RPS（例） | 入力。API総数＝RPS×試験秒数。 | 試算例 |
| 試験時間 | 3,600秒（例） | 入力。試験区間は開始以上・終了未満で数える。 | 試算例 |
| cache group数 | 100（例） | 入力。配布JMXでは同時稼働thread数に相当する。 | 試算例 |
| token TTL | 3,600秒（例） | 入力。実際のExpiresInに合わせる。 | 試算例 |
| 更新率 | 75%（例） | 入力。更新間隔＝TTL×更新率。0より大きく1未満。 | 試算例 |
| cache使用時発行数 | group数×ROUNDUP(試験秒数÷更新間隔,0) | 試験中ずっと各groupがtokenを必要とする前提。実装が停止中なら少なくなる。 | 試算例 |
| 金額 | 成功token数を月間段階料金へ当てはめ、VPCE／NLB等を加算。 | 単価は未入力。単一単価の単純乗算は該当tier内の参考値に限る。 | [S18](https://aws.amazon.com/cognito/pricing/) [S19](https://aws.amazon.com/privatelink/pricing/) |


## 09 根拠と未確定

公式一次資料を2026-10-18に確認。検索結果の抜粋が古い場合があるため、重要箇所はページ本文も直接取得して確認した。環境固有の値や未検証事項を下記に分離する。

### 未提示の前提と採用方針

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| リージョン | 未提示。東京リージョンをコード例に採用。 | 本番適用前に対象リージョンのGetClientToken／PrivateLink提供を確認。例のリージョン文字列だけを変えて完了としない。 | 利用者確認待ち |
| Cognitoドメイン | 有無と利用者が未確認。既存M2MがOAuthドメインを使う可能性がある。 | 既存プールは維持し、閉域専用のドメインなしプールでPoCを行う案を提示。 | 設計上の仮定 |
| APIの認可方式 | COGNITO_USER_POOLSかLambda authorizerか未確認。 | 標準Cognito authorizerの具体手順を記載。LambdaならJWKS・issuer・client制限・キャッシュを追加確認。 | 利用者確認待ち |
| NLB／VPCEの配置 | NLBからVPCEを経由するという情報以外は未確認。 | 現在の構成図として断定せず、Salesforce経路の想定図として提示する。 | 利用者確認待ち |
| Salesforce Private Connect | 利用有無・契約・接続リージョン・認証先の経路が未確認。 | Salesforce本体の閉域要件にはPrivate Connect等の別確認が必要。AWS内JMeterだけの成功をSalesforce全体の保証にしない。 | 利用者確認待ち |
| 実績値と価格 | RPS、同時数、TTL、月間発行数、AZ数、料金単価は未提示。 | Excelの負荷数は例示。単価は空欄のまま入力欄を用意し、実額を捏造しない。 | 試算上の仮定 |

### 公式文書に差がある箇所

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| ドメインとPrivateLink | S03の表注記は「domainありpoolは非対応」、運用例もdomain削除を示す。一方、後半にはSDK API全般の対応を示す一般記述がある。 | domainなし構成を安全な共通条件として採用。既存domain付きpoolのGetClientToken共存は未確認としてPoC／AWS問い合わせ事項に残す。 | [S03](https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html) |
| 新しいclient flow | S04・S02はALLOW_CLIENT_TOKEN_AUTHを明示。S35の汎用設定説明は新フローと整合しない箇所がある。 | 新M2M専用説明を基準にし、最新モデルと実サービスで確認する。古いSDKのエラーとサービス側の制約を分ける。 | [S02](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html) [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) [S35](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_CreateUserPoolClient.html) |
| 料金体系 | S04のactive M2M client記述と、S18の登録client数への追加料金なしという料金説明に差がある。 | 現在の金額判断ではS18とアカウントの課金条件を優先。旧料金を固定値としてExcelに組み込まない。 | [S04](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html) [S18](https://aws.amazon.com/cognito/pricing/) |
| Issuer互換性の古い抜粋 | 検索抜粋にはUPDATED issuerとAPI Gatewayの非互換が残るが、直接取得したS38本文の非互換注記はALB authenticate-cognitoについて記載していた。 | 古い抜粋だけでAPI Gateway全般を非対応としない。今回issuer変更は行わず、実tokenと対象authorizerで互換性を検証する。 | [S38](https://docs.aws.amazon.com/cognito/latest/developerguide/federation-endpoints.html) |

### 実施済み確認と未実施の範囲

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| 公式資料 | GetClientToken公開日、API契約、PrivateLink、Private API、Salesforce、JMeter仕様の一次資料を取得。 | 取得日時と出典URLを本資料に記載。サービス仕様は導入時にも再確認する。 | 調査実施済み |
| ローカルCLI | Windows PowerShell 5.1、AWS CLI 2.28.21、Java 17.0.11を確認。CLIは新APIに未対応。 | AWSの認証情報や実secretは読み出していない。管理リソースの作成・更新・削除は未実施。 | ローカル確認 |
| 実環境 | AWS VPCE経由のtoken取得、Private API認可、Salesforce接続の実試験は未実施。 | 対象環境の情報と承認済み試験機が必要。ローカル模擬通信の成功をAWS実環境の成功として報告しない。 | 未検証 |

### 成果物の検証結果

| 項目 | 意味・判断根拠 | 設定・運用への反映 | 根拠 |
| --- | --- | --- | --- |
| JMeter実行環境 | 公式配布のJMeter 5.6.3をSHA-512確認後、Java 17でローカルHTTPS模擬サーバーへ実行した。 | AWS・Salesforceへは接続していない。模擬tokenは署名検証済みのCognito JWTではなく、クライアント処理確認用のダミー。 | ローカル検証 |
| JMeter正常・更新 | 7ケース中、cache再利用は発行1回／API3回、短い模擬TTLでの更新は発行3回／API3回を確認。 | 実時間を使う更新試験。これはCognito実サービスでのTTL設定下限を変更したものではない。 | 成功 |
| JMeter異常系 | 誤secret・TTL不正・未信頼CA・ホスト名不一致・API 401で、失敗を検知して後続thread処理を停止した。 | 7ケースすべてで模擬secret／tokenがJTL・実行ログに出ていないことを確認。終了コード0でもsample失敗を検出する必要がある。 | 期待どおり拒否 |
| Pythonロジック | cache再利用、同時更新の排他、期限による再取得、更新失敗、TTL不正、HTTP要求形式、HTTPエラーの7テストが成功。 | SDK依存はテスト用スタブを使った。実boto3によるAWS API呼出は未実施。Python構文も確認。 | ローカル検証 |
| PowerShell | Windows PowerShell 5.1の構文解析でget_token.ps1にエラーなし。 | 実Cognitoに対する実行は未実施。Windowsの証明書ストアは変更していない。 | 構文確認 |
| Bash | Git Bashで構文チェックを試みたが、起動時にsignal pipe作成のWin32 error 5となった。 | Bashスクリプトは静的レビューのみ。Linux／WSL実行環境でbash -nと少数要求を実施する。 | 実行未検証 |
| Java SDK | GetClientTokenRequest等は公式Java SDK資料で確認。Java抜粋は対応SDK依存をこのPCへ導入していない。 | 導入先の固定したSDK BOMとHTTP実装でコンパイル・試験する。Java標準HTTPを使うJMeter例の実行確認とは別。 | コンパイル未実施 |
| Excel | 10シートを生成して各シートを画像化し、日本語・列幅・行高・構成図を確認。負荷試算の入力変更と元値への復元を確認。 | 数式のエラー走査と、保存後のセル書式／数式／キャッシュ値を確認する。ネイティブExcelアプリでの操作試験は未実施。 | ファイル検証 |
| 差分の確認 | 作業ディレクトリはGitリポジトリではなかった。既存ファイルもなく、新規成果物だけを生成した。 | git diff --no-index --checkと生成テキストの空白・文字化け・機密混入の静的確認を実施する。 | 新規作成 |

### 出典一覧

| ID | 公式資料・確認対象 | URL | 確認日 |
| --- | --- | --- | --- |
| S01 | GetClientTokenの提供開始（2026-08-31） | https://aws.amazon.com/about-aws/whats-new/2026/08/amazon-cognito-get-client-token/ | 2026-10-18 |
| S02 | GetClientToken API契約 | https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetClientToken.html | 2026-10-18 |
| S03 | Cognito PrivateLinkの制約・ポリシー | https://docs.aws.amazon.com/cognito/latest/developerguide/vpc-interface-endpoints.html | 2026-10-18 |
| S04 | M2M・スコープ・GetClientToken設定 | https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-define-resource-servers.html | 2026-10-18 |
| S05 | AWS CLI get-client-token | https://docs.aws.amazon.com/cli/latest/reference/cognito-idp/get-client-token.html | 2026-10-18 |
| S06 | 従来のOAuthトークンエンドポイント | https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html | 2026-10-18 |
| S07 | Private REST API | https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-apis.html | 2026-10-18 |
| S08 | Private APIのURL・Host・x-apigw-api-id | https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-api-test-invoke-url.html | 2026-10-18 |
| S09 | API Gateway VPCEポリシーとBearer | https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-vpc-endpoint-policies.html | 2026-10-18 |
| S10 | REST APIのCognitoオーソライザー設定 | https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-enable-cognito-user-pool.html | 2026-10-18 |
| S11 | APIリソースポリシーとSourceVpce | https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-resource-policies-examples.html | 2026-10-18 |
| S12 | CognitoのPython SDK公式例 | https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-identity-provider_example_cognito-identity-provider_GetClientToken_section.html | 2026-10-18 |
| S13 | Java SDK GetClientTokenRequest | https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/cognitoidentityprovider/model/GetClientTokenRequest.html | 2026-10-18 |
| S14 | アクセストークンの意味・有効期限 | https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html | 2026-10-18 |
| S15 | JWTの検証 | https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html | 2026-10-18 |
| S16 | 期限とキャッシュ | https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-caching-tokens.html | 2026-10-18 |
| S17 | Cognitoのクォータ | https://docs.aws.amazon.com/cognito/latest/developerguide/quotas.html | 2026-10-18 |
| S18 | Cognito料金（料金判断の一次資料） | https://aws.amazon.com/cognito/pricing/ | 2026-10-18 |
| S19 | PrivateLink料金 | https://aws.amazon.com/privatelink/pricing/ | 2026-10-18 |
| S20 | AWS PrivateLink DNS・経路・AZ | https://docs.aws.amazon.com/vpc/latest/privatelink/privatelink-access-aws-services.html | 2026-10-18 |
| S21 | Interface VPCEの設定 | https://docs.aws.amazon.com/vpc/latest/privatelink/interface-endpoints.html | 2026-10-18 |
| S22 | Secrets ManagerのVPCE | https://docs.aws.amazon.com/secretsmanager/latest/userguide/vpc-endpoint-overview.html | 2026-10-18 |
| S23 | Direct Connectの概要 | https://docs.aws.amazon.com/directconnect/latest/UserGuide/Welcome.html | 2026-10-18 |
| S24 | 社内DNSからのResolver inbound | https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/resolver-forwarding-inbound-queries.html | 2026-10-18 |
| S25 | SalesforceとPrivate APIの公式構成例（2024-02-29） | https://aws.amazon.com/blogs/apn/private-integration-salesforce-and-amazon-api-gateway/ | 2026-10-18 |
| S26 | Salesforce Private Connect outbound | https://trailhead.salesforce.com/content/learn/modules/private-connect-outbound-connections/create-an-outbound-connection | 2026-10-18 |
| S27 | JMeter コンポーネント | https://jmeter.apache.org/usermanual/component_reference.html | 2026-10-18 |
| S28 | JMeter CLI・分散実行 | https://jmeter.apache.org/usermanual/get-started.html | 2026-10-18 |
| S29 | JMeter ベストプラクティス | https://jmeter.apache.org/usermanual/best-practices.html | 2026-10-18 |
| S30 | JMeter 保存・TLS等のプロパティ | https://jmeter.apache.org/usermanual/properties_reference.html | 2026-10-18 |
| S31 | OAuth 2.0 RFC 6749（2012-10） | https://www.rfc-editor.org/rfc/rfc6749.html | 2026-10-18 |
| S32 | Cognito OAuth・Client Credentials提供（2017-08-10） | https://aws.amazon.com/about-aws/whats-new/2017/08/amazon-cognito-launches-general-availability-of-a-built-in-customizable-user-experience-for-sign-in-oauth-2-0-support-and-federation-with-facebook-login-with-amazon-google-and-saml-providers-for-user-pools/ | 2026-10-18 |
| S33 | Private API提供（2018-06-14） | https://aws.amazon.com/blogs/compute/introducing-amazon-api-gateway-private-endpoints/ | 2026-10-18 |
| S34 | Cognito User Pools PrivateLink提供（2025-11-07） | https://aws.amazon.com/about-aws/whats-new/2025/11/amazon-cognito-user-pools-private-connectivity-aws-privatelink/ | 2026-10-18 |
| S35 | アプリクライアント作成の設定契約 | https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_CreateUserPoolClient.html | 2026-10-18 |
| S36 | アプリクライアントの秘密の追加 | https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_AddUserPoolClientSecret.html | 2026-10-18 |
| S37 | ユーザープール設定更新時の既定値への復帰 | https://docs.aws.amazon.com/cli/latest/reference/cognito-idp/update-user-pool.html | 2026-10-18 |
| S38 | Issuer・OIDC discovery | https://docs.aws.amazon.com/cognito/latest/developerguide/federation-endpoints.html | 2026-10-18 |
| S39 | Cognito初期提供（2014-07-10） | https://aws.amazon.com/blogs/aws/aws-mobile-services/ | 2026-10-18 |
| S40 | User Pools正式提供（2016-07-28） | https://aws.amazon.com/about-aws/whats-new/2016/07/amazon-cognito-your-user-pools-is-now-generally-available/ | 2026-10-18 |

## 付属ファイルの利用

`examples`内のコードは同じ説明を実ファイル化したもの。Python SDKには対応版boto3／botocore、Java抜粋には対応版SDK v2とHTTP実装が必要。依存は社内の承認済み手順で導入し、ロックして閉域環境へ配布する。

Python SDK：`python get_token_sdk.py`はtoken値を表示せず取得結果を表示する。`--call-api`はAPI_URLへGETする。`--emit-token`はtokenを標準出力へ出すため、保護したパイプ等でだけ使う。PowerShell例も既定ではtokenを表示しない。

すべての例でAWS_REGION、COGNITO_CLIENT_ID、COGNITO_CLIENT_SECRET、COGNITO_SCOPESを実行プロセスへ安全に注入する。COGNITO_SCOPESは空白区切り。宛先endpointは信頼済みの運用設定とし、外部入力で差し替えさせない。任意のendpointへsecretを送らない。

TLS検証を無効にするcurl -k、CLI --no-verify-ssl、trust-allの追加はしない。公開出口遮断、DNS、SG、ルート、VPCE policyの確認はコード実行の前提であり、コードの成功だけで閉域性を保証しない。
