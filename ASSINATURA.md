# Chave de produção do Lumo Android

Todo APK precisa de uma assinatura digital. O Android só instala uma atualização se ela for assinada com a **mesma chave**
da versão já instalada.

Até a 0.18.0, todos os APKs foram assinados com `hisho-test.keystore`, uma chave de **teste**. Ela está no repositório e a
senha é `android`. Qualquer pessoa com acesso ao código pode assinar um APK que o celular aceita como "atualização do Lumo".
Antes de entregar o app a clientes, use uma chave de produção, só sua.

A partir da 0.19.0, o `build.py` e o workflow **Lumo Android** assinam com a chave de produção quando ela está cadastrada
nos Secrets do GitHub. Sem ela, continuam usando a chave de teste e avisam no resumo da execução.

## 1. Gerar a chave (uma vez só, no seu computador)

Precisa do Java 17 ou mais novo (por exemplo, [Temurin](https://adoptium.net)). No Windows, use o Git Bash.

```bash
bash tools/gerar-chave-producao.sh "Nome do Seu Estúdio"
```

O script cria a pasta `lumo-chave-producao`, **ao lado** do repositório e não dentro dele, com:

| Arquivo | Para quê |
|---|---|
| `lumo-producao.p12` | a chave (RSA 4096, válida por 27 anos) |
| `lumo-producao.base64.txt` | o mesmo arquivo em texto, para colar no GitHub |
| `SEGREDOS.txt` | a senha, o alias e a impressão digital SHA-256 |

Sem o script, o comando equivalente é:

```bash
keytool -genkeypair -storetype PKCS12 -keystore lumo-producao.p12 -alias lumo -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Seu Estudio, C=BR"
base64 < lumo-producao.p12 | tr -d '\n' > lumo-producao.base64.txt
```

## 2. Guardar o backup (obrigatório)

Guarde a pasta em **dois lugares**, por exemplo num gerenciador de senhas e num pendrive.
**Se perder o arquivo ou a senha, o Lumo instalado nos clientes nunca mais recebe atualização**: cada cliente teria de
desinstalar e instalar de novo. Não coloque a pasta no repositório. O `.gitignore` já recusa `*.p12`, `*.jks` e
`lumo-chave-producao/`.

## 3. Cadastrar no GitHub

No repositório: **Settings → Secrets and variables → Actions → New repository secret**

| Nome | Valor |
|---|---|
| `LUMO_KEYSTORE_BASE64` | o conteúdo inteiro de `lumo-producao.base64.txt` |
| `LUMO_KEYSTORE_PASSWORD` | a senha que está em `SEGREDOS.txt` |
| `LUMO_KEY_ALIAS` | `lumo` |
| `LUMO_KEY_PASSWORD` | opcional; só se a senha da chave for diferente da senha do arquivo |

## 4. Conferir

Rode **Actions → Lumo Android → Run workflow**, ou faça qualquer merge na `main`. No passo *Compilar APK*, o log deve
mostrar `Assinado com a chave de PRODUÇÃO` e, em `--print-certs`, a mesma impressão digital SHA-256 de `SEGREDOS.txt`.
As notas do release `lumo-latest` passam a dizer "assinados com a chave de produção".

Para compilar no seu computador com a chave de produção:

```bash
export LUMO_KEYSTORE=../lumo-chave-producao/lumo-producao.p12 LUMO_KEY_ALIAS=lumo
read -rs LUMO_KEYSTORE_PASSWORD && export LUMO_KEYSTORE_PASSWORD
ANDROID_SDK_ROOT=/caminho/sdk python3 build.py
```

Com `LUMO_REQUIRE_RELEASE_KEY=1`, o build falha em vez de usar a chave de teste.

## Atenção: a primeira instalação com a chave nova

Um celular com o Lumo assinado com a chave de teste **recusa** o APK de produção ("App não instalado"). Nesse aparelho,
desinstale o Lumo antigo uma vez e instale o novo.

- As fotos ficam: estão em `Pictures/LUMO` e `Download/LUMO`.
- O que se perde é o que fica dentro do app: lista de eventos, fila de envio, edições, sessão do Fotto e ajustes.
- Faça a troca **entre eventos**, com tudo já enviado ao Fotto.

Depois disso, as atualizações voltam a instalar por cima normalmente.

**Decida o nome do pacote antes.** O pacote atual é `com.hisho.tether`, um nome antigo. Ele também não pode ser trocado
depois de publicar sem obrigar uma nova instalação. Se quiser trocá-lo (por exemplo, para `br.com.lumo.app`), faça isso
junto com a troca de chave, para que os testadores reinstalem uma vez só.

## Play Store

Na Play Store, use esta chave como **chave de upload** e ative o **Play App Signing** (o Google guarda a chave que assina
o app entregue aos clientes). Assim, se a chave de upload for perdida ou vazar, o Google troca a chave de upload e o app
continua atualizando. A Play Store exige AAB, que o `build.py` ainda não gera.
