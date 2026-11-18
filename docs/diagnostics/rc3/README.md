# Auditoria Técnica e Arquitetura `v3.0.0-rc.3`

Documento técnico consolidando as investigações de causa raiz, decisões de conformidade e correções da camada de resolvedores locais (`@engines`) para a **Release Candidate 3 (`v3.0.0-rc.3`)**.

---

## 1. Pinterest: Correção de Pins de Vídeo (`GalleryDlLocalResolver.kt` & `HttpMediaDownloader.kt`)

### Causa Raiz Comprovada (`v3.0.0-rc.2`)
1. Em Pins de vídeo modernos do Pinterest (especialmente *Idea/Story Pins* e vídeos HLS), `videos.video_list` contém frequentemente apenas fluxos HLS `.m3u8` (`V_HLSV3_MOBILE`, `V_HLSV4`) ou o vídeo é carregado dinamicamente fora de `__PWS_DATA__`, enquanto `images.orig` e `<meta property="og:image">` trazem a **miniatura de capa** (`.jpg`).
2. `GalleryDlLocalResolver.extractBestPinterestVideo` ignorava entradas `.m3u8` e retornava `null`. Em seguida, `parsePinterestPwsJson` e `parsePinterestHtml` caíam imediatamente no fallback de imagem (`images.orig` / `og:image`), devolvendo `MediaItemType.IMAGE` com status de sucesso e **impedindo o `EngineRouter` de delegar o Pin de vídeo ao `yt-dlp`**.

### Correções Implementadas
- **Detecção explícita de Pin de vídeo**: `parsePinterestHtml` e `parsePinterestPwsJson` verificam `is_video == true`, presença de `videos` / `video_list`, blocos de vídeo em `story_pin_data.pages[].blocks[]` (`block_type == 3` ou `video != null`), `@type == "VideoObject"` em `application/ld+json`, `<meta property="og:type" content="video...">`, `<video>`, URLs `v.pinimg.com/videos/` no HTML/JSON e `request.format == "video"`.
- **Seleção de melhor `.mp4` direto**: Quando há streams `.mp4` diretos (`V_1080P`, `V_720P`, `V_EXP7`, `V_480P`), o resolvedor seleciona o de maior resolução (descartando `.m3u8` e `.ts`).
- **Delegação obrigatória para `yt-dlp`**: Se qualquer indicador de vídeo estiver presente e nenhum `.mp4` direto for encontrado (ex.: apenas `.m3u8` HLS ou miniatura), o resolvedor **nunca** retorna `og:image` / `images.orig` como sucesso — lança `ResolverException(DevwolfErrorCode.EXTRACTION_FAILED)` para que o `EngineRouter` acione o `yt-dlp` (que mescla HLS nativamente).
- **Blindagem de MIME e *magic bytes* (`HttpMediaDownloader.kt`)**: Respostas com `Content-Type: image/*` ou `application/vnd.apple.mpegurl`, ou arquivos cujo cabeçalho comece com assinatura de imagem (`JPEG FF D8 FF`, `PNG`, `GIF8`, `WEBP`), HTML (`<!doctype` / `<html`) ou playlist `#EXTM3U`, são rejeitados quando `item.type == MediaItemType.VIDEO`.

---

## 2. TikTok: Causa Raiz de `EXTRACTOR_OUTDATED` e Correção (`ResolverContract.kt`, `UrlExtractor.kt`)

