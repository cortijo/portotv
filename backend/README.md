# Portonet TV — API de provisionamento

Implementa o contrato que o app Portonet TV espera (`GET /sincronizar?device_id=...`), documentado na seção 7 de `claude/analise-saimotv-e-proposta-portonet.md` do projeto. Guarda aparelhos, catálogo de canais e configuração em SQLite, num único arquivo em `/data`.

A playlist e o EPG que o app baixa **não vêm mais de uma URL externa repassada direto** — são gerados pela própria API a partir de um catálogo de canais cadastrado no painel. Isso existe porque depender de uma lista/URL de terceiros pra tudo dá muito erro: a fonte cai, o `tvg-id` muda, o arquivo vem gigante demais pra um TV Box processar, etc. Agora a Portonet é dona do catálogo; a fonte externa só entra como *matéria-prima* pra importar de uma vez.

## Subir com Docker

```bash
cd backend
cp .env.example .env   # edite ADMIN_TOKEN antes de ir para produção
docker compose up -d --build
```

A API sobe em `http://localhost:9966`. O painel admin fica em `http://localhost:9966/admin.html` — pede o `ADMIN_TOKEN` uma vez e guarda no navegador.

## Uso no dia a dia

### Canais
1. **Importar de uma lista M3U existente**: cola a URL (ou envia o arquivo) em "Canais → Importar de uma lista M3U", clica em Buscar, e escolhe (com checkbox) quais canais realmente trazer pro catálogo — não importa a lista inteira automaticamente, então uma lista de terceiros gigante ou com lixo não vira problema aqui.
2. **Ou cadastrar um canal manualmente**: nome, número, grupo, logo e a URL do stream.
3. Cada canal pode ter mais de uma fonte (redundância) — usa o botão de adicionar fonte por canal.

### EPG
1. Em "Entradas de EPG", cadastra a URL de um XMLTV (ou envia o arquivo) e clica **Atualizar** pra API buscar e listar os canais que existem *dentro* dele.
2. De volta em "Canais", associa cada canal do catálogo a um desses "canais internos" do XMLTV — é essa associação que decide qual programação aparece pra cada canal, não mais um `tvg-id` cru vindo de fora.

### Aparelhos
1. Ligar um TV Box novo com o app instalado → ele aparece na lista como **"Aguardando"**, com o UUID visível também na própria tela de bloqueio do app.
2. Clicar **Autorizar** (dá pra dar um apelido, tipo "Loja Centro", pra não depender de decorar UUID).
3. O TV Box sincroniza sozinho (no boot e a cada 30 min por padrão), baixa a playlist/EPG gerados a partir do catálogo, e passa a tocar.
4. A tabela mostra **Rede** (Wi-Fi/Cabo/Dados, informada pelo próprio aparelho a cada sincronização), **Latência** (tempo de processamento da última sincronização, em ms) e um status **Online/Offline** calculado pela recência da última sincronização.
5. **Revogar** tira a autorização sem explicar o motivo (o app só volta a mostrar "aguardando"). **Bloquear** faz o mesmo mas pede uma mensagem (ex.: "Assinatura em atraso. Fale com o suporte.") que aparece na tela do TV Box no lugar do texto genérico — pensado pra corte por inadimplência ou suspeita de uso indevido, onde o cliente precisa saber o motivo.

## Endpoints

