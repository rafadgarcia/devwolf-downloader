# Auditoria Diagnóstica `v3.0.0-rc.1` — Instagram (`CAPTCHA_REQUIRED`) e Reddit (`PRIVATE_MEDIA`)

- **Versão auditada**: `v3.0.0-rc.1` (`commit 0b6c340` em `main`)
- **Escopo**: Diagnóstico forense baseado em leitura do código-fonte Kotlin/TS, evidências de engenharia reversa do Rposty (`docs/rev-eng/`), testes HTTP ao vivo e testes unitários reproduzíveis com payloads sanitizados.
- **Restrição preservada**: Nenhum arquivo de produção (`android/app/src/main/**`, `frontend/src/**`) foi modificado nesta auditoria.
- **Documento complementar**: [reddit-audit.md](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/docs/diagnostics/rc1/reddit-audit.md)
- **Suíte reproduzível**: [Rc1DiagnosticAuditTest.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/test/java/com/devwolf/downloader/resolvers/Rc1DiagnosticAuditTest.kt)

---

## 1. Resumo Executivo dos Resultados Físicos (`v3.0.0-rc.1`)

Os testes em aparelho físico na `v3.0.0-rc.1` revelaram duas falhas bloqueadoras em plataformas prioritárias:

1. **Instagram retorna `CAPTCHA_REQUIRED` mesmo após login via WebView**:
   - **Causa principal comprovada**: O parser de falhas [InstagramParsers.classifyFailure](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramParsers.kt#L157-L217) procura a substring `"captcha"` (entre outras) em **todo o HTML bruto** da página (`body.lowercase()`). Páginas HTML legítimas da shell Polaris do Instagram (HTTP `200`, ~536 KB) contêm rotineiramente configurações/telemetria JS inline como `"arkose_captcha"`, `"google_recaptcha"` e `"whatsapp_arkose_captcha"`.
   - **Efeito cascata**: A Estratégia 1 do DNR (`EmbedCaptionedStrategy`) sempre executa primeiro **sem cookies** (`Cookie` omitido) e com `User-Agent` desktop fixo. Quando não extrai mídia do `/embed/captioned/`, chama `classifyFailure`, recebe um falso positivo `CAPTCHA_REQUIRED` (`isTerminal = true`), **aborta imediatamente** sem tentar a Estratégia 2 (`WebPageJsonStrategy`, que envia o cookie da sessão), sem tentar a Estratégia 3 (`AuthorizedWebApiStrategy`) e **bloqueando o fallback para o `yt-dlp`**.
2. **Reddit retorna `PRIVATE_MEDIA` em publicações públicas**:
   - **Causa principal comprovada**: [GalleryDlLocalResolver.resolveReddit](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/GalleryDlLocalResolver.kt#L155-L162) converte **incondicionalmente** qualquer HTTP `403` em `DevwolfErrorCode.PRIVATE_MEDIA` (`isTerminal = true`), contornando [ErrorClassifier.fromHttpStatus](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/ResolverContract.kt#L165-L181).
   - **Efeito cascata**: Como `PRIVATE_MEDIA` é terminal no [EngineRouter.shouldFallback](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/EngineRouter.kt#L122-L143), qualquer bloqueio WAF/Fastly do Reddit (`"whoa there, pardner!"`, `"403 Blocked"` em HTML sobre `.json` anônimo em IP móvel, ou falha de redirect em links `/r/<sub>/s/<id>`) exibe *"Conteúdo privado ou restrito pelo autor"* e **impede o fallback para o `yt-dlp`**.

---

## 2. Investigação 1: Falsos Positivos de `CAPTCHA_REQUIRED` no Instagram

### Localização Exata
- [InstagramParsers.kt#L157-L217](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramParsers.kt#L157-L217) (`InstagramParsers.classifyFailure`)
- Chamadores em [InstagramNativeResolver.kt#L115](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt#L115), [#L178](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt#L178), [#L252](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt#L252).

### Mecanismo da Falha (Comprovado)
1. Os parsers de sucesso (`parseEmbedCaptionedHtml`, `parseWebPageHtml`) retornam `null` quando a página não traz o JSON/DOM esperado.
2. Quando `hasAcceptableMedia` é `false`, `InstagramNativeResolver` invoca `InstagramParsers.classifyFailure(resp.statusCode, resp.finalUrl, resp.body, hasSession)`.
3. Em `classifyFailure` (L174–181):
   ```kotlin
   if (lowerUrl.contains("/challenge/") ||
       lowerBody.contains("checkpoint_required") ||
       lowerBody.contains("challenge_required") ||
       lowerBody.contains("verify you are human") ||
       lowerBody.contains("captcha")
   ) {
       return DevwolfErrorCode.CAPTCHA_REQUIRED
   }
   ```
4. **Verificação HTTP real**: Respostas HTTP `200` reais da shell SPA Polaris do Instagram (~536,5 KB em `/p/C-example/embed/captioned/?cr=1&v=14&wp=1080`, `/reel/.../embed/captioned/`, `/p/C-example/` e `/instagram/`) contêm **`"captcha"` 13 vezes** em blocos `<script>` / configuração bootloader (`"arkose_captcha"`, `"google_recaptcha"`, `"whatsapp_arkose_captcha"`, `"553648129.polaris.AuthPlatformReCaptcha"`). Isso explica diretamente por que o teste ao vivo registrado no item #42 falhou com `CAPTCHA_REQUIRED` em apenas 1 requisição (`792 ms`).
5. **Problema adicional de precedência**: `CAPTCHA_REQUIRED` (L174–181) é avaliado **antes** de `MEDIA_UNAVAILABLE` (L183–192), `PRIVATE_MEDIA` (L195–201) e `AUTH_REQUIRED` (L203–210). Assim, qualquer página 404 ou muro de login que carregue a shell padrão do Polaris também é mascarada como `CAPTCHA_REQUIRED`.
6. **Contraste com `yt-dlp`**: [ErrorClassifier.fromYtdlpOutput](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/ResolverContract.kt#L192-L193) verifica `"captcha"` apenas nas linhas fatais do stderr do `yt-dlp`, não em HTML bruto. O falso positivo nasce exclusivamente em `InstagramParsers.classifyFailure`.

---

## 3. Investigação 2: Uso da Sessão Autenticada do Instagram pelo DNR

### Rastreamento Completo da Cadeia de Sessão

| Etapa | Componente / Arquivo | Comportamento Observado |
|---|---|---|
| 1. Captura WebView | [InstagramSessionActivity.kt#L183-L209](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/InstagramSessionActivity.kt#L183-L209) | Lê `CookieManager.getInstance().getCookie("https://www.instagram.com")` quando `sessionid` está presente e salva cookie + `webView.settings.userAgentString` no cofre. |
| 2. Persistência | [SecureVault.kt#L85-L124](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/SecureVault.kt#L85-L124) | Armazena em `EncryptedSharedPreferences` (`session_cookie_instagram`, `session_ua_instagram`) e expõe `StoredInstagramSession` via `getInstagramSession()`. |
| 3. Serviço de Download | [DevwolfDownloadService.kt#L345-L356](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt#L345-L356) | **Sim, carrega a sessão corretamente** (`igSession = secureVault.getInstagramSession()`) e popula `ResolutionRequest(cookieHeader = ..., userAgent = igSession?.userAgent)` para `EngineRouter.shared.resolveNative`. *(Nota: no fallback `yt-dlp`, L988 e L1006–1012 gravam `scoped.cookies.txt` para `.instagram.com`, mas fixam `--user-agent SafeHttpClient.DEFAULT_MOBILE_CHROME_UA` em vez de `igSession?.userAgent`.)* |
| 4. Isolamento HTTP | [SafeHttpClient.kt#L368-L388](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/SafeHttpClient.kt#L368-L388) | `scopeHeadersForHost` permite `Cookie` e `X-CSRFToken` entre subdomínios `*.instagram.com` (incluindo `www.instagram.com` e `i.instagram.com`), removendo-os apenas em CDNs externas (`cdninstagram.com`, `fbcdn.net`). |
| 5. Estratégia 1 (DNR) | [InstagramNativeResolver.kt#L85-L145](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt#L85-L145) | **Quebra principal**: `EmbedCaptionedStrategy` executa **antes** das estratégias autenticadas mesmo quando `hasSession == true` (L78), constrói `embedHeaders` (L90–95) **sem `Cookie`** e com `DEFAULT_DESKTOP_CHROME_UA` fixo (ignorando `request.userAgent`). |
| 6. Estratégia 2 (DNR) | [InstagramNativeResolver.kt#L148-L208](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt#L148-L208) | `WebPageJsonStrategy` **envia** `baseHeaders` (com `Cookie` e `userAgent` da sessão), mas raramente é alcançada devido ao aborto da Estratégia 1. |
| 7. Estratégia 3 (DNR) | [InstagramNativeResolver.kt#L210-L273](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt#L210-L273) | Apresenta quatro lacunas estruturais detalhadas abaixo. |

### Lacunas na Estratégia 3 (`AuthorizedWebApiStrategy`) e Extração de `media_id`
1. **Host `www.instagram.com` em vez de `i.instagram.com`** ([InstagramNativeResolver.kt#L218](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt#L218)): A chamada vai para `https://www.instagram.com/api/v1/media/{mediaId}/info/`, enquanto a API privada/mobile utilizada por clientes como o Rposty aponta para `https://i.instagram.com/api/v1/media/{mediaId}/info/` com `x-ig-app-id: 936619743392459` e `x-asbd-id: 198387`.
2. **Extração incompleta de `media_id` e ausência de decodificação de shortcode** ([InstagramParsers.kt#L133-L151](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramParsers.kt#L133-L151)): `extractPageContext` busca apenas regex `"(?:media_id|pk)"\s*:\s*"(\d{10,25})"` no HTML da Estratégia 2. Não lê `<meta property="al:ios:url" content="instagram://media?id=...">` nem implementa a conversão matemática determinística `shortcode -> media_id` (alfabeto Base64 URL-safe do Instagram), que dispensaria scraping prévio para posts públicos/privados com sessão.
3. **Fallback legado `?__a=1&__d=dis`** ([InstagramNativeResolver.kt#L225](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt#L225)): Quando `lastPageContext.mediaId` é `null`, a Estratégia 3 recorre ao endpoint depreciado `/{seg}/{shortcode}/?__a=1&__d=dis`.
4. **Headers de API incompletos** ([InstagramNativeResolver.kt#L233](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt#L233)): `X-IG-App-ID` só é enviado se encontrado no HTML da Estratégia 2 (sem valor padrão `936619743392459`), `X-ASBD-ID` nunca é enviado e `lsdToken` extraído em `extractPageContext` (L139–141) nunca é utilizado em nenhuma consulta GraphQL.

---

## 4. Investigação 3: Interrupção Prematura das Estratégias do DNR e Fallback

1. **Bloqueio dentro do DNR (`InstagramNativeResolver.kt#L116-L137`, `#L179-L200`)**:
   - Na Estratégia 1 (e Estratégia 2), qualquer retorno de `classifyFailure` que seja `RATE_LIMITED`, `CAPTCHA_REQUIRED`, `MEDIA_UNAVAILABLE` ou `PRIVATE_MEDIA` dispara `throw ResolverException(...)` imediatamente.
   - Como uma página normal sem mídia extraída dispara `CAPTCHA_REQUIRED`, o fluxo morre na Estratégia 1 após 1 requisição HTTP.
2. **Bloqueio de `yt-dlp` no `EngineRouter` (`EngineRouter.kt#L122-L143`, `DevwolfDownloadService.kt#L548`)**:
   - `CAPTCHA_REQUIRED.isTerminal == true` ([ResolverContract.kt#L26](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/ResolverContract.kt#L26)).
   - `EngineRouter.shared.shouldFallback(EngineKind.DNR, EngineKind.YT_DLP, CAPTCHA_REQUIRED)` retorna `false`, impedindo que o `yt-dlp` (com `scoped.cookies.txt`) sequer tente baixar o post/Reel.
3. **Armadilha secundária de `sawAuthWall` (`InstagramNativeResolver.kt#L125`, `#L275-L279`)**:
   - Se a Estratégia 1 (que roda **anônima**) receber um login wall (`/accounts/login/` -> `AUTH_REQUIRED`), marca `sawAuthWall = true`. Se as Estratégias 2 e 3 falharem com `EXTRACTION_FAILED`, a L276 promove `sawAuthWall` para `AUTH_REQUIRED` terminal no final — também bloqueando o fallback para `yt-dlp`.

---

## 5. Investigação 4: Recomparação Detalhada com Evidências do Rposty (REA/JADX)

| Dimensão | Rposty (`docs/rev-eng/sources/`) | Devwolf DNR (`v3.0.0-rc.1`) | Impacto da Divergência |
|---|---|---|---|
| **Ordem com sessão ativa** | `Q8/j.java:171-197`, `utils/j.java:141-147`: Se `sessionid` existe (`j.l(map)`), consulta a **URL principal** enviando `cookie`, `User-Agent` e `x-csrftoken`. Só reescreve para `/embed/captioned/?cr=1&v=13&wp=1080` (`Q8/j.java:103-120`) e remove credenciais (`:189-192`) quando **não** há sessão. | Sempre executa `EmbedCaptionedStrategy` **primeiro e anônima**, mesmo com sessão válida salva no `SecureVault`. | Desperdiça a sessão autenticada do usuário e expõe o fluxo ao falso positivo no `/embed/captioned/`. |
| **Extração de `media_id`** | `A8/c.java:29-44` (`B`): Busca `"media_id":"([\d]*)"` **e** `media\?id=([\d]*)"` (`al:ios:url`). | `InstagramParsers.kt:135-136`: Busca apenas `"(?:media_id\|pk)"\s*:\s*"(\d{10,25})"`. | Falha em recuperar `media_id` de meta tags `al:ios:url` na shell HTML. |
| **Endpoint da API v1** | `Q8/j.java:39, 51-54`, `p148s8/b.java:55-57`: Usa `https://i.instagram.com/api/v1/media/{mediaid}/info/` com `x-ig-app-id: 936619743392459` e `x-asbd-id: 198387`. | Usa `https://www.instagram.com/api/v1/media/{mediaId}/info/` sem `X-ASBD-ID` e com `X-IG-App-ID` opcional; cai em `?__a=1&__d=dis` se `mediaId == null`. | Endpoint `www.../?__a=1&__d=dis` é muito mais bloqueado; cabeçalhos obrigatórios podem faltar. |
| **Fallback GraphQL (`lsd`)** | `Q8/j.java:61-68`, `M7/d.java:115-148`, `p148s8/b.java:72-75`: Extrai `lsd`, `csrf_token`, `device_id`, `machine_id`, `versioningID` (`PublicCookieModel`) e faz POST em `/graphql/query` (`PolarisPostActionLoadPostQueryQuery`). | Extrai `lsdToken` (`InstagramParsers.kt:139-141`), mas **nunca o utiliza** em nenhuma consulta GraphQL. | Falta uma camada intermediária funcional quando a página HTML vem sem JSON embutido. |
| **Tratamento de erro HTML** | `Q8/j.java:242-255`, `Q8/g.java:43-93`: Alterna contextos/páginas (`f3637h <= 3`) sem classificar HTML por substring `"captcha"`. | Classifica `"captcha"` em todo o HTML bruto como `CAPTCHA_REQUIRED` terminal na Estratégia 1. | Interrompe todas as estratégias subsequentes e bloqueia `yt-dlp`. |

---

## 6. Investigação 5: Classificação Incorreta de `PRIVATE_MEDIA` no Reddit

*(Análise detalhada completa em [reddit-audit.md](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/docs/diagnostics/rc1/reddit-audit.md).)*

1. **Erro direto no código (`GalleryDlLocalResolver.kt#L155-L162`)**:
   - `resolveReddit` intercepta `resp.statusCode == 403` e lança `ResolverException(DevwolfErrorCode.PRIVATE_MEDIA, httpStatus = 403, technicalReason = "Comunidade ou publicação privada no Reddit")` **sem inspecionar o corpo/Content-Type** e **contornando** `ErrorClassifier.fromHttpStatus(403, body)` ([ResolverContract.kt#L168-L175](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/ResolverContract.kt#L168-L175)), que devolveria `EXTRACTION_FAILED` (recuperável).
   - Como `PRIVATE_MEDIA.isTerminal == true`, [EngineRouter.shouldFallback](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/EngineRouter.kt#L128) retorna `false` e bloqueia o fallback para o `yt-dlp`.
2. **Links de compartilhamento móvel `/r/<sub>/s/<share_id>` e `.json`**:
   - `resolveReddit` envia `"User-Agent": "android:com.devwolf.downloader:v3.0.0"` (sem `(by /u/...)`) e `"Accept": "application/json"`, ignorando `request.cookieHeader`.
   - Em [SafeHttpClient.resolveFinalUrl](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/SafeHttpClient.kt#L208-L225), se `/r/<sub>/s/<share_id>` responder HTTP `403` (ou `200` HTML sem redirect 3xx), `resolveFinalUrl` retorna silenciosamente o próprio `/s/<share_id>`, e `buildRedditJsonUrl` ([GalleryDlLocalResolver.kt#L801-L808](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/GalleryDlLocalResolver.kt#L801-L808)) anexa `.json?raw_json=1` diretamente a `/s/<share_id>`, resultando em novo `403` -> `PRIVATE_MEDIA`.
3. **Diferenciação obrigatória de respostas**:
   - **Bloqueio WAF / Fastly / IP móvel**: HTTP `403` com `Content-Type: text/html` contendo `"whoa there, pardner!"`, `"You've been blocked by network security"` ou `"403 Blocked"` -> deve ser **`EXTRACTION_FAILED`** (recuperável, acionando fallback `yt-dlp`).
   - **Privado real**: HTTP `403` com JSON `{"reason": "private", "message": "Forbidden"}` ou `"subreddit_type": "private"` / `"this community is private"` -> **`PRIVATE_MEDIA`** (terminal).
   - **Auth / Quarentena**: HTTP `401` ou `"reason": "quarantined"` / login wall -> **`AUTH_REQUIRED`** (terminal).
   - **Redirect `/s/` não resolvido**: Path permanece `/s/...` sem `/comments/` -> **`EXTRACTION_FAILED`** (delega para `yt-dlp`).
4. **Verificação no `yt-dlp`**: [ErrorClassifier.fromYtdlpOutput](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/ResolverContract.kt#L183-L219) **não** mapeia `"HTTP Error 403: Blocked"` para `PRIVATE_MEDIA` (mapeia para `EXTRACTION_FAILED`). Logo, o falso positivo `PRIVATE_MEDIA` no Reddit origina-se exclusivamente em `GalleryDlLocalResolver.kt:155-162`.

---

## 7. Investigação 6: Issues Recentes Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`)

- **`yt-dlp/yt-dlp` — Reddit (`403 Blocked`, `"whoa there, pardner"`, `/s/` links)**:
  - O Reddit intensificou bloqueios Fastly/Cloudflare, desafios JS (`loid`) e restrições de API contra requisições anônimas ao `.json`, gerando `HTTP Error 403: Blocked` ou `JSONDecodeError` especialmente em redes móveis/VPN/datacenter. Links `/s/` dependem de resolução de redirect/roteamento que também sofre `403` quando sem cookies. Mitigações upstream: manter `yt-dlp` atualizado e suportar `--cookies`.
- **`mikf/gallery-dl` — Reddit (`403 Blocked`, `videos=dash`)**:
  - Relatos frequentes de `HTTP Error 403: Blocked` e `"You've been blocked by network security"` para consultas sem cookies em IPs bloqueados; vídeos `v.redd.it` dependem da delegação DASH ao `yt-dlp` (`videos=dash`).
- **`yt-dlp/yt-dlp` & `mikf/gallery-dl` — Instagram (`embed/captioned`, `xdt_api`, `challenge_required`)**:
  - Rotação frequente de endpoints GraphQL/`xdt_api` e `doc_id`; páginas `/embed/captioned/` frequentemente caem para shell SPA ou login wall; `api/v1/media/{id}/info/` e GraphQL exigem cookies válidos e headers compatíveis (`X-IG-App-ID`, `X-CSRFToken`, `User-Agent` consistente); uso agressivo sem sessão ou com UA inconsistente dispara `challenge_required` real.
- **`junkfood02/youtubedl-android` (`0.18.1`)**:
  - O zipapp embarcado na release da biblioteca fica desatualizado frente às mudanças do Instagram e Reddit, reforçando que: (a) o auto-update do `yt-dlp` local é essencial; e (b) o resolvedor Kotlin nunca deve matar o fallback para `yt-dlp` com falsos códigos terminais.

---

## 8. Investigação 7: Testes Reproduzíveis de Diagnóstico (`Rc1DiagnosticAuditTest.kt`)

Arquivo criado: [Rc1DiagnosticAuditTest.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/test/java/com/devwolf/downloader/resolvers/Rc1DiagnosticAuditTest.kt) (sem alteração em código de produção).

Casos de teste implementados com `MockWebServer` e payloads sanitizados:
1. `instagramClassifyFailureTriggersFalsePositiveCaptchaOnNormalPolarisHtmlShell`: prova que HTML HTTP `200` normal com `"arkose_captcha"` / `"google_recaptcha"` / `"/challenge/"` em `<script>` dispara `CAPTCHA_REQUIRED` mesmo com `hasSessionCookie = true`, e mascara login walls subjacentes.
2. `instagramDnrOmitsCookieInStrategy1AbortsOnFalseCaptchaAndBlocksYtdlpFallback`: prova que `InstagramNativeResolver` envia `Cookie = null` e `DEFAULT_DESKTOP_CHROME_UA` na Estratégia 1, aborta em 1 requisição com `CAPTCHA_REQUIRED`, nunca chama Estratégia 2/3 e bloqueia fallback para `yt-dlp` no `EngineRouter`.
3. `instagramStrategy3GapsMissingAlIosUrlMediaIdAndDefaultIgAppId`: prova que `extractPageContext` ignora `<meta property="al:ios:url" content="instagram://media?id=...">`, fazendo a Estratégia 3 cair em `?__a=1&__d=dis` sem `X-IG-App-ID`.
4. `redditHttp403FastlyWafBlockIsMisclassifiedAsTerminalPrivateMediaBlockingYtdlpFallback`: prova que `GalleryDlLocalResolver` converte HTML 403 (`"whoa there, pardner!"`) em `PRIVATE_MEDIA` terminal (bloqueando `yt-dlp`), ignora `request.cookieHeader` e diverge de `ErrorClassifier.fromHttpStatus(403, body)` (`EXTRACTION_FAILED`).
5. `redditMobileShareLinkUnresolvedRedirectAppendsJsonToSharePathAndFailsWithPrivateMedia`: prova que quando `/r/<sub>/s/<id>` recebe 403 no `resolveFinalUrl`, o path `/s/<id>` não resolvido é transformado em `/s/<id>.json?raw_json=1` e falha como `PRIVATE_MEDIA`.
6. `errorClassifierDistinguishesYtdlpReddit403FromGenuinePrivateMedia`: prova que `ErrorClassifier.fromYtdlpOutput` não classifica `HTTP Error 403: Blocked` do Reddit como `PRIVATE_MEDIA`, confirmando que o bug está isolado em `GalleryDlLocalResolver.kt:155-162`.

---

## 9. Causas Comprovadas, Hipóteses Restantes e Plano de Correções Recomendadas

### Resumo de Evidências
- **Comprovado (Código + HTTP ao vivo + JUnit)**:
  - `IG-1`: `InstagramParsers.classifyFailure` dá falso positivo `CAPTCHA_REQUIRED` em qualquer HTML Polaris contendo `"captcha"` em `<script>`.
  - `IG-2`: `EmbedCaptionedStrategy` roda antes das estratégias autenticadas, sem `Cookie` e sem o `User-Agent` da sessão; `extractPageContext` não lê `al:ios:url` nem decodifica `shortcode -> media_id`; `AuthorizedWebApiStrategy` usa `www.instagram.com` sem `X-IG-App-ID` padrão / `X-ASBD-ID` / `lsd`.
  - `IG-3`: Aborto imediato na Estratégia 1 bloqueia Estratégia 2, Estratégia 3 e `yt-dlp`.
  - `RD-1 / RD-2`: `GalleryDlLocalResolver.resolveReddit` mapeia qualquer `403` para `PRIVATE_MEDIA` terminal (bloqueando `yt-dlp`), omite `cookieHeader` e não valida se `/s/` foi resolvido para `/comments/`.
- **Hipóteses Restantes (a confirmar em dispositivo físico após correções)**:
  - Mesmo após corrigir `classifyFailure` e priorizar a sessão, publicações específicas do Instagram podem exigir consulta GraphQL (`PolarisPostActionLoadPostQueryQuery` ou equivalente atual com `lsd` + `csrftoken`) ou `i.instagram.com/api/v1/media/{id}/info/` caso o HTML web não traga `xdt_api` embutido para o User-Agent do WebView.
  - Em redes móveis específicas onde o Reddit bloqueia tanto `.json` quanto `yt-dlp` sem sessão, pode ser necessário usar `old.reddit.com` HTML/meta tags ou orientar sessão opcional de Reddit caso o WAF bloqueie requisições anônimas do dispositivo.

### Plano de Correções Recomendadas (Próxima Fase — Engenharia)

1. **Instagram — Classificação de Erros (`InstagramParsers.kt`)**:
   - Remover correspondência ampla de `"captcha"`/`"/challenge/"` em `<script>`/JS bruto. Só retornar `CAPTCHA_REQUIRED` quando: `finalUrl` redirecionar para `/challenge/` ou `/checkpoint/`, OU payload JSON estruturado tiver `"message": "challenge_required"` / `"checkpoint_required"` / `"challenge_url"`, OU texto visível de DOM (fora de `<script>`/`<style>`) exibir desafio explícito.
   - Avaliar `MEDIA_UNAVAILABLE` (404), `PRIVATE_MEDIA` (`"is_private": true` / texto visível) e `AUTH_REQUIRED` (`/accounts/login/` / `"require_login": true`) **antes** de fallback genérico para `EXTRACTION_FAILED`.
2. **Instagram — Ordem Session-First e Fallback (`InstagramNativeResolver.kt`)**:
   - Quando `hasSession == true`, executar **`WebPageJsonStrategy` -> `AuthorizedWebApiStrategy` -> `EmbedCaptionedStrategy` (anônima)** (alinhado ao comportamento observado no Rposty), enviando `Cookie` e `request.userAgent` (do WebView) nas estratégias autenticadas.
   - Não abortar o DNR na Estratégia 1 quando ela é anônima ou quando `classifyFailure` não for inequívoco (`RATE_LIMITED` 429 ou JSON explícito de `PRIVATE_MEDIA`). Não propagar `sawAuthWall` de estratégia anônima se estratégias autenticadas/fallback ainda puderem tentar.
3. **Instagram — `media_id` + API v1 + `yt-dlp` UA (`InstagramParsers.kt`, `InstagramNativeResolver.kt`, `DevwolfDownloadService.kt`)**:
   - Adicionar suporte a `<meta property="al:ios:url" content="instagram://media?id=...">` em `extractPageContext` e fallback determinístico `shortcodeToMediaId(shortcode)` em Kotlin.
   - Na `AuthorizedWebApiStrategy`, consultar `https://i.instagram.com/api/v1/media/{mediaId}/info/` com `X-IG-App-ID` (padrão `936619743392459`), `X-CSRFToken`, `Cookie` e `User-Agent` da sessão (respeitando `baseUrl` quando sobrescrito em testes).
   - No fallback `yt-dlp` (`DevwolfDownloadService.kt`), passar `--user-agent` com `igSession?.userAgent` quando houver sessão do Instagram (evitando divergência entre cookie do WebView e UA do `yt-dlp`).
4. **Reddit — Diferenciação de HTTP 403 e Redirect `/s/` (`GalleryDlLocalResolver.kt`)**:
   - Substituir o `if (resp.statusCode == 403) throw PRIVATE_MEDIA` por inspeção do corpo/Content-Type: retornar `PRIVATE_MEDIA` **somente** se JSON/HTML indicar explicitamente comunidade/post privado (`"reason": "private"`, `"subreddit_type": "private"`, `"this community is private"`), `AUTH_REQUIRED` para `401` / quarentena / login obrigatório, e **`EXTRACTION_FAILED`** (recuperável, acionando fallback `yt-dlp`) para bloqueios WAF/Fastly (`"whoa there, pardner"`, HTML 403, Cloudflare).
   - Encaminhar `request.cookieHeader` se presente, usar `User-Agent` compatível (ou fallback para `old.reddit.com` / HTML OpenGraph) e validar `/s/<id>` não resolvido como `EXTRACTION_FAILED` para delegar ao `yt-dlp` imediatamente.

---

## 10. Correções Implementadas e Validadas na `v3.0.0-rc.2`

Todas as causas-raiz comprovadas na `v3.0.0-rc.1` foram corrigidas e validadas com suítes automatizadas antes da geração do APK `v3.0.0-rc.2` (`versionCode 30002`):

### Alterações de Código Implementadas

1. **Classificação de Erros e Extração de `media_id` no Instagram ([InstagramParsers.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramParsers.kt))**:
   - `classifyFailure` agora faz parse HTML com `Jsoup`, verifica formulários/widgets explícitos de desafio (`form[action*=/challenge]`, `recaptcha`/`hcaptcha`/`arkose`) e remove `<script>`, `<style>`, `<noscript>`, `<meta>` e `<link>` antes de inspecionar o texto visível do DOM. Strings inline do bootloader Polaris (`"arkose_captcha"`, `"google_recaptcha"`, `"/challenge/"`) não disparam mais `CAPTCHA_REQUIRED`.
   - `CAPTCHA_REQUIRED` agora é retornado apenas em redirecionamento de path (`/challenge/` ou `/checkpoint/`), payload JSON estruturado (`"challenge_required"`, `"checkpoint_required"`, `"challenge_url"`, objeto `"challenge"`) ou desafio visível no DOM.
   - `extractPageContext` agora extrai `mediaId` também das meta tags `<meta property="al:ios:url">` e `<meta property="al:android:url">` (`media?id=...`).
2. **Ordem *Session-First* e Isolamento de Estratégias no DNR ([InstagramNativeResolver.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/InstagramNativeResolver.kt))**:
   - Quando `hasSession == true`, a execução segue `WebPageJsonStrategy` -> `AuthorizedWebApiStrategy` -> `EmbedCaptionedStrategy` (mantendo `EmbedCaptionedStrategy` primeiro apenas em modo anônimo).
   - Estratégias secundárias/anônimas (`EmbedCaptionedStrategy`, `AuthorizedWebApiStrategy`) não envenenam `sawAuthWall` nem abortam o DNR em HTTP 404 fora da página principal, garantindo transição limpa entre estratégias e fallback para `yt-dlp` via `EXTRACTION_FAILED`.
3. **Alinhamento de `User-Agent` no `yt-dlp` e Diagnóstico por Estratégia ([DevwolfDownloadService.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt), [PerformanceTracker.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/PerformanceTracker.kt))**:
   - Adicionado `DevwolfDownloadService.resolveYtdlpUserAgent`: para `platform == "instagram"`, passa `--user-agent` com o `User-Agent` salvo do WebView no `SecureVault` (evitando divergência entre o `sessionid` em `scoped.cookies.txt` e o `User-Agent` padrão no fallback `yt-dlp`).
   - `DevwolfDownloadService` e `PerformanceTracker` agora registram `strategyAttempts` (inclusive em falhas intermediárias e fallback `yt-dlp-single-pass`) e `httpStatus` no `buildDiagnosticsReport` e no `sanitizedLog`, com redação automática de cookies, tokens e parâmetros de query assinados.
4. **Diferenciação de HTTP 403 e Links `/r/<sub>/s/<id>` no Reddit ([GalleryDlLocalResolver.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/GalleryDlLocalResolver.kt), [ResolverContract.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/resolvers/ResolverContract.kt), [UrlExtractor.kt](file:///c:/Users/Rafa/Desktop/Projetos/Devwolf%20Downloader/android/app/src/main/java/com/devwolf/downloader/UrlExtractor.kt))**:
   - Adicionado `classifyRedditHttpError` (e alinhamento em `ErrorClassifier.fromHttpStatus`): bloqueios WAF/Fastly HTTP `403` (`"whoa there, pardner!"`, `"blocked by network security"`, `"403 blocked"`, HTML 403 genérico) retornam `EXTRACTION_FAILED` (recuperável), acionando fallback para `old.reddit.com` `.json` e depois `yt-dlp`. `PRIVATE_MEDIA` permanece restrito a JSON/aviso explícito de comunidade/post privado.
   - `resolveReddit` encaminha `request.cookieHeader` e `User-Agent` compatível, resolve `/r/<sub>/s/<share_id>` com `Accept: text/html...`, extrai `<link rel="canonical">` / `og:url` caso receba HTTP `200` HTML sem 3xx, proíbe anexar `.json` a paths `/s/<id>` não resolvidos (`buildRedditJsonUrl` retorna `null`) e falha com `EXTRACTION_FAILED` para delegar ao `yt-dlp`.
   - `UrlExtractor.TRACKING_PARAMS` passa a remover `context`, `rdt` e `ref_source`, preservando `/r/<sub>/s/<id>` intacto.

### Validação Automatizada (`v3.0.0-rc.2`)
- **Frontend (`vitest run`)**: `5` suítes / `29` testes aprovados (`0` falhas).
- **Android (`testDebugUnitTest --rerun-tasks`)**: `7` suítes / `39` testes aprovados (`0` falhas, `0` ignorados):
  - `Rc1DiagnosticAuditTest` (6/6 — atualizado para validar as correções da `v3.0.0-rc.2` e regressão multi-plataforma/carrosséis)
  - `PerformanceAndErrorContractTest` (10/10 — inclui alinhamento de UA do `yt-dlp` e registro sanitizado de `strategyAttempts` + `httpStatus`)
  - `DnrAndLocalResolversIntegrationTest` (7/7)
  - `InstagramParsersAndErrorTest` (6/6)
  - `UrlExtractorTest` (5/5)
  - `StatusModeProcessorTest` (4/4)
  - `ExampleUnitTest` (1/1)
