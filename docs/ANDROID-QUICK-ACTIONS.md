# Atalhos Android: bolha de link e Status do WhatsApp

## Bolha flutuante

Ative **Configurações → Atalhos do Android → Bolha flutuante** (começa desligada). O Android abre a tela de autorização de sobreposição; ao conceder, a bolha redonda com o ícone do Devwolf passa a aparecer **sobre outros apps sempre que o Devwolf estiver em segundo plano** (ela some enquanto o app está aberto).

- **Tocar**: abre o Devwolf, que lê naquele momento o primeiro item do clipboard, valida um link suportado e inicia o download. Sem URL compatível, o app mostra um aviso.
- **Arrastar**: a bolha acompanha o dedo, encosta na borda mais próxima ao soltar e lembra a posição.
- **Fechar**: durante o arraste aparece um **✕** na parte de baixo da tela; solte a bolha sobre ele para fechá-la até a próxima vez que abrir o Devwolf. A notificação "Bolha do Devwolf ativa" também tem a ação **Fechar bolha**.
- **Desativar de vez**: desligue a chave em Configurações.

A bolha roda como serviço em primeiro plano de baixa prioridade (`ClipboardBubbleService`, tipo `specialUse`) para o Android não encerrá-la em segundo plano; por isso existe a notificação silenciosa.

O Android não oferece leitura silenciosa e contínua do clipboard em segundo plano. Por isso não há monitoramento periódico: a leitura ocorre após o toque explícito. O usuário pode revogar a autorização nas configurações do sistema ou desligar o recurso no app.

## Importar Status

Abra o menu de três pontos na tela inicial e toque em **WhatsApp Status**. Se ainda não houver uma pasta vinculada, use **Pasta padrão** para sugerir `Android/media/com.whatsapp/WhatsApp/Media/.Statuses` ou **Escolher pasta** para selecionar outro local. O Android ainda exige que você confirme a pasta e conceda acesso. Se o aparelho não reconhecer a localização padrão, navegue até `Statuses` manualmente. A permissão SAF de leitura fica restrita à árvore selecionada e pode ser revogada nas configurações do Android.

O Devwolf mantém a pasta autorizada. A tela de Status mostra miniaturas reais em grade; as prévias são carregadas sob demanda para os cartões visíveis. Toque em uma mídia para salvá-la sem recompressão em `Movies/Devwolf Downloader` ou `Pictures/Devwolf Downloader`. Use **Atualizar** depois de abrir novos Status no WhatsApp. O app confirma o caminho do arquivo e marca a mídia salva. A origem permanece intacta. Tipos permitidos: JPEG, PNG, WebP, MP4, M4V, 3GP e WebM; limite de 500 MB por arquivo. Só são lidos arquivos já disponíveis nessa pasta; o recurso não busca status não carregados ou apagados do servidor.

Para WhatsApp Business, escolha manualmente a pasta `Statuses` da instalação correspondente, se o seletor a disponibilizar. A validação exige que a pasta escolhida termine em `Statuses`, sem depender de caminhos fixos.

## Implementação e validação

- `ClipboardBubbleService` exibe a bolha `TYPE_APPLICATION_OVERLAY` (arrastável, com alvo de fechar) quando a `MainActivity` sai da tela e a esconde quando ela volta; a leitura do clipboard ocorre só quando a `MainActivity` recebe foco.
- `DevwolfNativePlugin` usa o seletor SAF (`ACTION_OPEN_DOCUMENT_TREE`), valida a pasta, lista itens recursivamente, gera miniaturas limitadas a 480 px em chamadas sob demanda, copia para cache temporário e publica pela rotina MediaStore existente.
- O resultado SAF (`JSONArray`) é serializado como JSON ao compor o `JSArray` da bridge Capacitor. A tela dedicada lista os itens e solicita cópia individual.
- A bolha começa desligada. A árvore Status é escolhida sob demanda, sem varredura do armazenamento.

`npm run build` passou. `gradlew testDebugUnitTest assembleDebug` passou com JDK 21 portátil. APK universal gerado: `android/app/build/outputs/apk/debug/app-universal-debug.apk`. A validação em aparelho ainda é necessária para o diálogo de sobreposição, posicionamento, acesso ao provedor SAF da pasta oculta `.Statuses` e publicação no MediaStore.
