# Atalhos Android: bolha de link e Status do WhatsApp

## Bolha de clipboard

Ative **Configurações → Atalhos do Android → Bolha do clipboard**. O Android abre a tela de autorização de sobreposição; ao conceder, aparece a bolha roxa “DW” na lateral. Ela fica sobre outros apps enquanto o Devwolf está ativo em segundo plano. Toque nela para ler naquele momento o primeiro item do clipboard, validar um link suportado e abrir o processamento normal do Devwolf. Sem URL compatível, o app mostra um aviso.

O Android não oferece leitura silenciosa e contínua do clipboard em segundo plano. Por isso não há monitoramento periódico: a leitura ocorre após o toque explícito. O usuário pode revogar a autorização nas configurações do sistema ou desligar o recurso no app.

## Importar Status

Abra o menu de três pontos na tela inicial e toque em **WhatsApp Status**. Se ainda não houver uma pasta vinculada, use **Pasta padrão** para sugerir `Android/media/com.whatsapp/WhatsApp/Media/.Statuses` ou **Escolher pasta** para selecionar outro local. O Android ainda exige que você confirme a pasta e conceda acesso. Se o aparelho não reconhecer a localização padrão, navegue até `Statuses` manualmente. A permissão SAF de leitura fica restrita à árvore selecionada e pode ser revogada nas configurações do Android.

O Devwolf mantém a pasta autorizada. A tela de Status mostra miniaturas reais em grade; as prévias são carregadas sob demanda para os cartões visíveis. Toque em uma mídia para salvá-la sem recompressão em `Movies/Devwolf Downloader` ou `Pictures/Devwolf Downloader`. Use **Atualizar** depois de abrir novos Status no WhatsApp. O app confirma o caminho do arquivo e marca a mídia salva. A origem permanece intacta. Tipos permitidos: JPEG, PNG, WebP, MP4, M4V, 3GP e WebM; limite de 500 MB por arquivo. Só são lidos arquivos já disponíveis nessa pasta; o recurso não busca status não carregados ou apagados do servidor.

Para WhatsApp Business, escolha manualmente a pasta `Statuses` da instalação correspondente, se o seletor a disponibilizar. A validação exige que a pasta escolhida termine em `Statuses`, sem depender de caminhos fixos.

## Implementação e validação

- `MainActivity` exibe/remove a bolha `TYPE_APPLICATION_OVERLAY` segundo a preferência e a autorização.
- `DevwolfNativePlugin` usa o seletor SAF (`ACTION_OPEN_DOCUMENT_TREE`), valida a pasta, lista itens recursivamente, gera miniaturas limitadas a 480 px em chamadas sob demanda, copia para cache temporário e publica pela rotina MediaStore existente.
- O resultado SAF (`JSONArray`) é serializado como JSON ao compor o `JSArray` da bridge Capacitor. A tela dedicada lista os itens e solicita cópia individual.
- A bolha começa desligada. A árvore Status é escolhida sob demanda, sem varredura do armazenamento.

`npm run build` passou. `gradlew testDebugUnitTest assembleDebug` passou com JDK 21 portátil. APK universal gerado: `android/app/build/outputs/apk/debug/app-universal-debug.apk`. A validação em aparelho ainda é necessária para o diálogo de sobreposição, posicionamento, acesso ao provedor SAF da pasta oculta `.Statuses` e publicação no MediaStore.
