# Índice de evidências

Todos os registros REA abaixo estão vinculados ao SHA-256 do APK declarado no relatório. Os JSONs preservam `raw_result`, `normalized_result`, parâmetros e limitações. Rótulos E01–E06 são atalhos documentais; o identificador canônico é `evidence_id`.

| Rótulo | Conteúdo | Evidence ID |
|---|---|---|
| E01 | [Declarações, identidade e manifesto](evidence/package.json) | `ev_230e0f7e5dbc94dfa16bb8fa9bc9b396abd3d39d0b4fa2adb43dd71a019c34fa` |
| E02 | [Busca de classes videodownloader](evidence/classes.json) | `ev_9732d9c5d7fedccb8b41b9aeeb927f392f5dbaf6e13f17a6f776cfddd0d55832` |
| E03 | [Q8.j.M: seleção de mídia/API/GraphQL](evidence/method-M.json) | `ev_2e3c713dcfe3b19e5632877dd9c810d63d3b1c72d2c1ca07b83051b47f5172cf` |
| E04 | [Q8.j.N: obtenção de página e contexto](evidence/method-N.json) | `ev_0c6088bb60ca7108748968dccae2c6f9b93c647455823c4506c34dee647ccecb` |
| E05 | [A8.c.E: parser de formatos recentes](evidence/method-E.json) | `ev_b0668e241a2a519deb6b627f759e0c5b108d6419b76fa1f56ef48844abb9ad9d` |
| E06 | [Referências estáticas para Q8.j.N](evidence/refs-N.json) | `ev_0419152574c250ccdea87f193c50fd103662215c69c931788949ec3fc31e6f3f` |

## Evidência auxiliar e alcance

- `sources/`: código produzido diretamente pela API JADX, com a mesma identidade de JAR registrada em REA. **Não é fonte original, código compilável garantido ou Evidence REA adicional.** Os números de linha do relatório referem-se a estes arquivos exportados. Classes com aliases podem diferir dos nomes DEX; os próprios arquivos registram renomeações.
- `evidence/AndroidManifest.xml`: cópia da exportação auxiliar; o manifesto também está preservado dentro de E01.
- `evidence/jadx-export.log`: avisos e falhas do decompilador. Exportação terminada não equivale a recuperação perfeita. Em especial, a ordem exata de `A8.c.r` permanece parcial.
- `evidence/domains.json`: busca textual offline em todos os Java exportados, incluindo bibliotecas. Não interpreta strings como tráfego de rede.
- `evidence/erro-terminal.json`: saída da execução do detector real do Devwolf; observação de código do projeto, não do APK.
- `integridade.json`: hashes da documentação/evidências/scripts/fontes selecionadas e snapshot Git do projeto. O manifesto de integridade exclui a si próprio.

## Relação achado → evidência

| Achado | Apoio | Limite |
|---|---|---|
| Identidade e splits | E01 | Sem verificação criptográfica da assinatura ou splits adicionais |
| Resolução Instagram própria | E03/E04/E05/E06, `sources/Q8/j.java`, `sources/p148s8/b.java` | Análise estática; não mede sucesso atual |
| Contexto anônimo extraído da página | `sources/M7/d.java`, `sources/com/android/model/PublicCookieModel.java` | Não significa cookie de conta válido |
| Login WebView | `sources/videodownloader/instagram/videosaver/LoginActivity.java` | Sem execução/login; armazenamento não auditado integralmente |
| Carrossel como itens | `sources/videodownloader/instagram/videosaver/utils/conver/c.java`, `sources/x8/i.java` | Sem validação em Android real |
| Galeria após transferência | `sources/o8/C3171d.java`, `sources/k6/g.java` | Scanner observado; permissões atuais não testadas |
| Domínio do fornecedor para boletim | `sources/D8/b.java`, `sources/p148s8/a.java`, `sources/videodownloader/instagram/videosaver/utils/AbstractC3244a.java` | Não é auditoria de todo tráfego do aplicativo |
| Erros terminais não reconhecidos | `evidence/erro-terminal.json`, `scripts/verificar-erros.cjs` | Reproduz decisão do detector; não executa fallback remoto |
| ZIP remoto enviado à galeria | Leitura dos arquivos do projeto citados no relatório | Consequência específica depende do Android |

## Fontes auxiliares selecionadas

- [Q8/j.java](sources/Q8/j.java)
- [Q8/g.java](sources/Q8/g.java)
- [Q8/a.java](sources/Q8/a.java)
- [A8/c.java](sources/A8/c.java)
- [M7/d.java](sources/M7/d.java)
- [p148s8/a.java](sources/p148s8/a.java)
- [p148s8/b.java](sources/p148s8/b.java)
- [D8/b.java](sources/D8/b.java)
- [X0/g.java](sources/X0/g.java)
- [G2/AbstractC0035e.java](sources/G2/AbstractC0035e.java)
- [F/b.java](sources/F/b.java)
- [x8/i.java](sources/x8/i.java)
- [o8/C3171d.java](sources/o8/C3171d.java)
- [k6/e.java](sources/k6/e.java)
- [k6/g.java](sources/k6/g.java)
- [com/bumptech/glide/d.java](sources/com/bumptech/glide/d.java)
- [com/google/common/util/concurrent/G.java](sources/com/google/common/util/concurrent/G.java)
- [com/android/model/PublicCookieModel.java](sources/com/android/model/PublicCookieModel.java)
- [com/android/model/LoginUserModel.java](sources/com/android/model/LoginUserModel.java)
- [com/android/model/DownloadModel.java](sources/com/android/model/DownloadModel.java)
- [com/android/model/DownloadObjectModel.java](sources/com/android/model/DownloadObjectModel.java)
- [com/android/model/instagram/v2/V2_ItemPostModel.java](sources/com/android/model/instagram/v2/V2_ItemPostModel.java)
- [com/android/model/instagram/v2/V2_ItemPostSubModel.java](sources/com/android/model/instagram/v2/V2_ItemPostSubModel.java)
- [com/android/model/instagram/v2/V2_FitModel.java](sources/com/android/model/instagram/v2/V2_FitModel.java)
- [videodownloader/instagram/videosaver/ShareActivity.java](sources/videodownloader/instagram/videosaver/ShareActivity.java)
- [videodownloader/instagram/videosaver/LoginActivity.java](sources/videodownloader/instagram/videosaver/LoginActivity.java)
- [videodownloader/instagram/videosaver/MainActivity.java](sources/videodownloader/instagram/videosaver/MainActivity.java)
- [videodownloader/instagram/videosaver/ShowActivity.java](sources/videodownloader/instagram/videosaver/ShowActivity.java)
- [videodownloader/instagram/videosaver/utils/AbstractC3244a.java](sources/videodownloader/instagram/videosaver/utils/AbstractC3244a.java)
- [videodownloader/instagram/videosaver/utils/j.java](sources/videodownloader/instagram/videosaver/utils/j.java)
- [videodownloader/instagram/videosaver/utils/i.java](sources/videodownloader/instagram/videosaver/utils/i.java)
- [videodownloader/instagram/videosaver/utils/w.java](sources/videodownloader/instagram/videosaver/utils/w.java)
- [videodownloader/instagram/videosaver/utils/conver/c.java](sources/videodownloader/instagram/videosaver/utils/conver/c.java)
