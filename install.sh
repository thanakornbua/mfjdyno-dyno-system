#!/bin/bash
set -e

# Dyno System Installation Script
#
# Self-contained: downloading and running this script is enough to install
# the dyno backend (dynod), the operator console, and the systemd services.
# No pre-existing checkout of the repo is required — this script clones the
# repo itself into a temporary directory, builds it, and installs it.
#
# Usage:
#   curl -fsSL <raw-url-to-this-script> | sudo bash
# or:
#   sudo ./install.sh

VERSION="1.0.0"
REPO_URL="${REPO_URL:-https://github.com/thanakornbua/mfjdyno-dyno-system.git}"
REPO_REF="${REPO_REF:-main}"
INSTALL_PREFIX="${INSTALL_PREFIX:-/usr/local}"
DATA_DIR="${DATA_DIR:-/var/lib/dyno}"
CONFIG_DIR="${CONFIG_DIR:-/etc/dyno}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "╔══════════════════════════════════════════════════════════╗"
echo "║             Dyno System Installation v${VERSION}              ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# Check if running as root
if [[ $EUID -ne 0 ]]; then
   echo -e "${RED}✗ This script must be run as root${NC}"
   exit 1
fi

# Determine source directory: use the repo we're already inside if this
# script was run from a checkout, otherwise fetch a fresh copy.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLEANUP_SRC_DIR=""
if [ -f "$SCRIPT_DIR/Cargo.toml" ] && [ -d "$SCRIPT_DIR/java" ]; then
    SRC_DIR="$SCRIPT_DIR"
    echo -e "${YELLOW}→ Using existing checkout at $SRC_DIR${NC}"
else
    echo -e "${YELLOW}→ Fetching dyno-system source (no local checkout found)...${NC}"
    for cmd in git; do
        if ! command -v $cmd &> /dev/null; then
            echo -e "${YELLOW}→ Installing $cmd...${NC}"
            apt-get update -qq
            apt-get install -y git
        fi
    done
    SRC_DIR="$(mktemp -d /tmp/dyno-system-src.XXXXXX)"
    CLEANUP_SRC_DIR="$SRC_DIR"
    trap 'if [ -n "$CLEANUP_SRC_DIR" ] && [ -d "$CLEANUP_SRC_DIR" ]; then rm -rf "$CLEANUP_SRC_DIR"; fi' EXIT
    git clone --depth 1 --branch "$REPO_REF" "$REPO_URL" "$SRC_DIR"
    echo -e "${GREEN}✓ Source fetched to $SRC_DIR${NC}"
fi
echo ""

cd "$SRC_DIR"

echo -e "${YELLOW}→ Installing dependencies...${NC}"
apt-get update -qq

# System packages needed to build and run
apt-get install -y \
    build-essential \
    pkg-config \
    unzip \
    curl \
    ca-certificates \
    can-utils \
    fonts-thai-tlwg \
    fonts-noto-cjk \
    >/dev/null

# GUI runtime libraries for the JavaFX console (package names vary per
# release, and a headless box can live without them — best effort).
apt-get install -y libgtk-3-0 libgl1 libxtst6 >/dev/null 2>&1 || \
    apt-get install -y libgtk-3-0t64 libgl1 libxtst6 >/dev/null 2>&1 || true
echo -e "${GREEN}✓ System packages installed${NC}"

# Java 21: from the distro when it ships it, otherwise Temurin from the
# Adoptium repository (e.g. Debian bookworm / Raspberry Pi OS has no
# openjdk-21 package).
if ! apt-get install -y openjdk-21-jdk-headless >/dev/null 2>&1; then
    echo -e "${YELLOW}→ openjdk-21 not in distro repos — adding Adoptium (Temurin) repository...${NC}"
    install -d -m 0755 /etc/apt/keyrings
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        -o /etc/apt/keyrings/adoptium.asc
    . /etc/os-release
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" \
        > /etc/apt/sources.list.d/adoptium.list
    apt-get update -qq
    apt-get install -y temurin-21-jdk >/dev/null
fi

# Pin the build and runtime to a Java 21 VM even when an older default JDK
# is installed alongside it.
JAVA21_HOME="$(find /usr/lib/jvm -maxdepth 1 -type d \( -name '*-21-*' -o -name '*-21' -o -name 'temurin-21-*' \) 2>/dev/null | sort | head -1)"
if [ -n "$JAVA21_HOME" ]; then
    export JAVA_HOME="$JAVA21_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v java >/dev/null 2>&1; then
    echo -e "${RED}✗ No Java runtime found after installation — cannot continue${NC}"
    exit 1
