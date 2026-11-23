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
