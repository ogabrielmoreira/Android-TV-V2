# Android TV V2

Tela inicial limpa, no estilo Apple TV, para Android TV e Google TV. Sem propaganda, sem marca e com um menu de
baixo que **se monta sozinho com os apps que você usa**. **Sem root e sem formatar:** tudo pela rede, via ADB,
e tudo reversível.

Testado no **Xiaomi Mi TV Stick** (Android TV 9). Deve funcionar em outros aparelhos com Android TV 9 ou mais novo.

## O que muda em relação à versão 1

| | Versão 1 | V2 (esta) |
|---|---|---|
| Menu de baixo | Lista fixa de apps no código | Aprende sozinho com os apps que você abre |
| Organizar o menu de baixo | Editando o código | Pelo controle: segure OK num app para mover ou tirar |
| Botões Netflix e Prime do controle | Desviados para outros apps | **Continuam como vieram de fábrica** |
| Marca | Logo e nome do autor na tela | Nenhuma |

## Como funciona

- **Relógio grande, saudação e papel de parede** que troca a cada 10 minutos (12 fotos de natureza, ou as suas).
- **Menu de baixo que aprende:** na primeira vez ele mostra os apps de streaming mais comuns que estiverem
  instalados (YouTube, Netflix, Prime Video, Globoplay, Disney+, Play Store). Daí em diante, cada app que você
  abre entra no fim do menu. Com o menu cheio (6 apps), sai o que você usa há mais tempo.
- **Organizar pelo controle:** segure **OK** (ou aperte o botão **Menu**) num app:
  - no menu de baixo: **Mover** (setas ◀ ▶ trocam de lugar, OK solta) ou **Tirar do menu de baixo**;
  - na grade ou em "Mais apps": **Colocar no menu de baixo**.

  Um app tirado à mão não volta sozinho quando você abre ele de novo; só se você colocar de volta.
- **Todos os apps:** o card "Mais apps", no fim do menu de baixo, abre todos os apps instalados. O botão
  **Apps (⋮⋮⋮)** do controle também abre esse menu.
- **Botões do controle intactos:** Netflix abre o Netflix, Prime Video abre o Prime Video, e assim por diante.
- **Volta ao original quando quiser:** um script troca entre esta tela e a do Android TV.