### Causa Raiz Comprovada
1. **Falso positivo no classificador (`ErrorClassifier.fromYtdlpOutput`)**: O `yt-dlp` anexa automaticamente a todos os `ExtractorError` o rodapé padrão `"; please report this issue on https://github.com/yt-dlp/yt-dlp/issues ... Make sure you are using the latest version"` (ou `"Confirm you are on the latest version using yt-dlp -U"`), e mensagens de falha no TikTok frequentemente começam com `"Unable to extract webpage video data"`. A regra anterior em `ResolverContract.kt` casava `"unable to extract"`, `"extractor"` e `"please report this issue"` diretamente para `DevwolfErrorCode.EXTRACTOR_OUTDATED`. Assim, **qualquer** erro upstream do TikTok (ex.: `status code 10204`, bloqueio regional ou falha de parsing web) era exibido como *"Extrator local desatualizado"* mesmo com o binário `yt-dlp` recém-atualizado.
2. **Fator secundário de runtime (`DevwolfDownloadService.kt` / `LocalEngineManager.kt`)**: Sobrescrever `--user-agent` globalmente para plataformas sem sessão customizada (como TikTok) pode conflitar com os `User-Agent`s específicos que os extratores internos do `yt-dlp` utilizam.

### Correções Implementadas
- `ErrorClassifier.fromYtdlpOutput` agora retorna `EXTRACTOR_OUTDATED` **somente** quando há aviso explícito de idade do binário (`"Your yt-dlp version .* is older than 90 days"` / `"yt-dlp is more than 90 days old"`) ou pedido explícito `"please update yt-dlp"`, após avaliar erros terminais (`RATE_LIMITED`, `PRIVATE_MEDIA`, `AUTH_REQUIRED`, `MEDIA_UNAVAILABLE`, `CAPTCHA_REQUIRED`, `UNSUPPORTED_MEDIA`, `CANCELLED`, `NETWORK_ERROR`).
- O rodapé genérico `"please report this issue ... Make sure you are using the latest version"` é removido da mensagem preservada (`stripYtdlpReportBoilerplate`), mantendo a causa original sanitizada (ex.: `Unable to extract webpage video data`, `status code 10204`) classificada corretamente como **`EXTRACTION_FAILED`**.
- `UrlExtractor.TRACKING_PARAMS` passa a limpar também parâmetros de compartilhamento móvel do TikTok (`_d`, `tt_from`, `share_app_id`, `share_link_id`, `sender_web_id`, `u_code`, `sec_uid`, além de `_t`, `_r`, `is_from_webapp`, `sender_device`), preservando URLs curtas `vm.tiktok.com` / `vt.tiktok.com` / `/t/`.

---

## 3. Suporte Nativo ao Threads (`threads.com` & `threads.net`) e Avaliação de `tribixbite/yt-dlp-threads`

### Avaliação do `tribixbite/yt-dlp-threads`
- **Repositório**: `https://github.com/tribixbite/yt-dlp-threads`
- **Licença**: **Unlicense** (domínio público, compatível com o projeto).
- **Situação upstream no `yt-dlp`**: O `yt-dlp` oficial ainda não possui extrator embutido para `threads.net` / `threads.com` (cai no extrator `[generic]` e falha com `Unsupported URL`). O plugin `tribixbite/yt-dlp-threads` extrai metadados das páginas do Threads buscando payloads `<script type="application/json" data-sjs>` (`thread_items`, `video_versions`, `image_versions2`, `carousel_media`) e tags OpenGraph com headers apropriados.
- **Decisão arquitetural**: Em vez de empacotar e manter um zipapp de plugin Python externo no Android, implementamos o **`ThreadsNativeResolver.kt`** nativo em Kotlin sobre a arquitetura DNR (`SafeHttpClient`), independente do `InstagramNativeResolver.kt`:
  - **Roteamento (`UrlExtractor.kt`, `EngineRouter.kt`)**: Reconhece `threads.net` e `threads.com` (`/@user/post/{code}`, `/t/{code}`, `/post/{code}`), remove tracking params (`xmt`, `slof`, `igsh`, `utm_*`) e roteia `threads` para `EngineKind.DNR` (`ThreadsNativeResolver`) com fallback para `YT_DLP` em falhas recuperáveis.
  - **Estratégias**: `ThreadsWebPageJsonStrategy` (lê `data-sjs` / `thread_items` / `carousel_media` / `ld+json` / OpenGraph com suporte a cookie opcional do `SecureVault`) e `ThreadsEmbedMetaStrategy` (`/@user/post/{code}/embed` anônimo).
  - **Isolamento de domínio (`SafeHttpClient.kt`)**: Cookies do Threads são escopados estritamente para `*.threads.net` e `*.threads.com`, jamais enviados a CDNs (`cdninstagram.com`, `fbcdn.net`).

