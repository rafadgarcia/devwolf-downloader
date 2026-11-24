# Guia de Manutenção — Devwolf Downloader (Android `v3.x`)

> O guia de manutenção da arquitetura híbrida (VPS, Cobalt, Docker, backup SQLite) está preservado na branch `archive/vps-hybrid` e na tag `v2.0.0`.

---

## 1. Atualização do motor `yt-dlp`

- O `LocalEngineManager` verifica atualizações automaticamente ~15 s após abrir o app e depois a cada 24 h (canal `Stable` por padrão; `Nightly` é opt-in na aba **Motores**).
- A atualização só é aplicada quando **não há downloads ativos ou na fila**. Após atualizar, o binário é validado (`YoutubeDL.version`); se a validação falhar, o rollback para a versão anterior é automático.
- Atualização (**Verificar / Atualizar agora**) e rollback manuais ficam na aba **Motores**.

## 2. Fila, histórico e arquivos temporários

- A fila nativa (`files/devwolf_local_queue.json`) é a fonte de verdade: mantém até 100 jobs (miniaturas apenas nos 24 mais recentes). Ela fica em memória e só é gravada em disco quando um job muda de status ou, para progresso puro, no máximo a cada 1,5 s.
- O frontend mantém uma cópia em `localStorage` apenas como cache de exibição; na inicialização ela é reconciliada com a fila nativa (`getLocalDownloadQueue`).
- No máximo **2 downloads** rodam ao mesmo tempo (`DevwolfDownloadService.MAX_CONCURRENT_DOWNLOADS`); os demais aparecem como **Na fila…**.
- Arquivos temporários ficam em `cacheDir/jobs/<jobId>` e são removidos ao final de cada job. **Ajustes → Limpar cache** remove resíduos.
- **Baixados → Limpar histórico** remove jobs concluídos/falhos/cancelados sem apagar mídias da Galeria.

## 3. Build e assinatura de release

```bash
cd frontend && npm ci && npm test && npm run build && npx cap sync android
cd ../android && ./gradlew testDebugUnitTest assembleRelease
```

- Com keystore própria: defina `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` e `RELEASE_KEY_PASSWORD`.
- Sem keystore, o build de release **falha de propósito**. Para assinar com a chave de debug local (por exemplo, para manter compatibilidade de atualização com APKs antigos assinados com ela), habilite explicitamente:
  - `DEVWOLF_ALLOW_DEBUG_SIGNING=true ./gradlew assembleRelease`, ou
  - `./gradlew assembleRelease -PallowDebugSigning=true`.
- O CI usa `DEVWOLF_ALLOW_DEBUG_SIGNING=true` porque seus APKs são artefatos de teste.

## 4. Versionamento

A cada release, atualize juntos: `versionCode`/`versionName` em `android/app/build.gradle`, `version` em `frontend/package.json`, `REDDIT_OAUTH_USER_AGENT` em `InstagramSessionActivity.kt`, `CHANGELOG.md` e `TASKS.md`.

## 5. Publicação de releases para a atualização no app

A seção **Ajustes → Atualizações** lê os releases públicos do GitHub. Para um release ser oferecido:

- A tag segue SemVer (`v3.0.0`, `v3.0.0-rc.7`). Pre-releases só aparecem com **Incluir versões de teste** ligado.
- Os APKs são anexados como `devwolf-downloader-<tag>-<abi>-release.apk` (ex.: `-arm64-v8a-`, `-armeabi-v7a-`, `-x86_64-`) e `devwolf-downloader-<tag>-universal-release.apk`.
- Todos os APKs são assinados com a **mesma chave** do app já instalado; caso contrário o app recusa a instalação.

```bash
TAG=v3.0.0-rc.7
OUT=android/app/build/outputs/apk/release
cp $OUT/app-arm64-v8a-release.apk   devwolf-downloader-$TAG-arm64-v8a-release.apk
cp $OUT/app-universal-release.apk   devwolf-downloader-$TAG-universal-release.apk
gh release create $TAG --prerelease --title "Devwolf Downloader $TAG" --notes-file notas.md \
  devwolf-downloader-$TAG-arm64-v8a-release.apk devwolf-downloader-$TAG-universal-release.apk
```

O app escolhe o asset pelo **nome do arquivo**, por isso os APKs são copiados com o nome no padrão antes do upload.
