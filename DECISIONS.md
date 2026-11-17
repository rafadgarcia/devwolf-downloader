# DECISIONS.md — Decisões Arquiteturais

## 1. API FastAPI + Fila SQLite WAL + Worker Separado
- **Decisão**: Usar Python + FastAPI para a API HTTP e um processo worker separado compartilhando banco SQLite em modo WAL com transações atômicas e leases de execução, sem Redis nem banco externo.
- **Motivo**: Uso pessoal em única VPS com baixo consumo de RAM/CPU, recuperação segura após reinício (`leases` expirados), simplicidade operacional e backup direto via `sqlite3 .backup`.
- **Restrição**: O volume SQLite deve ser disco local da VPS (nunca filesystem de rede/NFS).

## 2. Progresso via SSE com Fallback para Polling
- **Decisão**: Fornecer `GET /api/jobs/{id}/events` via Server-Sent Events (SSE) na mesma origem e manter `GET /api/jobs/{id}` como fallback automático no cliente.
- **Motivo**: Conexões móveis e proxies reversos podem interromper streams SSE; o fallback garante atualização contínua sem perder estado nem simular progresso.

## 3. Autenticação Pessoal com Argon2id, Sessão em Cookie e CSRF
- **Decisão**: Hash de senha com Argon2id (sem senha padrão pública), cookie `HttpOnly` + `SameSite=Lax` + `Secure` (quando HTTPS), proteção CSRF (`X-CSRF-Token` + checagem de origem) e rate limiting em login e criação de jobs.
- **Motivo**: Acesso estritamente pessoal sem cadastro público, compatível com mesma origem e fluxo de `POST /share-target` da PWA.

## 4. Proteção SSRF e Roteamento Explícito por Domínio
- **Decisão**: Rejeitar qualquer URL fora da allowlist exata/subdomínios em `config/routes.yaml`, proibir IPs literais, credenciais embutidas, redes privadas/loopback/link-local/IPv6 reservados e validar cada salto de redirect com verificação de DNS antes da conexão HTTP.
- **Motivo**: Evitar SSRF contra serviços internos da VPS, metadata cloud e ataques via URLs encurtadas ou domínios impostores.

## 5. Atualização do Cobalt via Host Systemd Timer + Rollback Automático
- **Decisão**: Substituir o padrão `containrrr/watchtower` (repositório oficial arquivado em 17/12/2025) por script no host (`ops/update-cobalt.sh`) acionado por `systemd timer` (`ops/cobalt-update.timer`), com `flock`, comparação de digest de tag major fixada (`ghcr.io/imputnet/cobalt:11`), sinalização de manutenção via arquivo JSON atômico, verificação de readiness por até 90s e rollback automático com cooldown de digest rejeitado.
- **Motivo**: Nunca montar `docker.sock` dentro da aplicação, evitar loops de reinício por imagem quebrada, não tocar em outros containers da VPS e manter outros motores (`yt-dlp`, `gallery-dl`) operando durante a janela de atualização.

## 6. Atualização Separada de yt-dlp e gallery-dl no Worker
- **Decisão**: Não executar `pip install -U` no boot de produção. Fixar versões no build da imagem do worker e utilizar `ops/check-engine-updates.sh` e `ops/update-worker.sh` para verificar releases oficiais, reconstruir, testar e drenar jobs antes de promover.
- **Motivo**: Reprodutibilidade, rollback rápido com imagem anterior preservada e isolamento de falhas entre motores.

## 7. Frontend Servido na Mesma Origem
- **Decisão**: Servir o build estático da PWA (`frontend/dist`) na mesma origem da API FastAPI (ou atrás do mesmo proxy reverso da VPS).
- **Motivo**: Simplifica cookies `SameSite`, política CSP, SSE sem CORS complexo e o endpoint `/share-target` do Android.

## 8. Arquitetura Híbrida Android (Capacitor + Motor Nativo Kotlin) — Fase 2
- **Decisão**: Empacotar a interface React/Vite em app Android nativo via Capacitor (`com.devwolf.downloader`, esquema `https://localhost`) com plugin nativo Kotlin `DevwolfNative` integrando `youtubedl-android` (`yt-dlp` + `FFmpeg` + `aria2c`), Foreground Service (`dataSync`), MediaStore (`Devwolf Downloader`), `ACTION_SEND` Sharesheet e cofre criptografado (`EncryptedSharedPreferences`).
- **Motivo**: Permite execução 100% local no dispositivo quando desejado ou offline da VPS, mantém a VPS como motor remoto com fallback automático (`Automático` / `Local` / `VPS`) e habilita Modo Status (WhatsApp) e entrega direta na galeria do Android sem depender apenas das limitações de PWA.
- **Segurança**: Sessões opcionais armazenadas localmente nunca são enviadas à VPS; comunicação com a VPS suporta Bearer token de sessão para evitar restrições de cookie cross-origin no WebView Capacitor.
