# Companion Sufficit Wake-on-LAN

O Android moderno nao pode ler a tabela ARP. Este processo local, sem senha de
roteador, responde a uma consulta UDP com nonce na porta 45991 e devolve os
MACs/IPs que a maquina Linux ja conhece. Nenhum comando e aceito e nenhuma
informacao deixa a rede local.

```sh
install -Dm755 sufficit_wol_lan_companion.py ~/.local/lib/sufficit/wol-lan-companion.py
install -Dm644 sufficit-wol-lan-companion.service ~/.config/systemd/user/sufficit-wol-lan-companion.service
systemctl --user daemon-reload
systemctl --user enable --now sufficit-wol-lan-companion.service
```
