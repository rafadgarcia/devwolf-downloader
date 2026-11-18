# Auditoria Diagnóstica RC1 — Reddit (`PRIVATE_MEDIA` em Conteúdo Público)

- **Escopo**: Investigação da falha observada em dispositivo físico na `v3.0.0-rc.1`, onde links públicos do Reddit falham com erro terminal de mídia privada (`PRIVATE_MEDIA` — *"Este conteúdo pertence a uma conta ou publicação privada."* / *"Conteúdo privado ou restrito pelo autor."*).
- **Regra aplicada**: Nenhum arquivo de produção (`android/app/src/main/**`, `frontend/src/**`) foi alterado nesta etapa.

---

## 1. Fluxo de Execução Atual (Passo a Passo)

Quando o usuário cola ou compartilha um link do Reddit (`https://www.reddit.com/r/<sub>/comments/...`, `https://redd.it/...` ou `https://www.reddit.com/r/<sub>/s/<share_id>`):

1. **`UrlExtractor.normalizeUrl` / `detectPlatform`** ([UrlExtractor.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/UrlExtractor.kt#L72-L73)):
   - Identifica `reddit.com` e `redd.it` como plataforma `"reddit"`.
   - Remove parâmetros de rastreamento (`utm_*`, `share_id`, etc.) mantendo o path intacto (inclusive `/r/<sub>/s/<share_id>`).
2. **`EngineRouter.route`** ([EngineRouter.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/EngineRouter.kt#L74-L80)):
   - Seleciona `primaryEngine = EngineKind.GALLERY_DL_LOCAL`, `fallbackEngines = [EngineKind.YT_DLP]` e `shouldInitializeYtdlpUpfront = false`.
3. **`DevwolfDownloadService.runDownloadJob`** ([DevwolfDownloadService.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt#L344-L356)):
   - Lê um eventual cookie salvo em `SecureVault.getCookieForPlatform("reddit")` e o envia em `ResolutionRequest.cookieHeader`.
   - Chama `EngineRouter.shared.resolveNative(req, EngineKind.GALLERY_DL_LOCAL)`.
4. **`GalleryDlLocalResolver.resolveReddit`** ([GalleryDlLocalResolver.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/GalleryDlLocalResolver.kt#L121-L210)):
   - Monta `headers` fixos com `"User-Agent" to "android:com.devwolf.downloader:v3.0.0"` e `"Accept" to "application/json"` (ignorando `request.cookieHeader`).
   - Chama `httpClient.resolveFinalUrl(initialTarget, headers)` (L135).
   - Monta `jsonEndpoint = buildRedditJsonUrl(finalUrl)` (L138, L801–808), anexando `.json?raw_json=1` ao `uri.path`.
   - Executa `httpClient.getText(jsonEndpoint, headers)` (L145).
   - **Ponto de falha fatal (L155–162)**:
     ```kotlin
     if (resp.statusCode == 403) {
         throw ResolverException(
             errorCode = DevwolfErrorCode.PRIVATE_MEDIA,
             httpStatus = 403,
             technicalReason = "Comunidade ou publicação privada no Reddit",
             metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
         )
     }
     ```
5. **Bloqueio de Fallback no `EngineRouter` / `DevwolfDownloadService`** ([EngineRouter.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/EngineRouter.kt#L122-L143), [DevwolfDownloadService.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt#L544-L552)):
   - `DevwolfErrorCode.PRIVATE_MEDIA` possui `isTerminal = true` ([ResolverContract.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/ResolverContract.kt#L17-L20)).
   - `EngineRouter.shouldFallback(GALLERY_DL_LOCAL, YT_DLP, PRIVATE_MEDIA)` retorna `false`.
   - `DevwolfDownloadService` interrompe o loop de motores imediatamente: o **`yt-dlp` nunca chega a ser inicializado ou executado** — mesmo quando o post era um vídeo `v.redd.it` cujo fluxo previsto era cair no `yt-dlp`.

---

## 2. Causas-Raiz Comprovadas

### Causa-Raiz R1 — Mapeamento Incondicional de HTTP `403` para `PRIVATE_MEDIA` (`GalleryDlLocalResolver.kt:155-162`)
- **Inconsistência interna**: O próprio contrato `ErrorClassifier.fromHttpStatus(statusCode, contextHint)` ([ResolverContract.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/ResolverContract.kt#L165-L181)) **não** mapeia HTTP `403` para `PRIVATE_MEDIA`: ele devolve `CAPTCHA_REQUIRED` se o hint indicar `"captcha"`/`"challenge"`, `AUTH_REQUIRED` se indicar `"login"`, e **`EXTRACTION_FAILED` (recuperável)** nos demais casos (como bloqueio WAF/Cloudflare/Fastly).
- Entretanto, `GalleryDlLocalResolver.resolveReddit` **contorna `ErrorClassifier.fromHttpStatus`** na linha 155 (embora o chame na linha 172 para outros códigos HTTP) e transforma **qualquer** resposta `403` no Reddit em `PRIVATE_MEDIA` terminal.
- **Consequência prática no Android**: Redes móveis (CGNAT/operadoras), IPs residenciais com histórico compartilhado e requisições anônimas ao endpoint `.json` de `www.reddit.com` recebem rotineiramente HTTP `403` da camada Fastly/segurança de rede do Reddit (`"whoa there, pardner!"`, `"You've been blocked by network security"`, `"403 Blocked"` em HTML). O app exibe ao usuário que o post público é *"privado ou restrito pelo autor"*.

### Causa-Raiz R2 — Cabeçalhos Propensos a Bloqueio Fastly/WAF e Omissão de Cookie (`GalleryDlLocalResolver.kt:125-128`)
- O header `User-Agent` usado é `"android:com.devwolf.downloader:v3.0.0"`, sem sufixo `(by /u/...)` esperado pela política de API do Reddit e sem cookies de sessão (`request.cookieHeader` recebido pelo `DevwolfDownloadService` não é anexado em `resolveReddit`).
- Além disso, o mesmo header `"Accept" to "application/json"` é enviado tanto para a resolução de redirecionamento (`resolveFinalUrl`) quanto para o endpoint `.json` em `www.reddit.com`. Requisições anônimas com `Accept: application/json` e `User-Agent` genérico/customizado sem OAuth frequentemente recebem HTTP `403` do Fastly/WAF em `www.reddit.com` (enquanto `old.reddit.com` ou fluxo HTML/`yt-dlp` pode ter comportamento distinto).

### Causa-Raiz R3 — Falha Silenciosa em Links de Compartilhamento Móvel `/r/<sub>/s/<share_id>` (`SafeHttpClient.kt:180-230`, `GalleryDlLocalResolver.kt:135-145, 801-808`)
- O aplicativo oficial do Reddit no Android gera URLs no formato `https://www.reddit.com/r/<subname>/s/<share_id>`.
- `GalleryDlLocalResolver.resolveReddit` tenta resolver o redirecionamento via `httpClient.resolveFinalUrl(initialTarget, headers)`.
- Em `SafeHttpClient.resolveFinalUrl` ([SafeHttpClient.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/SafeHttpClient.kt#L208-L225)), se o servidor responder `403` (ou `200` com redirecionamento via JS/meta-refresh em vez de HTTP `301`/`302`), `!resp.isRedirect` é verdadeiro e `resolveFinalUrl` **retorna silenciosamente a própria URL `/r/<sub>/s/<share_id>` sem erro**.
- Em seguida, `buildRedditJsonUrl(finalUrl)` ([GalleryDlLocalResolver.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/GalleryDlLocalResolver.kt#L801-L808)) não verifica se o path já contém `/comments/<id>` e gera `https://www.reddit.com/r/<sub>/s/<share_id>.json?raw_json=1`, que recebe HTTP `403`/`404` ou HTML — e se for `403`, dispara novamente `PRIVATE_MEDIA`!

### Causa-Raiz R4 — Comportamento quando `yt-dlp` é Alcançado (`ErrorClassifier.fromYtdlpOutput`)
- Quando o fluxo chega ao `yt-dlp` ([DevwolfDownloadService.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt#L988-L1012)), ele executa com `--user-agent SafeHttpClient.DEFAULT_MOBILE_CHROME_UA` e só recebe `--cookies` se o usuário tiver salvo manualmente um cookie para `"reddit"` no cofre.
- Caso o próprio `yt-dlp` receba `HTTP Error 403: Blocked`, `ErrorClassifier.fromYtdlpOutput` ([ResolverContract.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/ResolverContract.kt#L183-L219)) **não** classifica `"403"` nem `"Blocked"` como `PRIVATE_MEDIA` (cai em `EXTRACTION_FAILED`), e só retorna `PRIVATE_MEDIA` para frases explícitas (`"account is private"`, `"private account"`, `"this video is private"`, `"private post"`).
- Ou seja: **o falso positivo `PRIVATE_MEDIA` em conteúdo público do Reddit nasce especificamente no `GalleryDlLocalResolver.resolveReddit` (L155–162)** antes mesmo de o `yt-dlp` ter chance de rodar.

---

## 3. Matriz de Diferenciação de Respostas HTTP do Reddit

Para corrigir o classificador sem gerar novos falsos positivos nem mascarar conteúdo realmente privado, é obrigatório distinguir os quatro cenários abaixo:

| Cenário Real | Evidência na Resposta (Status / Content-Type / Body) | Classificação Correta no `DevwolfErrorCode` | Deve Acionar Fallback (`yt-dlp`)? |
|---|---|---|---|
| **1. Bloqueio WAF / Fastly / Anti-bot / IP móvel** | HTTP `403`, `Content-Type: text/html` ou texto contendo `"whoa there, pardner"`, `"blocked by network security"`, `"403 blocked"`, `"enable javascript"`, Cloudflare/Fastly | `EXTRACTION_FAILED` (ou `RATE_LIMITED` se indicar throttling explícito) | **Sim** (recuperável; permite fallback para `yt-dlp` / HTML) |
| **2. Subreddit / Conteúdo Realmente Privado** | HTTP `403` com JSON `{"reason": "private", "message": "Forbidden", "error": 403}`, ou `"subreddit_type": "private"` / `"is_private": true`, ou mensagem `"you must be invited to visit this community"` / `"this community is private"` | `PRIVATE_MEDIA` (`isTerminal = true`) | **Não** (conteúdo privado real) |
| **3. Autenticação / Quarentena / OAuth Obrigatório** | HTTP `401`, ou JSON `"reason": "quarantined"` / `"over18"` exigindo login, ou `"login_required"` / redirect para `/login` | `AUTH_REQUIRED` (`isTerminal = true`) | **Não** (exige sessão/conta autorizada) |
| **4. Link `/r/<sub>/s/<share_id>` não resolvido** | `resolveFinalUrl` retorna path `/s/<id>` sem `/comments/` (porque `/s/` devolveu `403` ou `200` HTML sem `Location` 3xx) | `EXTRACTION_FAILED` (`technicalReason` explícito de redirect `/s/` não resolvido) | **Sim** (delega para `yt-dlp` ou parser HTML antes de falhar) |

---

## 4. Achados em Issues Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`)

1. **`yt-dlp/yt-dlp` (Reddit `HTTP Error 403: Blocked` / `"whoa there, pardner"` / `/s/` links)**:
   - O Reddit vem endurecendo proteções Fastly/WAF, desafios JS (cookies como `loid`) e bloqueios de IP móvel/VPN/datacenter contra requisições `.json` anônimas, gerando `HTTP Error 403: Blocked` ou `JSONDecodeError` mesmo em posts públicos.
   - Links modernos de compartilhamento `/r/<sub>/s/<id>` dependem de redirecionamento e roteamento de frontend; quando o redirect falha por bloqueio HTTP 403 sem cookies, extratores que dependem apenas do `.json` falham.
   - Mitigações recomendadas no upstream: manter `yt-dlp` atualizado (stable/nightly) e aceitar cookies de navegador quando o endpoint anônimo sofre bloqueio de rede/WAF.
2. **`mikf/gallery-dl` (Reddit `403 Blocked` e `videos=dash`)**:
   - Relatos recorrentes de `HTTP Error 403: Blocked` e `"You've been blocked by network security"` para requisições anônimas ao Reddit sem cookies ou em IPs sinalizados.
   - Para vídeos `v.redd.it`, `gallery-dl` delega streams DASH para `yt-dlp` (`-o videos=dash`); quando `yt-dlp` está desatualizado ou o JSON inicial é bloqueado com 403, o download falha antes da etapa DASH.
3. **`junkfood02/youtubedl-android` (`0.18.1`)**:
   - Empacota um snapshot específico do `yt-dlp` que fica obsoleto rapidamente frente a mudanças de anti-bot em Instagram e Reddit, reforçando a importância da atualização local (`LocalEngineManager`) **e** de não bloquear o fallback para `yt-dlp` com falsos códigos terminais (`PRIVATE_MEDIA`/`CAPTCHA_REQUIRED`) no resolvedor Kotlin.

---

## 10. Correções Implementadas e Validadas na `v3.0.0-rc.2`

Todas as causas-raiz (`R1`–`R4`) identificadas nesta auditoria foram corrigidas e validadas na `v3.0.0-rc.2`:

### Mudanças Aplicadas no Código
1. **Diferenciação de HTTP `403` (`GalleryDlLocalResolver.classifyRedditHttpError` & `ErrorClassifier.fromHttpStatus`)**:
   - Respostas HTTP `403` com HTML WAF/Fastly/Cloudflare (`"whoa there, pardner!"`, `"blocked by network security"`, `"403 blocked"`, `text/html` sem indicador de comunidade privada) agora retornam `DevwolfErrorCode.EXTRACTION_FAILED` (recuperável), desbloqueando o fallback para `old.reddit.com` e em seguida para o `yt-dlp`.
   - `PRIVATE_MEDIA` (`isTerminal = true`) ficou estritamente limitado a indicadores explícitos (`"reason": "private"`, `"subreddit_type": "private"`, `"this community is private"`, `"you must be invited to visit this community"`).
   - HTTP `401` e `"reason": "quarantined"` / `"login_required"` retornam `AUTH_REQUIRED`; HTTP `429` retorna `RATE_LIMITED`; HTTP `404`/`410` retornam `MEDIA_UNAVAILABLE`.
2. **Resolução Segura de Links `/r/<sub>/s/<share_id>` e Encaminhamento de Sessão (`GalleryDlLocalResolver.resolveReddit` & `UrlExtractor.kt`)**:
   - `UrlExtractor.TRACKING_PARAMS` agora remove também `context`, `rdt` e `ref_source`, preservando `/r/<sub>/s/<share_id>` intacto.
   - `resolveReddit` envia `request.cookieHeader` (quando presente) e `User-Agent` compatível (`request.userAgent ?: DEFAULT_MOBILE_CHROME_UA`), usando `Accept: text/html...` na resolução de `/s/<id>` e extraindo `<link rel="canonical">` / `meta[property=og:url]` caso o servidor retorne HTTP `200` HTML sem redirect 3xx.
   - `buildRedditJsonUrl` retorna `null` se o path não contiver `/comments/` (proibindo anexar `.json` a `/s/<id>` não resolvido), lançando `EXTRACTION_FAILED` recuperável para delegar ao `yt-dlp`.
3. **Observabilidade no Diagnóstico (`DevwolfDownloadService.kt` & `PerformanceTracker.kt`)**:
   - Falhas do `GalleryDlLocalResolver` e do fallback `yt-dlp-single-pass` agora preservam `httpStatus` e a lista completa de `strategyAttempts` no `sanitizedLog` dos Ajustes, facilitando a verificação em dispositivo físico.

### Resultados dos Testes (`v3.0.0-rc.2`)
- `Rc1DiagnosticAuditTest` (`6/6` aprovados em `testDebugUnitTest --rerun-tasks`):
  - `redditClassifiesFastly403WafAsRecoverableExtractionFailedAndRealPrivateAsTerminal` valida que HTTP `403` Fastly (`"whoa there, pardner!"`) em `www.reddit.com` aciona fallback `old.reddit.com` e retorna `EXTRACTION_FAILED` recuperável (permitindo fallback para `yt-dlp`), enquanto JSON explícito `"reason": "private"` continua retornando `PRIVATE_MEDIA` terminal.
  - `redditMobileShareLinksSendHtmlAcceptExtractCanonicalAndNeverAppendJsonToSharePath` valida resolução via redirect/HTML canonical (`Accept: text/html...`) e comprova que `/s/<id>` bloqueado nunca recebe `.json`, falhando como `EXTRACTION_FAILED` recuperável.
  - Suíte completa Android: `39/39` testes aprovados (`7` suítes, `0` falhas, `0` ignorados).
