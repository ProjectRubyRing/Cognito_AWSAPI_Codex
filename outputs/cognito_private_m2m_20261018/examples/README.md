# 実装例の利用

先に親フォルダーの検討資料を読み、承認済みの検証環境でDNS・VPCE・SG・ルート・認可を確認してください。これらのコードを実行するだけで閉域になるわけではありません。

## 共通設定

次の環境変数を実行プロセスへ設定します。

| 変数 | 設定内容 |
| --- | --- |
| AWS_REGION | 対象リージョン。資料はap-northeast-1の例 |
| COGNITO_CLIENT_ID | ALLOW_CLIENT_TOKEN_AUTHの専用アプリクライアントID |
| COGNITO_CLIENT_SECRET | 安全な実行基盤・秘密管理から注入したsecret |
| COGNITO_SCOPES | 空白区切りのカスタムscope。例：orders/read |
| COGNITO_ENDPOINT | 任意。承認済みのHTTPS API endpoint。通常は省略しprivate DNSを利用 |
| API_URL | Pythonの--call-apiで使うHTTPS Private API URL |
| API_ID | PythonでVPCE固有URLを使うときなどに必要なREST API ID |

secretを設定するコマンドの実値を履歴へ残さず、Excel・JMX・properties・Gitにも書き込まないでください。環境変数も管理者・同権限のプロセスから読める場合があります。宛先endpointは信頼済みの運用設定として管理し、利用者入力で自由に変更できるようにしません。

## 単発の取得

- `python get_token_http.py`：標準ライブラリだけでAWS JSON APIを直接呼ぶ。
- `python get_token_sdk.py`：対応版boto3／botocoreが必要。既定ではtoken値を表示しない。
- `python get_token_sdk.py --call-api`：取得したtokenでAPI_URLへGETする。
- `./get_token_cli.sh`：Linux／WSL用。GetClientToken対応AWS CLI v2とpython3が必要。
- `./get_token_curl.sh`：Linux／WSL用。curlとpython3が必要。
- `.\get_token.ps1`：Windows PowerShell 5.1／PowerShell 7の直接HTTP例。
- `get_token_java.java`：Java SDK v2アプリへ組み込む抜粋。対応SDK BOMとHTTP実装を導入先で固定する。

`--emit-token`またはPowerShellの`-EmitToken`はtokenそのものを標準出力へ出します。保護したパイプ等で必要なときだけ使用し、実行ログへ保存しないでください。

## JMeter

1. このexamplesフォルダーをカレントディレクトリにする。
2. 全workerへ上記環境変数を安全に注入する。
3. `jmeter-private.properties`のapi_hostとapi_pathを設定する。VPCE固有のAPI用ホスト名ならapi_idも設定する。
4. Java 11以上とJMeterで、最初はthreads=1、loops=1のまま実行する。

```powershell
& 'C:\tools\apache-jmeter\bin\jmeter.bat' -n `
  -t .\private-m2m.jmx -q .\jmeter-private.properties `
  -l .\result.jtl -j .\jmeter.log
```

JMXは同じフォルダーの3つのGroovyファイルを読みます。標準プラグインだけで動作し、Java標準HttpClientのTLS検証を利用します。標準JMeter HTTP samplerのtrust-all動作に依存しません。

tokenと業務APIは別のsamplerラベルです。各threadがtokenを期限の75～80%まで再利用します。失敗時はthreadを停止し、API要求を自動で再送しません。共有キャッシュやSalesforceの認証ブローカーは、このサンプルには実装していません。

JTLのelapsedはスクリプト込みの処理時間です。接続時間・Latency・bytesは標準HTTP sampler相当の通信測定値として使えません。応答本文は保存せず、APIの2xxだけを判定するため、業務内容のassertionは別途追加します。

JMeterプロセスが終了コード0でも、sampleに失敗が含まれる場合があります。JTLのsuccess、responseCode、error率を必ず確認してください。

## 検証範囲

JMeter 5.6.3／Java 17で、ローカルHTTPS模擬サーバーに対する7シナリオを確認しました。Pythonのキャッシュ・要求形式・失敗処理も7テストを確認しています。PowerShellは構文解析済みです。Git Bashは起動時のWin32 error 5により実行検証できず、Java SDK抜粋も未コンパイルです。

AWS実環境・Salesforce・本物のCognito JWTの検証は未実施です。対象環境で親資料の受入試験を行ってください。
