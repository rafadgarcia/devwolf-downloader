# Diagnóstico de Problemas e Limitações Reais — DropLink

---

## 1. Limitações Reais de Plataforma (Comportamento Esperado)

1. **Clipboard Web (`navigator.clipboard.readText`)**:
   - Exige contexto seguro (**HTTPS** ou `localhost`), janela em foco e permissão do navegador.
   - Alguns navegadores (especialmente **iOS Safari** e determinados navegadores móveis) exigem gesto explícito do usuário antes de liberar leitura da área de transferência.
   - O DropLink não faz polling em segundo plano nem tenta contornar permissões. Quando bloqueado, o app exibe *"Toque em Colar para importar um link"* e mantém o fluxo manual plenamente funcional.

2. **Web Share Target (Menu Compartilhar do Sistema)**:
   - Funciona em PWAs instaladas no **Android / Chrome** (e navegadores Chromium compatíveis).
   - **iOS Safari não suporta Web Share Target** para receber compartilhamentos do sistema diretamente na PWA. No iOS, copie o link e use o botão **Colar** ou a colagem nativa no campo de URL.
   - No Android, muitos apps enviam a URL dentro do campo `text` (ex.: `"Veja este vídeo https://..."`) e não em `url`. O DropLink trata ambos automaticamente.

3. **Conclusão em segundo plano e salvamento no aparelho**:
   - O servidor conclui o job mesmo que você feche o app. Ao reabrir, o histórico e o card do job permitem baixar o arquivo pronto.
   - O disparo automático de download no dispositivo só ocorre se a página estiver ativa e o navegador autorizar o download. O aplicativo nunca afirma que o arquivo foi salvo na galeria sem confirmação real do navegador.

4. **Bloqueios de plataformas externas**:
   - Redes sociais mudam APIs, aplicam rate-limit (`HTTP 429`), exigem login ou impõem CAPTCHA/DRM.
   - Erros de acesso privado, autorização ou CAPTCHA são tratados como **terminais** — o DropLink não tenta contornar login, DRM ou proteção anti-bot.

---

## 2. Problemas Comuns e Soluções

### Erro de login / CSRF inválido após proxy reverso
- Verifique se `APP_URL` no `.env` corresponde ao domínio HTTPS real usado no navegador.
- Se estiver testando localmente em `http://localhost` sem HTTPS, configure `COOKIE_SECURE=false` no `.env`. Em produção HTTPS, mantenha `COOKIE_SECURE=true`.
- Confirme que o proxy repassa `Host` e `X-Forwarded-Proto: https`.

### Progresso não atualiza em tempo real (SSE travado)
- O frontend alterna automaticamente para polling (`GET /api/jobs/{id}`), mas para restaurar SSE instantâneo configure no proxy:
  - Nginx: `proxy_buffering off;` e `proxy_read_timeout 600s;`
  - Caddy: `flush_interval -1`

### Cobalt aparece como "Offline" ou "Degradado" na aba Motores
1. Verifique se o Cobalt está respondendo no host/rede configurada:
   ```bash
   docker compose exec droplink-worker python -c "import os, httpx; print(httpx.get(os.environ['COBALT_API_URL']).status_code)"
   ```
2. Confirme que `droplink-api` e `droplink-worker` estão conectados à mesma rede Docker do Cobalt (`COBALT_EXTERNAL_NETWORK`).
3. Se o Cobalt usar chave (`Api-Key`), verifique `COBALT_API_KEY` no `.env`.
4. Se o circuit breaker abriu após 3 falhas consecutivas de infraestrutura, ele permanece aberto por 5 minutos e reavalia automaticamente. Enquanto isso, `yt-dlp` e `gallery-dl` continuam processando rotas compatíveis.

### Túnel do Cobalt falha ao baixar mídia (`tunnel` / `redirect`)
- Verifique a variável `API_URL` configurada **dentro do próprio container do Cobalt**: ela precisa ser alcançável pelo worker (ou usar o fluxo compatível configurado na instância).
- Consulte os logs do worker:
  ```bash
  docker compose logs --tail=100 droplink-worker
  ```

### Rollback do Cobalt ocorreu ou atualização ficou em cooldown
- Verifique o histórico de atualização:
  ```bash
  cat data/ops-status/cobalt-updater-status.json
  journalctl -u cobalt-update.service -n 100 --no-pager
  ```
- Quando um digest falha no readiness check (90s), ele entra em cooldown (`rejected_until`) para evitar loop de reinício. Quando uma nova imagem for publicada no registro, a atualização voltará a ocorrer automaticamente (ou force manualmente com `FORCE_UPDATE=1 bash ops/update-cobalt.sh`).

### Disco cheio ou novos jobs recusados
- Por segurança, o worker recusa novos jobs quando o espaço livre cai abaixo de `MIN_FREE_DISK_MB` (padrão `2048` MB / 2 GB).
- Verifique retenção (`FILE_RETENTION_HOURS=24`) e espaço em disco (`df -h`).
