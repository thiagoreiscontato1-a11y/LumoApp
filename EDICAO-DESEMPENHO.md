# Desempenho da edição em resolução original

Meta: a foto de 24 MP editada e aprovada em cerca de **1 s**. Antes da 0.19.0 levava cerca de 10 s no celular.

## Onde estavam os 10 s

Medido no motor de revelação (`Develop.java`) com uma foto de 6000×4000, num servidor x86 de 4 núcleos. No celular os
números absolutos mudam, mas a proporção entre as etapas é parecida.

| Etapa | Antes | Problema |
|---|---|---|
| Cor (HSL, calibração, color grading, saturação, curvas) | ~2,6 s | contas de cor completas por pixel |
| Ajustes locais (realces, sombras, claridade, textura) | ~1,0 s | leitura espalhada numa grade de 2000×1333 que não cabe no cache |
| Vinheta + grão | ~0,9 s | 3 `pow` e 8 hashes por pixel |
| Nitidez | ~0,8 s | desfoque em **um** núcleo só |
| Preparação (log da luminância, campos locais) | ~1,1 s | em **um** núcleo só |

Fora do motor, no Android:
- A foto era decodificada inteira mais **duas** vezes só para o hash perceptual: uma na chegada e outra na curadoria.
- Fotos em pé eram giradas numa segunda cópia de 96 MB.
- O JPEG final esperava `fsync`, e a curadoria só começava depois de gravá-lo.

## O que mudou (0.19.0)

**Motor de revelação**
- Desfoque, log da luminância, soma dos campos locais e luminância da nitidez agora rodam em todos os núcleos.
  O resultado é idêntico.
- Grade dos ajustes locais interpolada uma vez por linha. Vinheta e grão pré-calculados por coluna e por linha, com o
  ruído reaproveitado dentro de cada célula.
- Compressão dos realces e máscara da vinheta passam a usar tabelas, sem `pow` por pixel.
- **Cor por tabela 3D (LUT 65³)** em fotos acima de 2 MP. A tabela é montada uma vez por foto com o mesmo código da cor.
  Contra o cálculo exato: 93% dos pixels idênticos e o resto no máximo 2 níveis em 255, o que é invisível.
  A prévia do editor e os testes continuam no cálculo exato, e a paridade com o iOS não mudou.

| 24 MP | Antes | Depois |
|---|---|---|
| Preset típico (exposição, contraste, realces, sombras, vibração, nitidez, claridade, vinheta) | 3,7 s | **1,9 s** |
| Preset completo (o dos testes, com HSL, calibração, color grading e grão) | 6,3 s | **2,4 s** |

**Fluxo no Android**
- A orientação EXIF é aplicada ao copiar os pixels, sem bitmap girado. O bitmap decodificado é liberado antes de revelar.
- A curadoria (técnica, rostos, hash) mede uma cópia de ~900 px feita da foto já revelada, **em paralelo** com a gravação do
  JPEG. Antes eram três decodificações do arquivo gravado, uma delas inteira.
- O hash perceptual da chegada decodifica a foto já reduzida (~375 px), não os 24 MP.
- Sem `fsync` no arquivo temporário.
- O registro de captura mostra o tempo de cada etapa:
  `Editada salva · 2.41 s (abrir 0.32 s · revelar 1.55 s · JPEG 0.54 s · 6000×4000) · nota 8/10`.

**O que esperar no celular:** algo entre 2,5 e 4 s por foto de 24 MP, contra ~10 s antes. Os números reais aparecem no
registro de captura. Envie essa linha depois do primeiro teste.

## Como chegar a ~1 s

Com Java no processador do celular, 1 s em 24 MP não é alcançável. Mesmo com o motor perfeito, só abrir e gravar o JPEG de
24 MP já levam ~0,5 a 0,8 s. Há dois caminhos.

### Caminho A (recomendado): revelar na GPU do celular
O pipeline é quase todo "por pixel", e os campos locais são grades pequenas. Isso encaixa num *fragment shader* OpenGL ES 3
(o Android 10, mínimo do Lumo, tem ES 3.2 em praticamente todo aparelho).
- **Tempo esperado:** revelar 24 MP em ~0,1 a 0,2 s na GPU. Com abrir e gravar o JPEG, o total fica em **~0,8 a 1,2 s**.
- **Trabalho:**
  - portar as contas de `Plan.rows`, `color` e `Effects` para GLSL;
  - campos locais e LUT viram texturas;
  - renderizar em blocos de até 4096 px (limite de textura de aparelhos mais simples), com contexto EGL próprio no
    serviço de captura;
  - manter o motor Java como reserva quando a GPU falhar;
  - testar em 2 ou 3 aparelhos reais e comparar com o motor Java (mesma tolerância de 2 níveis).
- **Custo:** zero de infraestrutura e funciona sem internet, o que importa em evento.

### Caminho B: revelar num servidor
O motor (`Develop.java`) é Java puro, sem Android, e roda igual num servidor, com o mesmo resultado.

```
Celular ──(original JPEG + ajustes JSON)──► API de revelação ──► JPEG editado ──► Fotto (S3) / galeria online
   │                                          (Cloud Run, Java 21,                       │
   └── prévia e curadoria locais (~900 px) ◄── TurboJPEG, Develop.java)   ◄── confirmação
```

| Peça | Sugestão |
|---|---|
| Servidor | **Google Cloud Run** (contêiner Java 21, 8 vCPU / 8 GB, escala a zero, região São Paulo). Alternativa fixa: VPS dedicada de 8 vCPU (Hetzner, Magalu Cloud) |
| JPEG rápido | libjpeg-turbo via TurboJPEG (abrir e gravar 24 MP em ~0,15 s cada) |
| Fila e estado | um endpoint `POST /v1/revelar` idempotente por `clientPhotoId + revisão`, como a galeria online já faz |
| Armazenamento temporário | o R2 que a galeria já usa (o original fica 24 h e é apagado) |
| Segurança | token por fotógrafo (o mesmo esquema da galeria) e HTTPS |
| Custo estimado | ~US$ 0,0003 de CPU por foto + saída de rede ~US$ 0,12/GB → **~US$ 1,50 por evento de 1.000 fotos**. Confira os preços atuais do provedor |

**Limitações:**
- Precisa de internet boa no evento. Sem ela, o celular volta a revelar sozinho, então o motor Java continua necessário.
- O original de ~10 MB precisa subir antes de qualquer edição: 4 s numa rede de 20 Mbit/s. O tempo até a foto chegar ao
  Fotto só melhora se o servidor enviar direto para o Fotto. Para isso, o servidor teria de usar o token do Fotto do
  fotógrafo, e a API do Fotto não é oficial (o mesmo risco já apontado para a comercialização).
- No servidor, o motor Java levaria ~1,2 a 1,5 s por foto com 8 vCPU. Para ficar em 1 s, o servidor também precisaria de GPU
  ou de mais núcleos, e aí o custo sobe.

**Recomendação:** primeiro medir no celular a versão 0.19.0 (registro de captura). Se ainda ficar acima de ~2 s, seguir
pelo **Caminho A (GPU)**: mesmo ganho de um servidor, sem custo mensal e sem depender de internet. O servidor faz sentido
depois, se o Lumo passar a revelar também na galeria online ou para vários fotógrafos.

**Enquanto isso:** em Ajustes, "Reduzida · 2560 px" revela cerca de 5× menos pixels (~0,5 s) para quem não precisa da
resolução original.

## iPhone

O iOS (`Develop.swift`) é uma cópia linha a linha do motor e ainda não recebeu estas otimizações. Vale portar depois de
validar os números no Android.
