#!/bin/bash
set -e

# Dyno System Installation Script
# Installs the dyno backend (dynod) and operator console

VERSION="1.0.0"
INSTALL_PREFIX="${INSTALL_PREFIX:-/usr/local}"
DATA_DIR="${DATA_DIR:-/var/lib/dyno}"
CONFIG_DIR="${CONFIG_DIR:-/etc/dyno}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "╔══════════════════════════════════════════════════════════╗"
echo "║          Dyno System Installation v${VERSION}           ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# Check if running as root
if [[ $EUID -ne 0 ]]; then
   echo -e "${RED}✗ This script must be run as root${NC}"
   exit 1
fi

echo -e "${YELLOW}→ Checking dependencies...${NC}"

# Check for required tools
for cmd in systemctl java cargo; do
    if ! command -v $cmd &> /dev/null; then
        echo -e "${RED}✗ $cmd is not installed${NC}"
        exit 1
    fi
done

echo -e "${GREEN}✓ Dependencies OK${NC}"
echo ""

# Create directories
echo -e "${YELLOW}→ Creating directories...${NC}"
mkdir -p "$DATA_DIR"
mkdir -p "$CONFIG_DIR"
mkdir -p /opt/dyno-operator-console
echo -e "${GREEN}✓ Directories created${NC}"
echo ""

# Install backend binary
echo -e "${YELLOW}→ Installing backend (dynod)...${NC}"
if [ -f "target/release/dyno-core" ]; then
    cp target/release/dyno-core "$INSTALL_PREFIX/bin/dynod"
    chmod 755 "$INSTALL_PREFIX/bin/dynod"
    echo -e "${GREEN}✓ Backend installed to $INSTALL_PREFIX/bin/dynod${NC}"
else
    echo -e "${RED}✗ Backend binary not found. Run 'cargo build --release -p dyno-core' first${NC}"
    exit 1
fi
echo ""

# Install operator console
echo -e "${YELLOW}→ Installing operator console...${NC}"
if [ -f "java/build/distributions/operator-console.zip" ]; then
    unzip -q "java/build/distributions/operator-console.zip" -d /opt/
    if [ -d "/opt/operator-console-1.0.0" ]; then
        rm -rf /opt/dyno-operator-console/*
        cp -r /opt/operator-console-1.0.0/* /opt/dyno-operator-console/
        rm -rf /opt/operator-console-1.0.0
    fi
    echo -e "${GREEN}✓ Operator console installed to /opt/dyno-operator-console${NC}"
else
    echo -e "${RED}✗ Console distribution not found. Run 'cd java && ./gradlew distZip' first${NC}"
    exit 1
fi
echo ""

# Install systemd services
echo -e "${YELLOW}→ Installing systemd services...${NC}"
cp deploy/systemd/dyno-canable.service /etc/systemd/system/ 2>/dev/null || true
cp deploy/systemd/dynod.service /etc/systemd/system/ 2>/dev/null || true
cp deploy/systemd/dyno-operator-console.service /etc/systemd/system/ 2>/dev/null || true
systemctl daemon-reload
echo -e "${GREEN}✓ Systemd services installed${NC}"
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
echo ""

# Create launcher script
echo -e "${YELLOW}→ Creating launcher script...${NC}"
cat > "$INSTALL_PREFIX/bin/dyno-operator-console" << 'EOF'
#!/bin/bash
. /etc/dyno/operator-console.env
export DISPLAY="${DISPLAY:-:0}"
exec /opt/dyno-operator-console/bin/operator-console "$@"
EOF
chmod 755 "$INSTALL_PREFIX/bin/dyno-operator-console"
echo -e "${GREEN}✓ Launcher created at $INSTALL_PREFIX/bin/dyno-operator-console${NC}"
echo ""

# Install Thai fonts
echo -e "${YELLOW}→ Installing Thai fonts...${NC}"
apt-get update -qq
apt-get install -y fonts-thai-tlwg fonts-noto-cjk >/dev/null 2>&1
fc-cache -fv >/dev/null 2>&1
echo -e "${GREEN}✓ Thai fonts installed${NC}"
echo ""

# Set permissions
echo -e "${YELLOW}→ Setting permissions...${NC}"
chown -R dyno:dyno "$DATA_DIR" 2>/dev/null || mkdir -p "$DATA_DIR"
chown -R dyno:dyno "$CONFIG_DIR" 2>/dev/null || mkdir -p "$CONFIG_DIR"
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
