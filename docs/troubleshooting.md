# Diagnóstico de Problemas — Devwolf Downloader (Android `v3.x`)

> O guia de diagnóstico da arquitetura híbrida (VPS, Cobalt, SSE, proxy reverso) está preservado na branch `archive/vps-hybrid` e na tag `v2.0.0`.

---

## 1. Limitações reais (comportamento esperado)

- **Bloqueios de plataforma**: redes sociais mudam APIs, aplicam limite de requisições (`HTTP 429`), exigem login ou impõem CAPTCHA/DRM. Esses casos são **terminais** (`RATE_LIMITED`, `AUTH_REQUIRED`, `PRIVATE_MEDIA`, `CAPTCHA_REQUIRED`): o app para imediatamente e não tenta contornar a proteção.
- **Login opcional**: Instagram, Threads e Reddit aceitam uma sessão opcional em **Ajustes → Contas e Sessões** (WebView oficial; cookies em `EncryptedSharedPreferences` + Android Keystore).
- **Tamanho máximo**: o `yt-dlp` baixa no máximo **500 MB por arquivo**. Acima disso o job falha com uma mensagem explícita; tente uma qualidade menor.
- **Somente HTTPS**: o app bloqueia tráfego HTTP sem criptografia; links `http://` são promovidos para `https://` automaticamente.
- **SSRF**: os resolvedores nativos (DNR / resolvedor local) validam DNS público em cada redirecionamento. O `yt-dlp` faz suas próprias requisições e não passa por essa validação; ele só recebe URLs de plataformas suportadas já validadas.

## 2. Problemas comuns

### "Motor de extração desatualizado"
Atualize o `yt-dlp` na aba **Motores** (**Verificar / Atualizar agora**). Se a quebra for recente na plataforma, experimente o canal `Nightly`.

### Download fica em "Na fila…"
Já existem 2 downloads em execução. O job começa automaticamente quando um deles terminar; ele também pode ser cancelado da fila.

### "FFmpeg indisponível no dispositivo"
Falha local ao extrair/iniciar o FFmpeg (mescla de vídeo+áudio, Modo Status, MP3). Feche e reabra o app; se persistir, use **Motores → Verificar / Atualizar agora** e **Ajustes → Limpar cache**.

### "Download interrompido pelo sistema"
O Android encerrou o processo durante o download (economia de bateria). Toque em **Repetir**. Desativar a otimização de bateria para o app reduz a ocorrência.

### Motor não atualiza
O atualizador espera não haver downloads ativos ou na fila. Aguarde a conclusão ou cancele os downloads.

### Galeria / Modo Status
Mídias são salvas em `Movies/Devwolf Downloader`, `Pictures/Devwolf Downloader` e `Music/Devwolf Downloader` via `MediaStore`. Vídeos em codec incompatível (VP9/AV1/WebM) são transcodificados para MP4 (`H.264` + `AAC`) no Modo Status, preservando a proporção.

### Exportar diagnóstico
**Ajustes → Diagnóstico e Performance** gera um log local sanitizado (cookies, tokens e query strings assinadas são removidos) com plataforma, motor/versão, estratégias, `httpStatus`, `exitCode` e tempos.