---

## 4. Cofre Multi-Plataforma (`SecureVault.kt`) e Autenticação Oficial do Reddit

### Arquitetura Multi-Plataforma (`SecureVault.kt`)
- `SecureVault` foi expandido com `StoredPlatformSession` para armazenar credenciais criptografadas isoladas por plataforma (`instagram`, `reddit`, `threads`, etc.) via `EncryptedSharedPreferences` + Android Keystore:
  - **Compatibilidade retroativa garantida**: Chaves legadas do Instagram (`session_cookie_instagram`, `session_ua_instagram`, `session_updated_instagram`, `session_source_instagram`) continuam sendo lidas e preservadas sem exigir novo login do usuário.
  - **Campos suportados**: `platform`, `configured`, `authType` (`webview_cookie` | `oauth_token` | `cookie`), `username`, `cookieHeader`, `accessToken`, `refreshToken`, `clientId`, `expiresAtEpochMs`, `userAgent`, `updatedAt`, `source`.
  - **Ponte segura (`getPlatformSessions()`)**: Expõe apenas metadados sanitizados para a UI React; cookies brutos e tokens OAuth nunca são enviados à ponte JS nem registrados em logs (`SafeHttpClient.sanitizeForDiagnostics`).
  - **Revogação isolada (`revokePlatformSession(platform)`)**: Apaga apenas as credenciais da plataforma selecionada sem afetar as demais.

### Arquitetura Reddit Data API / OAuth2 & Conformidade de Política
1. **Fluxo Oficial OAuth2 Installed App (PKCE)**:
   - O aplicativo suporta autenticação via **Installed App** registrado pelo próprio usuário em `https://www.reddit.com/prefs/apps` / `developers.reddit.com` (sem *client secret* embutido no código-fonte):
     - Autorização via `https://www.reddit.com/api/v1/authorize.compact` com `response_type=code`, `code_challenge` (S256), `duration=permanent` e `scope=read`.
     - Troca de código em `https://www.reddit.com/api/v1/access_token` e armazenamento criptografado no `SecureVault`.
   - Quando `accessToken` está configurado para `reddit`, `GalleryDlLocalResolver.resolveReddit` consulta `https://oauth.reddit.com/.../.json?raw_json=1` com:
     - `Authorization: Bearer <accessToken>`
     - `User-Agent` conforme exigência da API do Reddit: `android:com.devwolf.downloader:v3.0.0-rc.3 (by /u/<username>)`
     - Sem envio de `Cookie` para o endpoint OAuth e sem envio do token `Bearer` para redirecionamentos de shortlinks `redd.it`.
2. **Sessão WebView Opcional do Reddit**:
   - Alternativamente, o usuário pode autenticar em `https://www.reddit.com/login/` via WebView oficial do app; os cookies ficam restritos estritamente a `*.reddit.com`.
3. **Conformidade e Eliminação de Evasão**:
   - Proibido embutir segredos de terceiros, extrair sessões de outros aplicativos ou alternar hosts (`old.reddit.com`) para contornar bloqueios WAF/HTTP 403.
   - Quando o Reddit retorna HTTP `401` ou bloqueio HTTP `403` sem sessão/OAuth configurada, o resolvedor classifica como **`AUTH_REQUIRED`** com mensagem específica do Reddit (*"O Reddit exigiu autenticação para acessar esta mídia. Conecte sua conta do Reddit em Contas e Sessões."*), oferecendo autenticação do Reddit — nunca do Instagram.
