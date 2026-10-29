# Xiaomi Mi TV Stick (MiTV-AESP0)

Android TV 9, 1 GB de RAM, controle Bluetooth "Xiaomi RC" (`Vendor_2717_Product_32b9`).

## Debloat

[`debloat.txt`](debloat.txt) tem 11 pacotes de fábrica que dá para remover (o Netflix ficou fora da lista). Memória livre logo após ligar: de ~210 MB para ~394 MB
(a medição de antes foi com o stick ligado há um tempo, então a comparação é aproximada).

## Botões do controle

Descoberto lendo `/vendor/usr/keylayout/Vendor_2717_Product_32b9.kl` e o `logcat` com o botão sendo apertado:

| Botão | Scancode | Keycode Android | O que o sistema faz | Na V2 |
|---|---|---|---|---|
| Netflix | 393 | 1001 (da Xiaomi) | O WindowManager consome a tecla e dispara `VIEW nflx://www.netflix.com/` | Sem mudança: abre o Netflix |
| Prime Video | 399 | 191 (`BUTTON_4`) | O WindowManager consome a tecla e abre direto `com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity` | Sem mudança: abre o Prime Video |
| Apps (⋮⋮⋮) | 580 | 284 (`ALL_APPS`) | Repassa a tecla normalmente | O serviço de acessibilidade pega a tecla e abre o menu **Todos os apps** |
| Assistente | 582 | 219 (`ASSIST`) | Google Assistente | Sem mudança |

Por que só o botão Apps é mexido: serviços de acessibilidade só recebem teclas **depois** da etapa
`interceptKeyBeforeQueueing` do sistema. Netflix e Prime são tratados ali pela Xiaomi, então nunca chegam ao
serviço, e na V2 eles ficam como vieram de fábrica. Teclas simuladas com `adb shell input keyevent` também não passam pelo filtro de acessibilidade, então
o teste precisa ser feito com o controle de verdade.

## Detalhes úteis

- O stick já aceita ADB pela rede na porta 5555 quando a **Depuração USB** está ligada (não existe a opção
  "Depuração sem fio" no Android 9).
- O launcher do Google (`com.google.android.tvlauncher`) é app de sistema com prioridade 2 no filtro HOME:
  enquanto estiver ativo, ele sempre ganha o botão Home. Por isso ele é desativado (não apagado).
- Fontes do sistema: só a família Roboto (+ serif e monospace).