| Rota | Uso |
|---|---|
| `GET /sincronizar?device_id=<uuid>&rede=<wifi\|ethernet\|celular>` | Chamado pelo app. Não precisa de token — é o único endpoint de aparelho público. |
| `GET /playlist.m3u8` | Playlist gerada a partir do catálogo de canais ativos (público — é a URL que o app recebe em `playlist_url`). |
| `GET /epg.xml` | XMLTV gerado a partir das associações de EPG dos canais (público — é a URL que o app recebe em `epg_url`). |
| `GET /health` | Checagem simples de vida. |
| `GET /admin/api/canais` | Lista o catálogo de canais, com fontes e associação de EPG (token) |
| `POST /admin/api/canais` | Cadastra um canal manualmente `{nome, url, numero?, grupo?, logo?}` (token) |
| `PUT /admin/api/canais/:id` | Edita um canal `{nome?, numero?, grupo?, logo?, ativo?}` (token) |
| `DELETE /admin/api/canais/:id` | Remove um canal e suas fontes (token) |
| `POST /admin/api/canais/:id/fontes` | Adiciona uma fonte (URL) redundante a um canal (token) |
| `DELETE /admin/api/canais/:canalId/fontes/:fonteId` | Remove uma fonte específica (token) |
| `POST /admin/api/canais/:id/associar-epg` | Associa `{epg_input_id, epg_canal_id}` (ou `null` pra desassociar) (token) |
| `POST /admin/api/canais/importar-m3u` | Busca e parseia uma lista `{url}`, devolve os itens pra escolher (não grava nada) (token) |
| `POST /admin/api/canais/importar-selecionados` | Grava `{itens: [...]}` escolhidos na etapa anterior (token) |
| `GET /admin/api/epg-inputs` | Lista as fontes de EPG cadastradas (token) |
| `POST /admin/api/epg-inputs` | Cadastra uma fonte `{nome, url}` (token) |
| `POST /admin/api/epg-inputs/:id/atualizar` | Busca/reprocessa o XMLTV dessa fonte (token) |
| `GET /admin/api/epg-inputs/:id/canais` | Lista os canais (`id`+`nome`) encontrados dentro dessa fonte, pra associar (token) |
| `DELETE /admin/api/epg-inputs/:id` | Remove a fonte (canais associados ficam sem EPG) (token) |
| `GET /admin/api/devices` | Lista aparelhos, com rede/latência/status de bloqueio (token) |
| `POST /admin/api/devices/:id/autorizar` | Autoriza um UUID (token) |
| `POST /admin/api/devices/:id/revogar` | Revoga um UUID, sem mensagem (token) |
| `POST /admin/api/devices/:id/bloquear` | Bloqueia com uma mensagem `{mensagem}` mostrada no app (token) |
| `POST /admin/api/devices/:id/apelido` | Define um apelido pro aparelho (token) |
| `DELETE /admin/api/devices/:id` | Remove o registro (volta a aparecer se sincronizar de novo) (token) |
| `GET /admin/api/config` | Lê TTLs / intervalo de sincronização (token) |
| `POST /admin/api/config` | Grava TTLs / intervalo de sincronização (token) |
| `POST /admin/api/upload` | Sobe um `.m3u`/`.m3u8`/`.xml` (campo `arquivo`, multipart, até 100 MB) e devolve a URL pública em `/arquivos/...` (token) |
| `GET /admin/api/logs` | Histórico de sincronizações (`?device_id=` filtra, `?limit=` padrão 200, máx. 2000) (token) |

Todos os endpoints `/admin/api/*` exigem o cabeçalho `X-Admin-Token: <ADMIN_TOKEN>` (o painel HTML cuida disso sozinho depois do login).

Compatibilidade: se nenhum canal estiver cadastrado no catálogo ainda, `/sincronizar` cai de volta pra `playlist_url`/`epg_url` configuradas manualmente via `POST /admin/api/config` (modo antigo) — assim uma instalação já em produção não quebra até alguém migrar para o catálogo.

## Dados

Tudo fica em `/data/portonet.db` (SQLite, via `node:sqlite` nativo do Node 22 — sem dependência nativa para compilar) e os arquivos enviados pelo painel em `/data/uploads/`. O volume Docker `portonet_data` persiste ambos entre reinícios do container.

Cada `/sincronizar` fica registrado na tabela `acessos` (dispositivo, status, IP, data/hora) — o painel expõe isso na seção "Logs de acesso", com filtro por UUID. A tabela é podada automaticamente mantendo as últimas ~50 mil linhas.

O EPG de cada fonte cadastrada fica em cache em memória por 15 minutos, pra não rebuscar/reparsear o XMLTV externo a cada sincronização de cada aparelho.

## Conectar o app a esta API

No projeto Android, `Provisioning.BASE_URL` (`app/src/main/java/br/com/portonet/tv/Provisioning.kt`) já aponta para `http://181.233.106.46:9966`, onde esta API roda por enquanto. A cada sincronização o app também informa o tipo de rede (`wifi`/`ethernet`/`celular`), usado só pro painel mostrar como o aparelho está conectado.
