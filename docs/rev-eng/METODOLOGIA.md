# Metodologia e reprodução

## Sequência executada

1. Leitura de `AGENTS.md`, README, decisões, roteamento e código Android/frontend/backend do Devwolf. `git status --short` estava limpo.
2. Identificação SHA-256 e inventário ZIP do APK fornecido, sem executá-lo.
3. Ausência de ferramentas REA conectadas nesta sessão: uso da CLI. `rea doctor --client codex --json` apontou registro ausente/config drift; nenhum arquivo de configuração do Codex foi alterado.
4. A primeira tentativa de REA com Node 24.19.0 foi recusada por requisito de versão. Preparados Node 24.22.1 e Temurin JDK 21.0.13 portáteis em `work/tools` da pasta desta investigação. Não substituem instalações globais. REA 6.9.0 foi adquirido pelo npm/npx; o launcher público do cache foi usado.
5. Download do `jadx-headless-mcp-0.7.1-all.jar` oficial, verificação de SHA-256 e configuração das variáveis somente no processo. JDK e Node também tiveram checksum conferido com os metadados oficiais.
6. REA `inspect-android-package`, `search-android-classes`, três `inspect-android-method` e `trace-android-references`, com JSON completo preservado em `evidence/`.
7. Exportação de fontes/recursos via API JADX contida no mesmo JAR, usando `scripts/ExportApk.java`. Esses arquivos são **evidência auxiliar JADX**, não novos registros de Evidence REA.
8. Leitura de cadeias de resolução, sessão e transferência. Busca textual em 11.211 arquivos Java exportados não encontrou `yt-dlp`, `youtube-dl`, `youtubedl` ou `chaquopy`; o resultado é limitado ao texto exportado. Não cobre splits ausentes, carregamento dinâmico ou strings cifradas. Inventário completo de domínios literais em `evidence/domains.json`; um domínio literal não prova requisição em runtime.
9. Reprodução em memória da incompatibilidade de códigos de erro do Devwolf. Documentação isolada em `docs/rev-eng`.

## Ferramentas

| Ferramenta | Identidade |
|---|---|
| REA CLI | `rea-agents@6.9.0` |
| Node portátil | 24.22.1, Windows x64 |
| Java portátil | Eclipse Temurin JDK 21.0.13 |
| Provedor REA | JADX headless 0.7.1 |
| SHA-256 JAR | `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75` |
| REA bridge | `f9d6d68eadd3d51a89e2debda359454c20534560080d4438f9b9cfe5a19f0b2e` |

Fontes de aquisição: [REA no npm](https://www.npmjs.com/package/rea-agents), [release do provedor JADX](https://github.com/1013503897/jadx-headless-mcp/releases/tag/v0.7.1), [Node 24.22.1](https://nodejs.org/dist/v24.22.1/), [API oficial Adoptium](https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse). Estas referências documentam ferramentas, não a validade atual dos endpoints Instagram.

## Repetir a inspeção

Com Node compatível, JDK completo e o JAR acima disponíveis, em PowerShell:

```powershell
$env:JAVA_HOME = 'C:\caminho\para\jdk-21'
$env:REA_JADX_MCP_JAR = 'C:\caminho\para\jadx-headless-mcp-0.7.1-all.jar'
$apk = 'C:\Users\Rafa\Desktop\Projetos\Devwolf Downloader\docs\Rposty.apk'
Get-FileHash -LiteralPath $apk -Algorithm SHA256
npx -y rea-agents@6.9.0 inspect-android-package $apk --format json
npx -y rea-agents@6.9.0 search-android-classes $apk videodownloader --format json
npx -y rea-agents@6.9.0 inspect-android-method $apk Q8.j M --format json
npx -y rea-agents@6.9.0 inspect-android-method $apk Q8.j N --format json
npx -y rea-agents@6.9.0 inspect-android-method $apk A8.c E --format json
npx -y rea-agents@6.9.0 trace-android-references $apk Q8.j --method-name N --format json
```

Para exportar fontes auxiliares, a partir de `docs/rev-eng`:

```powershell
& "$env:JAVA_HOME/bin/java.exe" -Xmx3g -cp $env:REA_JADX_MCP_JAR scripts/ExportApk.java $apk 'C:\pasta-nova\rposty-export'
```

Escolher diretório novo. O exportador executa o decompilador, não o APK. Os arquivos podem conter avisos e reconstruções incorretas. O export integral ficou na área temporária `work/decompiled`; só fontes selecionadas foram copiadas para esta documentação. Nomes como `p148s8` e `AbstractC3244a` são aliases gerados pelo JADX, inclusive por colisões em filesystem Windows; não são necessariamente nomes DEX originais.

Reprodução do detector de erro, sem rede e sem edição:

```powershell
node scripts/verificar-erros.cjs 'C:\Users\Rafa\Desktop\Projetos\Devwolf Downloader'
```

Requer as dependências frontend já instaladas (TypeScript). Os imports de funções não usadas pelo detector são substituídos por objetos vazios; não há mock da função sob teste. O script extrai as mensagens diretamente do Kotlin, transpila o módulo TypeScript em memória e chama `isTerminalPlatformError`.

## O que não foi feito

- Não foi instalado/executado o APK, nem usado emulador, aparelho ou instrumentação.
- Não foram acessados endpoints extraídos, contas, cookies reais, CAPTCHA ou conteúdo privado.
- Não foram alterados fontes, infraestrutura, configurações globais do Codex ou motores do Devwolf.
- Não foram executados testes/build/lint completos da aplicação. A verificação se limitou às evidências/documentação e à reprodução descrita.

As evidências são estáticas. `coverage=complete` em uma operação REA significa cobertura daquela operação e seleção, não engenharia reversa completa do aplicativo.
