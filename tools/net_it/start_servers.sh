#!/bin/bash
# Starts real network servers on 127.0.0.1 for the P13 integration tests (NetIntegrationTest): OpenSSH (SFTP)
# :2222, Samba :4450, pyftpdlib :2121, WsgiDAV :8081, tools/net_test_server.py :8765 (Basic auth), MiniDLNA :8200.
# Debian/Ubuntu, as root (a throwaway container or VM: it adds the user "vptest" and its files under /srv/vptest).
# Then: VP_NET_IT=1 ./gradlew :app:testDebugUnitTest --tests 'com.vibeplayer.net.NetIntegrationTest'
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO=$(pwd)
W=/tmp/vp_net_it
mkdir -p "$W" /run/sshd /run/samba/ncalrpc "$W/smb" "$W/dlna"
apt-get install -y -qq openssh-server samba minidlna ffmpeg python3-venv >/dev/null
[ -x "$W/venv/bin/python" ] || { python3 -m venv "$W/venv"; "$W/venv/bin/pip" install -q pyftpdlib wsgidav cheroot; }

id vptest >/dev/null 2>&1 || useradd -m -d /srv/vptest -s /bin/bash vptest
echo 'vptest:vppass' | chpasswd
M=/srv/vptest/Movies
mkdir -p "$M/Season1" "$M/.hidden"
for n in 01 02; do
  [ -f "$M/ep$n.mkv" ] || ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=320x240:rate=24 -f lavfi -i sine=frequency=440 \
    -t 6 -c:v libx264 -preset ultrafast -g 24 -c:a aac "$M/ep$n.mkv"
done
printf '1\n00:00:00,500 --> 00:00:02,000\nhello\n' > "$M/ep01.srt"
echo notes > "$M/notes.txt"
cp -n "$M/ep01.mkv" "$M/Season1/Ep 1 (spaced).mkv"
chown -R vptest:vptest /srv/vptest
chmod -R a+rX /srv/vptest

ssh-keygen -A >/dev/null 2>&1
cat > "$W/sshd.conf" <<CONF
Port 2222
ListenAddress 127.0.0.1
PasswordAuthentication yes
KbdInteractiveAuthentication no
UsePAM yes
Subsystem sftp internal-sftp
PidFile $W/sshd.pid
HostKey /etc/ssh/ssh_host_ed25519_key
HostKey /etc/ssh/ssh_host_rsa_key
CONF
/usr/sbin/sshd -f "$W/sshd.conf"

cat > "$W/smb.conf" <<CONF
[global]
  smb ports = 4450
  interfaces = lo
  bind interfaces only = yes
  server min protocol = SMB2_02
  pid directory = $W/smb
  lock directory = $W/smb
  state directory = $W/smb
  cache directory = $W/smb
  private dir = $W/smb
  log file = $W/smb/log
[Movies]
  path = $M
  read only = yes
  valid users = vptest
CONF
(echo vppass; echo vppass) | smbpasswd -c "$W/smb.conf" -s -a vptest >/dev/null
smbd -s "$W/smb.conf" -D

cat > "$W/ftpd.py" <<'PY'
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer
a = DummyAuthorizer()
a.add_user("vptest", "vppass", "/srv/vptest", perm="elr")
a.add_anonymous("/srv/vptest/Movies")
FTPHandler.authorizer = a
FTPHandler.passive_ports = range(30000, 30050)
FTPServer(("127.0.0.1", 2121), FTPHandler).serve_forever()
PY
nohup "$W/venv/bin/python" "$W/ftpd.py" > "$W/ftpd.log" 2>&1 &

cat > "$W/wsgidav.yaml" <<'YAML'
host: 127.0.0.1
port: 8081
provider_mapping:
  "/dav": "/srv/vptest"
http_authenticator:
  domain_controller: null
  accept_basic: true
  accept_digest: false
  default_to_digest: false
simple_dc:
  user_mapping:
    "*":
      "vptest":
        password: "vppass"
YAML
nohup "$W/venv/bin/wsgidav" --config "$W/wsgidav.yaml" > "$W/dav.log" 2>&1 &
nohup python3 "$REPO/tools/net_test_server.py" "$M" 8765 --auth vptest:vppass > "$W/http.log" 2>&1 &

cat > "$W/minidlna.conf" <<CONF
port=8200
network_interface=lo
media_dir=V,$M
friendly_name=VibeTest DLNA
db_dir=$W/dlna
log_dir=$W/dlna
inotify=no
CONF
minidlnad -f "$W/minidlna.conf" -P "$W/dlna/pid" -R
sleep 4
echo "servers up: sftp :2222, smb :4450, ftp :2121, webdav :8081, http :8765, dlna :8200 (user vptest / vppass)"