fi

# Rust toolchain (needed to build dyno-core)
if ! command -v cargo &> /dev/null; then
    echo -e "${YELLOW}→ Installing Rust toolchain...${NC}"
    export RUSTUP_HOME=/opt/rustup
    export CARGO_HOME=/opt/cargo
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable --profile minimal
    ln -sf /opt/cargo/bin/* /usr/local/bin/
fi
echo -e "${GREEN}✓ Rust toolchain ready ($(cargo --version))${NC}"
echo -e "${GREEN}✓ Java toolchain ready ($(java -version 2>&1 | head -1))${NC}"
echo ""

# Create dedicated system user/group for the services
if ! id -u dyno &> /dev/null; then
    echo -e "${YELLOW}→ Creating 'dyno' system user...${NC}"
    useradd --system --create-home --home-dir /var/lib/dyno --shell /usr/sbin/nologin dyno
    echo -e "${GREEN}✓ 'dyno' user created${NC}"
    echo ""
fi
# Serial adapters (/dev/ttyUSB*, /dev/ttyACM*) are group 'dialout'
usermod -aG dialout dyno 2>/dev/null || true

# Build backend
echo -e "${YELLOW}→ Building backend (dyno-core)...${NC}"
cargo build --release -p dyno-core
echo -e "${GREEN}✓ Backend built${NC}"
echo ""

# Build operator console
echo -e "${YELLOW}→ Building operator console...${NC}"
(cd java && chmod +x gradlew && ./gradlew --no-daemon distZip)
echo -e "${GREEN}✓ Operator console built${NC}"
echo ""

# Create directories
echo -e "${YELLOW}→ Creating directories...${NC}"
mkdir -p "$DATA_DIR"
mkdir -p "$CONFIG_DIR"
mkdir -p /opt/dyno-operator-console
echo -e "${GREEN}✓ Directories created${NC}"
echo ""

# Install backend binary (the dyno-core crate's [[bin]] is named "dynod")
echo -e "${YELLOW}→ Installing backend (dynod)...${NC}"
cp target/release/dynod "$INSTALL_PREFIX/bin/dynod"
chmod 755 "$INSTALL_PREFIX/bin/dynod"
echo -e "${GREEN}✓ Backend installed to $INSTALL_PREFIX/bin/dynod${NC}"
echo ""

# Install operator console
echo -e "${YELLOW}→ Installing operator console...${NC}"
CONSOLE_ZIP="$(find java/build/distributions -maxdepth 1 -name '*.zip' | head -1)"
if [ -z "$CONSOLE_ZIP" ]; then
    echo -e "${RED}✗ Console distribution not found after build${NC}"
    exit 1
fi
CONSOLE_TMP="$(mktemp -d)"
unzip -q "$CONSOLE_ZIP" -d "$CONSOLE_TMP"
CONSOLE_EXTRACTED_DIR="$(find "$CONSOLE_TMP" -mindepth 1 -maxdepth 1 -type d | head -1)"
rm -rf /opt/dyno-operator-console
mkdir -p /opt/dyno-operator-console
cp -r "$CONSOLE_EXTRACTED_DIR"/* /opt/dyno-operator-console/
rm -rf "$CONSOLE_TMP"
if [ ! -d /opt/dyno-operator-console/lib ]; then
    echo -e "${RED}✗ Console lib directory missing after install${NC}"
    exit 1
fi
# The canonical runner: puts the JavaFX jars on the module path, which a
# plain classpath launch of an Application subclass cannot do.
install -m 755 deploy/bin/run-dyno-operator-console.sh \
    /opt/dyno-operator-console/bin/run-dyno-operator-console.sh
# Sarabun font for PDF export (FontProvider searches this path)
mkdir -p /opt/dyno-operator-console/fonts
cp app/dashboard/fonts/Sarabun-Regular.ttf /opt/dyno-operator-console/fonts/
echo -e "${GREEN}✓ Operator console installed to /opt/dyno-operator-console${NC}"
echo ""

# Install systemd services
echo -e "${YELLOW}→ Installing systemd services...${NC}"
cp deploy/systemd/dyno-canable.service /etc/systemd/system/
cp deploy/systemd/dynod.service /etc/systemd/system/
cp deploy/systemd/dyno-operator-console.service /etc/systemd/system/
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload
    echo -e "${GREEN}✓ Systemd services installed${NC}"
else
    echo -e "${YELLOW}→ systemd is not running (container/chroot?) — unit files installed, reload skipped${NC}"
fi
echo ""

# Copy environment files
echo -e "${YELLOW}→ Setting up configuration files...${NC}"
if [ ! -f "$CONFIG_DIR/dynod.env" ]; then
    cp deploy/env/dynod.env.example "$CONFIG_DIR/dynod.env" 2>/dev/null || \
    cat > "$CONFIG_DIR/dynod.env" << 'EOF'
RUST_LOG=info
DYNO_DATA_DIR=/var/lib/dyno
DYNO_PROFILE=production
DYNO_SOURCE_MODE=live
DYNO_SERIAL_PORT=auto
DYNO_SERIAL_BAUD=115200
DYNO_CAN_IFACE=auto
DYNO_MODBUS_AFR_ENABLED=false
DYNO_BME280_ENABLED=true
DYNO_WS_BIND=0.0.0.0:9000
DYNO_API_BIND=0.0.0.0:9001
EOF
    echo -e "${GREEN}✓ Created $CONFIG_DIR/dynod.env${NC}"
else
    echo -e "${YELLOW}→ $CONFIG_DIR/dynod.env already exists (skipped)${NC}"
fi

if [ ! -f "$CONFIG_DIR/operator-console.env" ]; then
    cp deploy/env/dyno-operator-console.env.example "$CONFIG_DIR/operator-console.env" 2>/dev/null || \
    cat > "$CONFIG_DIR/operator-console.env" << 'EOF'
DYNO_UI_API_BASE_URL=http://localhost:9001
DYNO_UI_WS_URI=ws://localhost:9000
DYNO_UI_MODE=fullscreen
EOF
    echo -e "${GREEN}✓ Created $CONFIG_DIR/operator-console.env${NC}"
else
    echo -e "${YELLOW}→ $CONFIG_DIR/operator-console.env already exists (skipped)${NC}"
fi

# Make the console run on the Java 21 VM even if the system default is older
if [ -n "${JAVA21_HOME:-}" ] && ! grep -q '^JAVA_BIN=' "$CONFIG_DIR/operator-console.env"; then
    echo "JAVA_BIN=$JAVA21_HOME/bin/java" >> "$CONFIG_DIR/operator-console.env"
fi
echo ""

# Create launcher script
echo -e "${YELLOW}→ Creating launcher script...${NC}"
cat > "$INSTALL_PREFIX/bin/dyno-operator-console" << 'EOF'
#!/bin/bash
# set -a exports everything the env file assigns, so the java process
# actually sees the DYNO_UI_* configuration.
set -a
[ -f /etc/dyno/operator-console.env ] && . /etc/dyno/operator-console.env
set +a
export DISPLAY="${DISPLAY:-:0}"
exec /opt/dyno-operator-console/bin/run-dyno-operator-console.sh "$@"
EOF
chmod 755 "$INSTALL_PREFIX/bin/dyno-operator-console"
echo -e "${GREEN}✓ Launcher created at $INSTALL_PREFIX/bin/dyno-operator-console${NC}"
echo ""

# Refresh font cache (fonts-thai-tlwg / fonts-noto-cjk already installed above)
fc-cache -f >/dev/null 2>&1 || true

# Set permissions
echo -e "${YELLOW}→ Setting permissions...${NC}"
chown -R dyno:dyno "$DATA_DIR"
chown -R dyno:dyno "$CONFIG_DIR"
echo -e "${GREEN}✓ Permissions set${NC}"
echo ""

# Summary
echo "╔══════════════════════════════════════════════════════════╗"
echo "║            Installation Complete ✓                      ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo "Next steps:"
echo "  1. Edit configuration: sudo nano $CONFIG_DIR/dynod.env"
echo "  2. Enable backend:    sudo systemctl enable --now dynod"
echo "  3. Launch console:    /usr/local/bin/dyno-operator-console"
echo ""
echo "Status commands:"
echo "  • Backend logs:   journalctl -u dynod -f"
echo "  • Backend health: curl http://127.0.0.1:9001/healthz"
echo "  • Services:       systemctl list-units dyno*"
echo ""
