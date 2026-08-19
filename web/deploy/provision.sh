#!/usr/bin/env bash
#
# Creates the droplet Tasty Radio runs on, and points DNS at it.
#
# Run once. Everything after this is `git push` — see .github/workflows/web-deploy.yml. Re-running
# is safe: each step checks for what it would create before creating it.
#
#   DIGITALOCEAN_ACCESS_TOKEN=… ./provision.sh
#
# What it makes, and what it costs: one s-1vcpu-1gb droplet in Sydney, $6/month, 1 TB of transfer
# included. Transfer is the number that matters — a listener with four stations up pulls a steady
# ~64 KB/s through the proxy, so 1 TB is roughly 4,000 listener-hours a month. A handful of friends
# will not come close.

set -euo pipefail

NAME="${NAME:-tastyradio}"
REGION="${REGION:-syd1}"
SIZE="${SIZE:-s-1vcpu-1gb}"
IMAGE="${IMAGE:-ubuntu-24-04-x64}"
DOMAIN="${DOMAIN:-truthseekersbyo.com}"
SUBDOMAIN="${SUBDOMAIN:-radio}"
KEY_FILE="${KEY_FILE:-$HOME/.ssh/tastyradio_deploy}"
KEY_NAME="${KEY_NAME:-tastyradio-deploy}"

say() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- ssh key

if [[ ! -f "$KEY_FILE" ]]; then
  say "Making a deploy key at $KEY_FILE"
  ssh-keygen -t ed25519 -N '' -C 'tastyradio-deploy' -f "$KEY_FILE"
fi

if ! doctl compute ssh-key list --format Name --no-header | grep -qx "$KEY_NAME"; then
  say "Registering the deploy key with DigitalOcean"
  doctl compute ssh-key import "$KEY_NAME" --public-key-file "$KEY_FILE.pub"
fi
KEY_ID=$(doctl compute ssh-key list --format ID,Name --no-header | awk -v n="$KEY_NAME" '$2==n {print $1}')

# ---------------------------------------------------------------- droplet

if doctl compute droplet list --format Name --no-header | grep -qx "$NAME"; then
  say "Droplet $NAME already exists"
else
  say "Creating $NAME ($SIZE in $REGION)"
  # Docker, a firewall, and a gigabyte of swap. The swap is not optional on a 1 GB box: the index
  # is a 34 MB file that gets memory-mapped, and headroom is cheaper than an OOM kill at 3am.
  cat > /tmp/tastyradio-cloud-init.yml <<'CLOUDINIT'
#cloud-config
package_update: true
packages:
  - ca-certificates
  - curl
  - ufw
runcmd:
  - install -m 0755 -d /etc/apt/keyrings
  - curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  - chmod a+r /etc/apt/keyrings/docker.asc
  - echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" > /etc/apt/sources.list.d/docker.list
  - apt-get update
  - DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  - systemctl enable --now docker
  - fallocate -l 1G /swapfile
  - chmod 600 /swapfile
  - mkswap /swapfile
  - swapon /swapfile
  - echo '/swapfile none swap sw 0 0' >> /etc/fstab
  - mkdir -p /opt/tastyradio
  - ufw allow OpenSSH
  - ufw allow 80/tcp
  - ufw allow 443/tcp
  - ufw --force enable
  - touch /opt/tastyradio/.provisioned
CLOUDINIT

  doctl compute droplet create "$NAME" \
    --region "$REGION" \
    --size "$SIZE" \
    --image "$IMAGE" \
    --ssh-keys "$KEY_ID" \
    --user-data-file /tmp/tastyradio-cloud-init.yml \
    --enable-monitoring \
    --tag-name tastyradio \
    --wait
fi

IP=$(doctl compute droplet list --format Name,PublicIPv4 --no-header | awk -v n="$NAME" '$1==n {print $2}')
say "Droplet is at $IP"

# ---------------------------------------------------------------- dns

# Additive only. This domain has a live site and Google Workspace mail on it; nothing here touches
# an existing record.
if doctl compute domain records list "$DOMAIN" --format Type,Name --no-header |
  awk '$1=="A"' | grep -qx "A *$SUBDOMAIN" 2>/dev/null; then
  say "$SUBDOMAIN.$DOMAIN already points somewhere — leaving it alone"
else
  say "Pointing $SUBDOMAIN.$DOMAIN at $IP"
  doctl compute domain records create "$DOMAIN" \
    --record-type A --record-name "$SUBDOMAIN" --record-data "$IP" --record-ttl 300
fi

say "Done. The site will be https://$SUBDOMAIN.$DOMAIN once the first deploy runs."
echo
echo "Set these as GitHub repository secrets:"
echo "  DROPLET_HOST    $IP"
echo "  SITE_HOST       $SUBDOMAIN.$DOMAIN"
echo "  DROPLET_SSH_KEY  (the contents of $KEY_FILE)"
