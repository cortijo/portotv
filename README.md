# Portonet TV

App Android TV exclusivo para TV Box: recebe uma lista de canais (M3U/M3U8, aceitando também MPEG-TS bruto) e o EPG (XMLTV) próprio da Portonet, e funciona como uma TV digital comum — liga, mostra o último canal, zapping por controle remoto, sem menu no meio do caminho.

Contexto completo da decisão de arquitetura, identidade visual e o contrato de API proposto está no projeto "aplicativo portonet" do Claude (docs `analise-saimotv-e-proposta-portonet.md` e `identidade-visual-portonet.md`).

## Estrutura

| Arquivo | Papel |
|---|---|
| `DeviceId.kt` | Gera/persiste o UUID que identifica o aparelho junto ao servidor da Portonet |
| `Provisioning.kt` | Sincroniza esse UUID com a API (`GET /sincronizar?device_id=...`), trata autorizado/não-autorizado/indisponível, cacheia a última resposta boa |
| `ContentRepository.kt` | Baixa e parseia a playlist e o EPG a partir das URLs que o provisionamento devolveu, cacheia o resultado processado em disco |
| `PlaylistParser.kt` | Parser de M3U — casa fontes repetidas do mesmo `tvg-id` como redundância, não como canais duplicados |
| `Epg.kt` | Parser de XMLTV em streaming (`XmlPullParser`, sem carregar o XML inteiro em memória) + cálculo de agora/próximo |
| `Playback.kt` | Monta o `MediaSource` certo do ExoPlayer (HLS `.m3u8`, DASH `.mpd`, ou progressivo para MPEG-TS bruto sem extensão) |
| `LockActivity.kt` | Tela de bloqueio — único ponto de entrada do app, mostra o UUID e não libera o player até a API autorizar |
| `PlayerActivity.kt` | O app propriamente dito: player em tela cheia, zapping (setas/CH+-/números), banner agora/próximo, lista lateral de canais, troca de fonte com tolerância a engasgo, ressincronização periódica para permitir revogação remota |
| `backend/` | API de provisionamento (Node/Express + SQLite, Docker), implementa `GET /sincronizar` e o painel admin — ver `backend/README.md` |

## O que ainda é placeholder

- **`Provisioning.BASE_URL`** aponta para `https://api.portonet.net.br/tv`, um endereço de exemplo — trocar por onde o backend em `backend/` for publicado (ex.: `http://SEU_SERVIDOR:9966`). O contrato de requisição/resposta que o app espera está documentado no cabeçalho de `Provisioning.kt`, no doc do projeto e em `backend/README.md`.
- **Ícones e banner do launcher** (`res/mipmap-*`, `res/drawable/tv_banner.png`) foram gerados automaticamente a partir do logotipo sobre o azul da marca (`#01237C`) — servem para rodar e testar, mas o ideal é substituir por artes oficiais da Portonet quando existirem.

## Compilar

Este ambiente de desenvolvimento (sandbox) não tem o Android SDK instalado e não tem acesso de rede para baixá-lo — por isso o projeto não foi compilado aqui. Duas formas de compilar de verdade:

### 1. GitHub Actions (automático)
O workflow `.github/workflows/build.yml` já está pronto: em qualquer push a um repositório GitHub, ele instala JDK 17 + Android SDK + Gradle 8.9 e roda `gradle assembleDebug`, publicando o `app-debug.apk` como artefato do build. Basta dar push deste projeto para um repositório.

### 2. Localmente, no Android Studio
Abrir a pasta `portonet-tv` no Android Studio (Koala ou mais novo) — ele baixa o Gradle/SDK necessários automaticamente — e rodar/compilar normalmente. Não há `gradlew` neste pacote (também depende de baixar o wrapper); o Android Studio resolve isso na primeira abertura, ou gere com `gradle wrapper --gradle-version 8.9` tendo o Gradle 8.9+ instalado à parte.

## Próximos passos sugeridos
1. Subir o `backend/` (`docker compose up -d --build`, porta 9966) em algum servidor/domínio acessível pelo TV Box.
2. Trocar `Provisioning.BASE_URL` pelo endereço real desse backend e recompilar o APK.
3. Ícones/banner oficiais da Portonet, se/quando existirem em vetor.
4. Testar em TV Box real com a lista M3U e o XMLTV de produção.
