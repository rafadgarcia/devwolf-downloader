# Atalhos Android: bolha de link e Status do WhatsApp

## Bolha de clipboard

Ative **Configurações → Atalhos do Android → Bolha do clipboard**. O Android abre a tela de autorização de sobreposição; ao conceder, aparece a bolha roxa “DW” na lateral. Ela fica sobre outros apps enquanto o Devwolf está ativo em segundo plano. Toque nela para ler naquele momento o primeiro item do clipboard, validar um link suportado e abrir o processamento normal do Devwolf. Sem URL compatível, o app mostra um aviso.

O Android não oferece leitura silenciosa e contínua do clipboard em segundo plano. Por isso não há monitoramento periódico: a leitura ocorre após o toque explícito. O usuário pode revogar a autorização nas configurações do sistema ou desligar o recurso no app.

## Importar Status

Abra **Configurações → Atalhos do Android → Escolher pasta de Status**. No seletor do sistema, escolha a pasta `Statuses` do WhatsApp, normalmente sob `Android/media/com.whatsapp/WhatsApp/Media/.Statuses` ou localização equivalente. O caminho varia por instalação, versão do Android, fabricante e WhatsApp; o app não fixa um caminho. A permissão SAF de leitura fica restrita à árvore selecionada e pode ser revogada nas configurações do Android.

O Devwolf lista até 200 fotos e vídeos visíveis, inclusive em subpastas. Toque **Salvar** para copiar sem recompressão para `Pictures/Devwolf Downloader` ou `Movies/Devwolf Downloader`. A origem permanece intacta. Tipos permitidos: JPEG, PNG, WebP, MP4, M4V, 3GP e WebM; limite de 500 MB por arquivo. Só são lidos arquivos já disponíveis nessa pasta; o recurso não busca status não carregados ou apagados do servidor.

Para WhatsApp Business, escolha manualmente a pasta `Statuses` da instalação correspondente, se o seletor a disponibilizar. A validação exige que a pasta escolhida termine em `Statuses`, sem depender de caminhos fixos.

## Implementação e validação

- `MainActivity` exibe/remove a bolha `TYPE_APPLICATION_OVERLAY` segundo a preferência e a autorização.
- `DevwolfNativePlugin` usa o seletor SAF (`ACTION_OPEN_DOCUMENT_TREE`), valida a pasta, lista itens recursivamente, copia para cache temporário e publica pela rotina MediaStore existente.
- A bridge Capacitor devolve toques da bolha ao fluxo usual de compartilhamento. A tela de Configurações lista os itens e solicita cópia individual.
- A bolha começa desligada. A árvore Status é escolhida sob demanda, sem varredura do armazenamento.

`npm run build` passou. `gradlew testDebugUnitTest assembleDebug` passou com JDK 21 portátil. APK universal gerado: `android/app/build/outputs/apk/debug/app-universal-debug.apk`. A validação em aparelho ainda é necessária para o diálogo de sobreposição, posicionamento, acesso ao provedor SAF da pasta oculta `.Statuses` e publicação no MediaStore.
