#!/usr/bin/env bash
# Gera a chave de produção do Lumo Android (uma vez só, no SEU computador).
# Precisa do Java (keytool). Uso:  bash tools/gerar-chave-producao.sh "Nome do Estúdio"
# Saída numa pasta nova "lumo-chave-producao" FORA do repositório (no diretório atual):
#   lumo-producao.p12        a chave (guarde backup em 2 lugares; sem ela não há atualização do app)
#   lumo-producao.base64.txt o mesmo arquivo em base64, para colar no segredo LUMO_KEYSTORE_BASE64
#   SEGREDOS.txt             os 3 valores para cadastrar no GitHub
set -euo pipefail
owner="${1:-Lumo}"
command -v keytool >/dev/null || { echo "keytool não encontrado: instale o Java 17 ou mais novo (ex.: Temurin)."; exit 1; }
out="$(pwd)/lumo-chave-producao"
if git -C "$(pwd)" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  out="$(cd "$(git rev-parse --show-toplevel)/.." && pwd)/lumo-chave-producao"
fi
[ -e "$out/lumo-producao.p12" ] && { echo "Já existe $out/lumo-producao.p12. Não sobrescrevo uma chave de produção."; exit 1; }
mkdir -p "$out"; chmod 700 "$out"
pass="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
alias="lumo"
cn="$(printf '%s' "$owner" | tr -d ',=+<>#;\\"')"
keytool -genkeypair -v -storetype PKCS12 -keystore "$out/lumo-producao.p12" -alias "$alias" \
  -keyalg RSA -keysize 4096 -validity 10000 -storepass "$pass" -keypass "$pass" \
  -dname "CN=$cn, O=$cn, C=BR" >/dev/null 2>&1
base64 < "$out/lumo-producao.p12" | tr -d '\n' > "$out/lumo-producao.base64.txt"
sha="$(keytool -list -v -keystore "$out/lumo-producao.p12" -storepass "$pass" -alias "$alias" | grep -m1 'SHA256:' | sed 's/.*SHA256: *//')"
cat > "$out/SEGREDOS.txt" <<TXT
Chave de produção do Lumo Android — gerada em $(date '+%Y-%m-%d %H:%M')

GitHub → repositório → Settings → Secrets and variables → Actions → New repository secret:

  LUMO_KEYSTORE_BASE64   = conteúdo inteiro de lumo-producao.base64.txt
  LUMO_KEYSTORE_PASSWORD = $pass
  LUMO_KEY_ALIAS         = $alias

Impressão digital SHA-256 do certificado (confira no log do build "Lumo Android"):
  $sha

GUARDE esta pasta em dois lugares seguros (ex.: gerenciador de senhas + pendrive).
Sem este arquivo e esta senha, o Lumo instalado nos clientes não pode mais ser atualizado.
Nunca coloque esta pasta dentro do repositório.
TXT
chmod 600 "$out"/*
echo "Chave criada em: $out"
echo "Abra $out/SEGREDOS.txt e siga as instruções."