O launcher é baseado no [tv-home](https://github.com/mihirgupta0900/tv-home), de Mihir Gupta (MIT), adaptado
para Android 9, traduzido para português e estendido. O original está preservado em
[`upstream-tv-home/`](upstream-tv-home/).

## Passo a passo

### 1. Prepare a TV

1. **Configurações → Preferências do dispositivo → Sobre** → aperte OK em **Build** 7 vezes.
2. Volte em **Preferências do dispositivo → Opções do desenvolvedor** → ligue **Depuração USB**.
   No Android 9 não existe "Depuração sem fio", e não precisa: com a Depuração USB ligada, o stick já aceita
   ADB pela rede na porta 5555.
3. Anote o IP: **Configurações → Rede e internet →** sua rede. O computador precisa estar no mesmo Wi-Fi.

### 2. Prepare o computador (macOS ou Linux)

Precisa de **Git** e de um **JDK 17+** (ex.: [Adoptium Temurin](https://adoptium.net/)).

```bash
git clone https://github.com/ogabrielmoreira/Android-TV-V2.git
```

```bash
cd Android-TV-V2
```

Baixe as ferramentas oficiais do Google (adb, build-tools 36.1 e a plataforma Android 9) para `.sdk/`.
Nada é instalado no sistema:

```bash
scripts/setup-sdk.sh
```

### 3. Conecte na TV

```bash
export TV=192.168.1.44:5555
```

```bash
scripts/connect.sh
```

Na TV aparece **"Permitir depuração USB?"**: marque **Sempre permitir** e confirme.

> **macOS:** se aparecer `No route to host` com a TV respondendo ao `ping`, é o macOS bloqueando o `adb`
> na rede local. Rode os comandos pelo app **Terminal** (e permita "Rede local" se ele pedir).

### 4. Limpeza (opcional)

Remove apps de fábrica que pesam e não são usados, **só para o usuário**: nada é apagado do sistema e tudo volta
com um comando. A lista do Xiaomi Mi TV Stick está em
[`devices/xiaomi-mi-tv-stick/debloat.txt`](devices/xiaomi-mi-tv-stick/debloat.txt). Confira antes e tire dela o
que você usa.

```bash
scripts/debloat.sh devices/xiaomi-mi-tv-stick/debloat.txt
```

A lista de pacotes de antes fica salva em `backup/`.

### 5. Compile e instale

```bash
launcher/build.sh
```

```bash
scripts/install-launcher.sh
```

O script instala o APK, desativa (não apaga) o launcher do Google, liga o serviço **"Botões do controle"**
(só o botão Apps), libera o **acesso a uso** e aperta Home.

O acesso a uso deixa o menu de baixo ver os apps abertos por qualquer caminho, inclusive pelos botões do controle.
Sem ele, o menu aprende só com os apps abertos pela própria tela inicial.

### 6. Seus papéis de parede (opcional)

Envie uma pasta com suas fotos (substituem as 12 de natureza que vêm no app):

```bash
scripts/push-wallpapers.sh ~/Pictures/wallpapers-tv
```

Fotos grandes de celular são reduzidas automaticamente. Use imagens na horizontal.

## Personalize

Tudo em [`launcher/res/values/config.xml`](launcher/res/values/config.xml), depois rode `launcher/build.sh` e
`scripts/install-launcher.sh` de novo:

- `dock_max`: quantos apps cabem no menu de baixo.
- `dock_suggestions`: os apps que aparecem na primeira vez.
- `dock_ignore`: apps que nunca entram sozinhos no menu de baixo (ex.: Configurações).
- `hidden_apps`: apps que nunca aparecem.

Nomes de pacote: `adb shell pm list packages`.

## Como voltar ao original

| Quero | Comando |
|---|---|
| Voltar para a tela do Android TV | `scripts/switch-interface.sh android-tv` |
| Voltar para esta tela | `scripts/switch-interface.sh nova` |
| Reinstalar os apps removidos | `scripts/restore.sh devices/xiaomi-mi-tv-stick/debloat.txt` |
| Tirar um print da TV | `scripts/screenshot.sh` |

Um reset de fábrica também desfaz tudo.

## Problemas conhecidos

- **`No route to host` no macOS:** o macOS bloqueia o `adb` na rede local. Rodar pelo app Terminal resolve.
- **O botão Home sempre volta para o launcher do Google:** ele é app de sistema com prioridade maior.
  A solução é desativá-lo (`pm disable-user`), não apagar. O `install-launcher.sh` já faz isso.
- **O d8 do build-tools 34 quebra com o JDK 21** (`NullPointerException` em classes anônimas):
  use o build-tools 36.1, que é o que o `setup-sdk.sh` baixa.
- **Caminhos com espaço quebram o d8:** o `build.sh` compila numa pasta temporária em `/tmp`.
- **Reinstalar o launcher desliga o serviço "Botões do controle":** o `install-launcher.sh` religa.
  Manualmente: Configurações → Acessibilidade → Botões do controle.

## Segurança

- Quando terminar, **desligue a Depuração USB**: com ela ligada, a porta 5555 fica aberta na sua rede.
- O serviço "Botões do controle" só recebe as teclas do controle. Ele não lê o conteúdo da tela.
- O acesso a uso é lido só para saber o nome dos apps abertos. Nada sai da TV: o launcher não acessa a internet.

## Estrutura

```text
launcher/                      tela inicial (Java puro, sem Gradle) e o serviço do botão Apps
  res/values/config.xml        tamanho do menu de baixo, sugestões, apps escondidos, colunas
  assets/wallpapers/           12 fotos (Wikimedia Commons, créditos em WALLPAPER-CREDITS.md)
scripts/                       setup do SDK, conexão, limpeza/restauração, instalação, papéis de parede
devices/xiaomi-mi-tv-stick/    lista de limpeza e mapa dos botões do controle
upstream-tv-home/              projeto original tv-home (ferramentas em Python, exemplos, prompt)
```

## Créditos e licença

- Launcher base: [tv-home](https://github.com/mihirgupta0900/tv-home), de Mihir Gupta, licença MIT.
- Fotos de natureza: Wikimedia Commons, cada uma com sua licença, em
  [`launcher/WALLPAPER-CREDITS.md`](launcher/WALLPAPER-CREDITS.md).
- Adaptação, scripts e V2: Gabriel Moreira.

Código sob licença MIT, ver [LICENSE](LICENSE).
