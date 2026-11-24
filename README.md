# Devwolf Downloader (`com.devwolf.downloader`)

**Copiou. Abriu. Baixou.**

O **Devwolf Downloader** é um aplicativo Android nativo e 100% local (Capacitor + motor Kotlin + `yt-dlp` + `FFmpeg`) para baixar vídeos, fotos, áudios e carrosséis públicos autorizados de **Instagram**, **Threads**, **YouTube / Shorts**, **TikTok**, **Facebook**, **Twitter/X**, **Pinterest** e **Reddit**, diretamente para a galeria do dispositivo e com **Modo Status (WhatsApp)**.

> **Nota de Arquitetura**: A partir da versão **3.0.0**, o aplicativo opera de forma **100% autônoma e local no Android** (zero dependência de VPS). O código da arquitetura híbrida anterior (FastAPI + Docker + Cobalt) permanece preservado integralmente na branch [`archive/vps-hybrid`](../../tree/archive/vps-hybrid) e na tag `v2.0.0`.

---

## Principais Recursos (`v3.0.0`)

- **Motor Nativo para Instagram & Threads (`DNR`)**: resolve Reels, vídeos, fotos e carrosséis mistos diretamente em Kotlin (`InstagramNativeResolver` e `ThreadsNativeResolver`) sem inicializar Python/yt-dlp, com fallback automático para `yt-dlp` apenas em falhas recuperáveis.
- **Carrosséis Individuais na Galeria (`MediaStore`)**: cada foto e vídeo de um carrossel é baixado e salvo individualmente em `Pictures/Devwolf Downloader` e `Movies/Devwolf Downloader`, preservando ordem (`01`, `02`, ...), resolução e ações individuais (**Abrir**, **Compartilhar** e **Compartilhar todos**).
- **Bolha flutuante de clipboard (opcional)**: atalho lateral do Android; só lê a área de transferência quando tocado e envia links compatíveis ao fluxo normal. Requer autorização de sobreposição.
- **Importador de Status do WhatsApp**: selecionar a pasta `Statuses` pelo seletor de documentos do Android e copiar fotos/vídeos existentes para o álbum do Devwolf. Não busca status no servidor.
- **Resolvedores Locais Multi-Plataforma**:
  - **Instagram & Threads**: `DNR` (primário) -> `yt-dlp` (fallback recuperável).
  - **Pinterest & Reddit**: resolvedor Kotlin local -> `yt-dlp` (vídeos HLS no Pinterest e DASH `v.redd.it` no Reddit).
  - **Twitter/X**: `yt-dlp` -> fallback local para posts só de imagens.
  - **YouTube / Shorts, TikTok, Facebook**: `yt-dlp` em passo único (`--no-simulate --no-warnings --print before_dl:...`) com cookies por plataforma isolados por host.
- **Atualização Real do `yt-dlp` (Stable & Nightly)**: lê a versão real do binário zipapp `yt-dlp` em execução, verifica atualizações automaticamente na inicialização e a cada 24h, suporta canais `Stable`/`Nightly`, valida o binário após atualizar e realiza rollback automático se falhar.
- **Modo Status (WhatsApp)**: remux instantâneo (`-c copy`) quando já compatível com H.264/AAC MP4 ou transcodificação preservando proporção original, sem marca d'água e com corte em segmentos de N segundos.
- **Receptor Nativo (`ACTION_SEND`) & Clipboard Inteligente**: compartilhamento direto de outros apps com início automático e botão rápido **Voltar ao app de origem**, além de detecção automática local ao abrir/focar o app (com deduplicação de 10 minutos).
- **Contas e Sessões Opcionais (`Instagram`, `Reddit`, `Threads`)**: login opcional via WebView oficial ou **Reddit OAuth2 Installed App PKCE** (`EncryptedSharedPreferences` + Android Keystore) com logout isolado por plataforma; mídias públicas não exigem login.
- **Diagnóstico & Performance Local**: telemetria estritamente local (`plataforma`, `motor/versão`, `estratégias`, `httpStatus`, `exitCode`, `tipo de mídia`, `tempos`, `requisições HTTP`) com exportação de log sanitizado nos Ajustes.

---

## Tamanhos de APK & Compatibilidade

- **`app-arm64-v8a-release.apk`**: ~57,7 MiB (recomendado para celulares Android modernos — redução de **73%** em relação ao APK universal de ~217 MiB da `v2.0.0`, compilado com R8 + `shrinkResources`).
- **`app-armeabi-v7a-release.apk`**: ~50,8 MiB (dispositivos ARM 32-bit).
- **`app-x86_64-release.apk`**: ~60,5 MiB (emuladores x86_64).
- **`app-universal-release.apk`**: ~210,7 MiB (todas as ABIs).

Todos os builds mantêm compatibilidade de assinatura com a `v2.0.0` quando compilados com a keystore padrão local, permitindo atualização direta via `adb install -r` sem perder fila nem sessões salvas.

---

## Desenvolvimento Local e Testes

### Frontend (Node 24+)
```bash
cd frontend
npm install
npm test
npm run build
npx cap sync android
```

### Android (JDK 21 + Android SDK 36)
```bash
cd android
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Instalação direta no celular (`arm64-v8a` otimizado):
```bash
adb install -r android/app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

---

## Documentação Adicional

- [`AGENTS.md`](AGENTS.md) — Contratos entre motores, erros estruturados e invariantes de segurança.
- [`DECISIONS.md`](DECISIONS.md) — Decisões arquiteturais das Fases 1, 2 e 3.
- [`docs/DNR-ARCHITECTURE.md`](docs/DNR-ARCHITECTURE.md) — Detalhamento do Devwolf Native Resolver e resolvedores locais.
- [`CHANGELOG.md`](CHANGELOG.md) — Histórico completo de versões.
