# Portonet TV — API de provisionamento

Implementa o contrato que o app Portonet TV espera (`GET /sincronizar?device_id=...`), documentado na seção 7 de `claude/analise-saimotv-e-proposta-portonet.md` do projeto. Guarda os aparelhos e a configuração (URL da playlist M3U e do EPG XMLTV) em SQLite, num único arquivo em `/data`.

## Subir com Docker

```bash
cd backend
cp .env.example .env   # edite ADMIN_TOKEN antes de ir para produção
docker compose up -d --build
```

A API sobe em `http://localhost:9966`. O painel admin fica em `http://localhost:9966/admin.html` — pede o `ADMIN_TOKEN` uma vez e guarda no navegador.

## Uso no dia a dia

1. Ligar um TV Box novo com o app instalado → ele aparece na lista do painel como **"Aguardando"**, com o UUID visível também na própria tela de bloqueio do app.
2. No painel, clicar **Autorizar** (dá pra dar um apelido, tipo "Loja Centro", pra não depender de decorar UUID).
3. Preencher **uma vez** a URL da playlist M3U e do EPG XMLTV no topo do painel e salvar — vale para todos os aparelhos autorizados (não há personalização por assinante nesta primeira versão; se for necessário no futuro, dá pra estender por aparelho sem mudar o app).
4. O TV Box sincroniza sozinho (no boot e a cada 30 min por padrão) e passa a tocar.
5. Para desligar um assinante, **Revogar** — na sincronização seguinte (até 30 min, ou na próxima vez que ligar) o app volta pra tela de bloqueio sozinho.

## Endpoints

| Rota | Uso |
|---|---|
| `GET /sincronizar?device_id=<uuid>` | Chamado pelo app. Não precisa de token — é o único endpoint público. |
| `GET /health` | Checagem simples de vida. |
| `GET /admin/api/devices` | Lista aparelhos (token) |
| `POST /admin/api/devices/:id/autorizar` | Autoriza um UUID (token) |
| `POST /admin/api/devices/:id/revogar` | Revoga um UUID (token) |
| `POST /admin/api/devices/:id/apelido` | Define um apelido pro aparelho (token) |
| `DELETE /admin/api/devices/:id` | Remove o registro (volta a aparecer se sincronizar de novo) (token) |
| `GET /admin/api/config` | Lê playlist/EPG/TTLs (token) |
| `POST /admin/api/config` | Grava playlist/EPG/TTLs (token) |

Todos os endpoints `/admin/api/*` exigem o cabeçalho `X-Admin-Token: <ADMIN_TOKEN>` (o painel HTML cuida disso sozinho depois do login).

## Dados

Tudo fica em `/data/portonet.db` (SQLite, via `node:sqlite` nativo do Node 22 — sem dependência nativa para compilar). O volume Docker `portonet_data` persiste entre reinícios do container.

## Conectar o app a esta API

No projeto Android, `Provisioning.BASE_URL` (`app/src/main/java/br/com/portonet/tv/Provisioning.kt`) já aponta para `http://181.233.106.46:9966`, onde esta API roda por enquanto. Ela expõe exatamente o endpoint `/sincronizar` que o app já chama.
